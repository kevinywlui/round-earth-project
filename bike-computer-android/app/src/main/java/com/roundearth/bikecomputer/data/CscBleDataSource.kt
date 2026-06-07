package com.roundearth.bikecomputer.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A CSC sensor seen during scanning, surfaced to the picker UI. */
data class DiscoveredSensor(
    val address: String,
    val name: String,
    val rssi: Int,
    val paired: Boolean,
    val connected: Boolean,
)

/**
 * Connects to CSC (Cycling Speed and Cadence) sensors over BLE, subscribes to
 * their measurement notifications, and turns them into both live dashboard
 * values and raw [WheelRevolutionReading]s for persistence.
 *
 * Scans for the CSC service (0x1816) and surfaces every device via [discovered]
 * for the picker UI. It connects only to sensors the user has **paired** (their
 * addresses come from [pairedSensors]); pairing more than one is supported, so a
 * dedicated speed sensor and a dedicated cadence sensor merge into one stream.
 * Wheel data feeds speed/odometer (using the configured circumference) and the
 * persisted readings; crank data feeds cadence.
 *
 * Callers MUST hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT (API 31+) before [start].
 */
@SuppressLint("MissingPermission")
class CscBleDataSource(
    private val context: Context,
    /** Current wheel circumference in meters; read fresh on each revolution. */
    private val wheelCircumferenceM: () -> Double,
    /** Current compass heading in degrees [0, 360); read fresh on each revolution. */
    private val heading: () -> Float = { 0f },
    /** Current magnetic declination in degrees (positive east); read fresh per use. */
    private val declination: () -> Float = { 0f },
    /** Addresses of sensors the user has chosen to connect to. */
    private val pairedSensors: Flow<Set<String>> = MutableStateFlow(emptySet()),
) : BikeDataSource {

    private val scope = CoroutineScope(SupervisorJob())
    private var staleJob: Job? = null
    private var headingJob: Job? = null
    private var pairedJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _data = MutableStateFlow(RawBikeData(0.0, 0.0, 0f, 0f, 0.0))
    override val data: StateFlow<RawBikeData> = _data

    // Unlimited buffer so no revolution is ever dropped — the recorded stream
    // must stay lossless even if the consumer (DB writes) briefly lags.
    private val _readings = Channel<WheelRevolutionReading>(Channel.UNLIMITED)
    override val revolutionReadings = _readings.receiveAsFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredSensor>>(emptyList())
    /** Sensors seen while scanning, with their paired/connected status. */
    val discovered: StateFlow<List<DiscoveredSensor>> = _discovered

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Volatile private var pairedAddresses: Set<String> = emptySet()
    private var scanning = false

    // Merged live state across all connected sensors.
    @Volatile private var speedKph = 0.0
    @Volatile private var cadenceRpm = 0.0
    @Volatile private var odometerM = 0.0
    @Volatile private var lastActivityAt = 0L

    private val devices = ConcurrentHashMap<String, BluetoothDevice>()      // address -> last seen device
    private val seen = ConcurrentHashMap<String, DiscoveredSensor>()        // address -> UI model
    private val connections = ConcurrentHashMap<String, SensorConnection>() // address -> live connection

    override fun start() {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or disabled")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        if (scanning) return
        pairedJob = scope.launch {
            pairedSensors.collect { paired ->
                pairedAddresses = paired
                connections.keys.filter { it !in paired }.forEach { disconnect(it) }
                paired.forEach { addr -> if (devices.containsKey(addr)) connectIfNeeded(addr) }
                publishDiscovered()
                updateConnectionState()
            }
        }
        startScan()
        startStaleWatcher()
        startHeadingTicker()
    }

    override fun stop() {
        stopScan()
        connections.values.forEach { it.gatt?.close() }
        connections.clear()
        staleJob?.cancel(); staleJob = null
        headingJob?.cancel(); headingJob = null
        pairedJob?.cancel(); pairedJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CSC_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            // Missing BLUETOOTH_SCAN — caller should have requested it first.
            Log.e(TAG, "scan blocked: missing permission", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        scanning = true
        updateConnectionState()
        Log.i(TAG, "scanning for CSC sensors")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address
            devices[address] = device
            // scanRecord.deviceName comes from the advertisement (no CONNECT needed).
            val name = result.scanRecord?.deviceName ?: address
            seen[address] = DiscoveredSensor(
                address = address,
                name = name,
                rssi = result.rssi,
                paired = address in pairedAddresses,
                connected = connections[address]?.ready == true,
            )
            publishDiscovered()
            if (address in pairedAddresses) connectIfNeeded(address)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            scanning = false
            updateConnectionState()
        }
    }

    private fun connectIfNeeded(address: String) {
        if (connections.containsKey(address)) return
        val device = devices[address] ?: return
        val conn = SensorConnection(address)
        connections[address] = conn
        Log.i(TAG, "connecting to $address")
        conn.gatt = device.connectGatt(context, false, conn)
    }

    private fun disconnect(address: String) {
        connections.remove(address)?.gatt?.close()
        markConnected(address, false)
    }

    private fun markConnected(address: String, connected: Boolean) {
        seen[address]?.let { seen[address] = it.copy(connected = connected) }
        publishDiscovered()
        updateConnectionState()
    }

    private fun publishDiscovered() {
        _discovered.value = seen.values
            .map { it.copy(paired = it.address in pairedAddresses) }
            .sortedWith(compareByDescending<DiscoveredSensor> { it.connected }.thenByDescending { it.rssi })
    }

    private fun updateConnectionState() {
        _connectionState.value = when {
            connections.values.any { it.ready } -> ConnectionState.CONNECTED
            scanning -> ConnectionState.SCANNING
            else -> ConnectionState.DISCONNECTED
        }
    }

    private fun emitData() {
        val h = heading()
        val t = trueFromMagnetic(h, declination())
        _data.value = RawBikeData(
            speedKph = speedKph,
            cadenceRpm = cadenceRpm,
            bearingDegrees = h,
            trueBearingDegrees = t,
            odometerKm = odometerM / 1000.0,
        )
    }

    /** Zeroes speed/cadence when no revolutions arrive (the bike has stopped). */
    private fun startStaleWatcher() {
        staleJob?.cancel()
        staleJob = scope.launch {
            while (true) {
                delay(1_000)
                val idle = SystemClock.elapsedRealtime() - lastActivityAt
                if (lastActivityAt > 0 && idle > STALE_MS && (speedKph != 0.0 || cadenceRpm != 0.0)) {
                    speedKph = 0.0
                    cadenceRpm = 0.0
                    _data.update { it.copy(speedKph = 0.0, cadenceRpm = 0.0) }
                }
            }
        }
    }

    /**
     * Keeps the live bearing fresh from the compass even while stopped — wheel
     * notifications (which otherwise carry the heading) only arrive while moving.
     */
    private fun startHeadingTicker() {
        headingJob?.cancel()
        headingJob = scope.launch {
            while (true) {
                val h = heading()
                val t = trueFromMagnetic(h, declination())
                // Atomic compare-and-set; ignore sub-degree sensor jitter so a
                // stationary phone produces no churn. Returning the same instance
                // skips the emission (StateFlow dedups equal values).
                _data.update {
                    if (angularDistance(it.bearingDegrees, h) < HEADING_EPSILON_DEG &&
                        angularDistance(it.trueBearingDegrees, t) < HEADING_EPSILON_DEG
                    ) it
                    else it.copy(bearingDegrees = h, trueBearingDegrees = t)
                }
                delay(250)
            }
        }
    }

    /** One BLE connection to a single CSC sensor, with its own decode state. */
    private inner class SensorConnection(private val address: String) : BluetoothGattCallback() {

        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var ready = false
        private val decoder = CscMeasurementDecoder()

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "connected $address, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "disconnected $address (status=$status)")
                    g.close()
                    connections.remove(address)
                    ready = false
                    // Continuous scanning will rediscover and reconnect if still paired.
                    markConnected(address, false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val measurement = g.getService(CSC_SERVICE_UUID)?.getCharacteristic(CSC_MEASUREMENT_UUID)
            if (measurement == null) {
                Log.e(TAG, "CSC measurement characteristic not found on $address")
                return
            }
            g.setCharacteristicNotification(measurement, true)
            val cccd = measurement.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            ready = true
            markConnected(address, true)
            Log.i(TAG, "subscribed to $address")
        }

        @Deprecated("Deprecated in API 33; the ByteArray overload is used there")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == CSC_MEASUREMENT_UUID) handleMeasurement(c.value)
        }

        // API 33+ overload.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == CSC_MEASUREMENT_UUID) handleMeasurement(value)
        }

        private fun handleMeasurement(bytes: ByteArray?) {
            if (bytes == null || bytes.isEmpty()) return
            val circumference = wheelCircumferenceM()
            val result = decoder.decode(bytes, circumference)

            // Persist every wheel revolution losslessly for the time-series.
            result.wheelCumulativeRevs?.let { revs ->
                val h = heading()
                _readings.trySend(
                    WheelRevolutionReading(
                        timestampMillis = System.currentTimeMillis(),
                        cumulativeRevolutions = revs,
                        sensorEventTime1024 = result.wheelEventTime1024 ?: 0,
                        wheelCircumferenceM = circumference,
                        headingDegrees = h,
                        trueHeadingDegrees = trueFromMagnetic(h, declination()),
                    )
                )
            }

            var changed = false
            result.speedKph?.let {
                speedKph = it
                odometerM += result.distanceMeters
                lastActivityAt = SystemClock.elapsedRealtime()
                changed = true
            }
            result.cadenceRpm?.let {
                cadenceRpm = it
                lastActivityAt = SystemClock.elapsedRealtime()
                changed = true
            }
            if (changed) emitData()
        }
    }

    companion object {
        private const val TAG = "CscBleDataSource"
        private const val STALE_MS = 2_500L
        // Minimum heading change (degrees) that warrants a live update.
        private const val HEADING_EPSILON_DEG = 1f
        private val CSC_SERVICE_UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        private val CSC_MEASUREMENT_UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
