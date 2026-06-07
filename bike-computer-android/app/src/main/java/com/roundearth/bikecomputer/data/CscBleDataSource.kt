package com.roundearth.bikecomputer.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

/**
 * Connects to a CSC (Cycling Speed and Cadence) sensor over BLE, subscribes to
 * wheel-revolution notifications, and turns them into both live dashboard values
 * and raw [WheelRevolutionReading]s for persistence.
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
) : BikeDataSource {

    private val scope = CoroutineScope(SupervisorJob())
    private var staleJob: Job? = null
    private var headingJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _data = MutableStateFlow(RawBikeData(0.0, 0.0, 0f, 0f, 0.0))
    override val data: StateFlow<RawBikeData> = _data

    // Unlimited buffer so no revolution is ever dropped — the recorded stream
    // must stay lossless even if the consumer (DB writes) briefly lags.
    private val _readings = Channel<WheelRevolutionReading>(Channel.UNLIMITED)
    override val revolutionReadings = _readings.receiveAsFlow()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // Rolling state for speed/cadence derivation.
    private var prevRevs: Long = -1
    private var prevEventTime1024: Int = 0
    private var lastReadingWallClock: Long = 0

    override fun start() {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or disabled")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        if (scanning || gatt != null) return
        startScan()
        startStaleWatcher()
        startHeadingTicker()
    }

    override fun stop() {
        stopScan()
        gatt?.close()
        gatt = null
        staleJob?.cancel()
        staleJob = null
        headingJob?.cancel()
        headingJob = null
        prevRevs = -1
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
        _connectionState.value = ConnectionState.SCANNING
        Log.i(TAG, "scanning for CSC sensor")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            Log.i(TAG, "found ${device.address}, connecting")
            stopScan()
            gatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            scanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "disconnected (status=$status), rescanning")
                    g.close()
                    gatt = null
                    prevRevs = -1
                    _connectionState.value = ConnectionState.SCANNING
                    startScan()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(CSC_SERVICE_UUID)
            val measurement = service?.getCharacteristic(CSC_MEASUREMENT_UUID)
            if (measurement == null) {
                Log.e(TAG, "CSC measurement characteristic not found")
                return
            }
            g.setCharacteristicNotification(measurement, true)
            val cccd = measurement.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "subscribed to CSC notifications")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == CSC_MEASUREMENT_UUID) handleMeasurement(c.value)
        }

        // API 33+ overload.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (c.uuid == CSC_MEASUREMENT_UUID) handleMeasurement(value)
        }
    }

    /**
     * Parses a CSC Measurement packet. Wheel data is present when flags bit 0 is
     * set: a 32-bit LE cumulative revolution count followed by a 16-bit LE last
     * wheel event time in 1/1024 s units.
     */
    private fun handleMeasurement(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val flags = bytes[0].toInt()
        if (flags and 0x01 == 0 || bytes.size < 7) return

        val revs = u32le(bytes, 1)
        val eventTime = u16le(bytes, 5)
        val now = System.currentTimeMillis()
        val circumference = wheelCircumferenceM()
        val headingDeg = heading()
        val trueHeadingDeg = trueFromMagnetic(headingDeg, declination())

        _readings.trySend(
            WheelRevolutionReading(
                timestampMillis = now,
                cumulativeRevolutions = revs,
                sensorEventTime1024 = eventTime,
                wheelCircumferenceM = circumference,
                headingDegrees = headingDeg,
                trueHeadingDegrees = trueHeadingDeg,
            )
        )

        if (prevRevs >= 0) {
            val deltaRevs = revs - prevRevs
            // Sensor event time is a 16-bit value that wraps every ~64 s.
            val deltaTicks = (eventTime - prevEventTime1024) and 0xFFFF
            val deltaSec = if (deltaTicks > 0) deltaTicks / 1024.0 else (now - lastReadingWallClock) / 1000.0
            if (deltaRevs > 0 && deltaSec > 0) {
                val speedMs = deltaRevs * circumference / deltaSec
                _data.value = RawBikeData(
                    speedKph = speedMs * 3.6,
                    cadenceRpm = deltaRevs / deltaSec * 60.0,
                    bearingDegrees = headingDeg,
                    trueBearingDegrees = trueHeadingDeg,
                    odometerKm = revs * circumference / 1000.0,
                )
            }
        } else {
            _data.update {
                it.copy(
                    bearingDegrees = headingDeg,
                    trueBearingDegrees = trueHeadingDeg,
                    odometerKm = revs * circumference / 1000.0,
                )
            }
        }

        prevRevs = revs
        prevEventTime1024 = eventTime
        lastReadingWallClock = now
    }

    /** Zeroes speed/cadence when no revolutions arrive (the bike has stopped). */
    private fun startStaleWatcher() {
        staleJob?.cancel()
        staleJob = scope.launch {
            while (true) {
                delay(1_000)
                val idleMs = System.currentTimeMillis() - lastReadingWallClock
                if (lastReadingWallClock > 0 && idleMs > 2_500) {
                    _data.update {
                        if (it.speedKph != 0.0) it.copy(speedKph = 0.0, cadenceRpm = 0.0) else it
                    }
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
                    if (abs(it.bearingDegrees - h) < HEADING_EPSILON_DEG &&
                        abs(it.trueBearingDegrees - t) < HEADING_EPSILON_DEG
                    ) it
                    else it.copy(bearingDegrees = h, trueBearingDegrees = t)
                }
                delay(250)
            }
        }
    }

    private fun u16le(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24)

    companion object {
        private const val TAG = "CscBleDataSource"
        // Minimum heading change (degrees) that warrants a live update.
        private const val HEADING_EPSILON_DEG = 1f
        private val CSC_SERVICE_UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        private val CSC_MEASUREMENT_UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
