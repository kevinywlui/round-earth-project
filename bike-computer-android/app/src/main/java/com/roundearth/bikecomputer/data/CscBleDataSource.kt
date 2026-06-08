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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.atomic.AtomicReference

/** A CSC sensor seen during scanning, surfaced to the picker UI. */
data class DiscoveredSensor(
    val address: String,
    val name: String,
    val rssi: Int,
    val paired: Boolean,
    val connected: Boolean,
)

/**
 * Connects to a single CSC (Cycling Speed and Cadence) sensor over BLE, subscribes
 * to its wheel-revolution notifications, and turns them into both live dashboard
 * values and raw [WheelRevolutionReading]s for persistence.
 *
 * Scans for the CSC service (0x1816) and surfaces every device via [discovered] for
 * the picker UI. It connects only to the one sensor the user has **paired** (its
 * address comes from [pairedSensor]). Wheel data feeds speed/odometer (using the
 * configured circumference) and the persisted readings.
 *
 * Callers MUST hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT (API 31+) before [start].
 */
@SuppressLint("MissingPermission")
class CscBleDataSource(
    private val context: Context,
    /** Current wheel circumference in meters; read fresh on each revolution. */
    private val wheelCircumferenceM: () -> Double,
    /** Current compass heading in degrees [0, 360), or NaN when unknown; read fresh on each revolution. */
    private val heading: () -> Float = { Float.NaN },
    /** Current magnetic declination in degrees (positive east); read fresh per use. */
    private val declination: () -> Float = { 0f },
    /** Address of the sensor the user has chosen to connect to (null if none). */
    private val pairedSensor: Flow<String?> = MutableStateFlow(null),
) : BikeDataSource {

    // Process-lifetime scope by design (this is an Application-scoped singleton); it is
    // never cancelled. Because of that, stop() must cancel EVERY launched job individually
    // — currently staleJob, headingJob, pairedJob. Add any new job to that list in stop().
    private val scope = CoroutineScope(SupervisorJob())
    private var staleJob: Job? = null
    private var headingJob: Job? = null
    private var pairedJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    // Seed bearings to NaN ("unknown"), not 0° (= due north): the heading ticker
    // fills them within ~250 ms, but any consumer reading _data in that window must
    // see "unknown" rather than a false north. NaN propagates through the UI as "---".
    private val _data = MutableStateFlow(RawBikeData(0.0, Float.NaN, Float.NaN, 0.0))
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

    @Volatile private var pairedAddress: String? = null
    private var scanning = false

    // True between start() and stop(): the caller wants the source live. Kept independent
    // of the adapter being on, so the Bluetooth-state receiver knows whether to revive
    // scanning when BT is toggled back on.
    @Volatile private var wantRunning = false
    private var receiverRegistered = false

    // Reconnect backoff (the state machine that throttles status-133 flaps, null
    // connectGatt, and unconfirmed CCCD writes). Extracted so its rules are unit tested
    // in ReconnectPolicyTest; it reads the same monotonic clock used everywhere here.
    private val reconnect = ReconnectPolicy { SystemClock.elapsedRealtime() }

    // Live state. GATT callbacks run on binder threads, so read-modify-writes of the
    // odometer go through [stateLock]; the rest are plain volatiles (single writer each).
    private val stateLock = Any()
    @Volatile private var speedKph = 0.0
    @Volatile private var odometerM = 0.0
    @Volatile private var lastActivityAt = 0L
    private var sensorDistanceM = 0.0 // guarded by stateLock; this ride's measured distance
    private var odometerSeedM = 0.0   // guarded by stateLock; distance carried from a resumed ride

    private fun recomputeOdometerLocked() {
        odometerM = odometerSeedM + sensorDistanceM
    }

    private val devices = ConcurrentHashMap<String, BluetoothDevice>() // address -> last seen device
    private val seen = ConcurrentHashMap<String, DiscoveredSensor>()   // address -> UI model
    // The single live connection (CAS so a concurrent scan callback + paired-flow emission
    // can't both open a GATT client, and a late drop callback can't evict a newer one).
    //
    // Ownership contract (four threads mutate this: scan-callback connect, paired-flow
    // teardown, main-thread stop/onAdapterOff, binder-thread onConnectionStateChange):
    //  - The slot is CLAIMED (compareAndSet null -> conn) BEFORE connectGatt, and conn.gatt
    //    is PUBLISHED after the handle comes back; connectIfNeeded re-checks ownership and
    //    closes the handle itself if a teardown won the slot in that pre-publish window.
    //  - Every RELEASE path (stop/onAdapterOff/teardown/onConnectionStateChange) must close
    //    conn.gatt, and only the thread that wins the CAS off the slot owns that close.
    private val connection = AtomicReference<SensorConnection?>(null)

    // Best-effort read of the slot: snapshots connection.get() once, so a concurrent teardown
    // can only make the result one tick stale (report a just-dropped link as connected, or
    // vice-versa), never tear or NPE. Same for updateConnectionState() below.
    private fun isConnected(address: String): Boolean =
        connection.get()?.let { it.address == address && it.ready } == true

    override fun start() {
        if (wantRunning) return // idempotent: onForeground() may re-enter while already live
        wantRunning = true

        // These don't depend on the Bluetooth adapter, and the receiver must be live so a
        // start() issued while BT is off still recovers once the user turns it on.
        registerBtStateReceiver()
        pairedJob = scope.launch {
            pairedSensor.collect { addr ->
                pairedAddress = addr
                // Drop a connection to a sensor that is no longer the chosen one.
                connection.get()?.let { if (it.address != addr) teardown(it) }
                // Connect the newly chosen sensor if we've already seen it advertising.
                if (addr != null && devices.containsKey(addr)) connectIfNeeded(addr)
                publishDiscovered()
                updateConnectionState()
            }
        }
        startStaleWatcher()
        startHeadingTicker()

        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            // Not fatal: the BT-state receiver starts scanning once the adapter turns on.
            Log.w(TAG, "Bluetooth disabled; waiting for it to turn on")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        startScan()
    }

    override fun stop() {
        wantRunning = false
        unregisterBtStateReceiver()
        stopScan()
        connection.getAndSet(null)?.gatt?.close()
        reconnect.clear()
        staleJob?.cancel(); staleJob = null
        headingJob?.cancel(); headingJob = null
        pairedJob?.cancel(); pairedJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // --- Bluetooth adapter on/off recovery ---

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth turned on")
                    if (wantRunning && !scanning) {
                        // Fresh start: a deliberate toggle shouldn't inherit stale backoff.
                        reconnect.clear()
                        startScan()
                    }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth turning off; tearing down BLE")
                    onAdapterOff()
                }
            }
        }
    }

    private fun registerBtStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // ACTION_STATE_CHANGED is a protected system broadcast; NOT_EXPORTED is correct and
        // required on API 33+ for context-registered receivers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(btStateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterBtStateReceiver() {
        if (!receiverRegistered) return
        // Guard against "Receiver not registered" if the system already reclaimed it.
        runCatching { context.unregisterReceiver(btStateReceiver) }
        receiverRegistered = false
    }

    /**
     * The adapter went down: drop scanning and the GATT link, but keep [wantRunning] so
     * the STATE_ON handler revives everything. The scanner instance is invalid once the
     * adapter is off, so we flip [scanning] directly rather than calling into it.
     */
    private fun onAdapterOff() {
        scanning = false
        connection.getAndSet(null)?.gatt?.close()
        reconnect.clear()
        seen.keys.forEach { addr -> seen[addr]?.let { seen[addr] = it.copy(connected = false) } }
        publishDiscovered()
        updateConnectionState()
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
                // Placeholder: publishDiscovered() is the single authority for `paired`
                // (it recomputes it on every emission, since pairedAddress can change
                // without a rescan). The value stored here never reaches the UI.
                paired = false,
                connected = isConnected(address),
            )
            publishDiscovered()
            if (address == pairedAddress) connectIfNeeded(address)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            scanning = false
            updateConnectionState()
        }
    }

    private fun connectIfNeeded(address: String) {
        // Honour the reconnect backoff: while the sensor is cooling down after a failed
        // attempt, ignore scan results for it so we don't hot-loop into the same failure.
        if (!reconnect.canAttempt(address)) return
        val device = devices[address] ?: return
        val conn = SensorConnection(address)
        // Claim the slot atomically so a concurrent scan callback + paired-flow emission
        // can't both open a GATT client (which would leak one).
        if (!connection.compareAndSet(null, conn)) return
        Log.i(TAG, "connecting to $address")
        val gatt = device.connectGatt(context, false, conn)
        if (gatt == null) {
            // connectGatt can return null (BT briefly unavailable, GATT client limit,
            // stale handle). Don't leave a zombie that blocks every reconnect — drop it
            // and arm a backoff so the next scan result retries with a delay.
            Log.w(TAG, "connectGatt returned null for $address; backing off")
            connection.compareAndSet(conn, null)
            val delay = reconnect.recordFailure(address)
            Log.w(TAG, "connect to $address abandoned; retrying in ${delay}ms")
            return
        }
        conn.gatt = gatt
        // Ownership: the slot was claimed (CAS null->conn) BEFORE connectGatt, but a teardown
        // running on another thread (stop/onAdapterOff/paired-change) can null or replace the
        // slot in the window before this line publishes conn.gatt — it would then find
        // conn.gatt == null and close nothing, leaking this live GATT client (one of Android's
        // few per-app slots). Re-check ownership and close it ourselves if we lost the race.
        if (connection.get() !== conn) gatt.close()
    }

    /** Drops [conn] if it is still the live connection (e.g. the user picked another sensor). */
    private fun teardown(conn: SensorConnection) {
        if (connection.compareAndSet(conn, null)) {
            conn.gatt?.close()
            markConnected(conn.address, false)
        }
    }

    private fun markConnected(address: String, connected: Boolean) {
        seen[address]?.let { seen[address] = it.copy(connected = connected) }
        publishDiscovered()
        updateConnectionState()
    }

    private fun publishDiscovered() {
        _discovered.value = seen.values
            .map { it.copy(paired = it.address == pairedAddress) }
            .sortedWith(compareByDescending<DiscoveredSensor> { it.connected }.thenByDescending { it.rssi })
    }

    private fun updateConnectionState() {
        _connectionState.value = when {
            connection.get()?.ready == true -> ConnectionState.CONNECTED
            scanning -> ConnectionState.SCANNING
            else -> ConnectionState.DISCONNECTED
        }
    }

    override fun seedOdometer(meters: Double) {
        synchronized(stateLock) {
            odometerSeedM = meters
            recomputeOdometerLocked()
        }
        emitData()
    }

    private fun emitData() {
        // CAS read-modify-write (not a plain .value = ...) so this binder-thread/seed writer
        // can't clobber a partial update the heading ticker or stale-speed watcher just
        // committed. Reading the @Volatile speed/odometer fields inside the lambda also means
        // a CAS retry under contention re-reads their freshest values. This keeps every _data
        // writer on the same atomic path — see the invariant documented in live-heading.md.
        val h = heading()
        val t = trueFromMagnetic(h, declination())
        _data.update {
            it.copy(
                speedKph = speedKph,
                bearingDegrees = h,
                trueBearingDegrees = t,
                odometerKm = odometerM / 1000.0,
            )
        }
    }

    /** Zeroes speed when no revolutions arrive (the bike has stopped). */
    private fun startStaleWatcher() {
        staleJob?.cancel()
        staleJob = scope.launch {
            while (true) {
                delay(1_000)
                val zeroed = synchronized(stateLock) {
                    val idle = SystemClock.elapsedRealtime() - lastActivityAt
                    if (lastActivityAt > 0 && idle > STALE_MS && speedKph != 0.0) {
                        speedKph = 0.0
                        true
                    } else false
                }
                if (zeroed) _data.update { it.copy(speedKph = 0.0) }
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
                // skips the emission (StateFlow dedups equal values). headingSettled
                // forces an emit across any NaN<->real transition (its NaN behavior is
                // pinned in HeadingTest), preserving the "unknown heading" invariant.
                _data.update {
                    if (headingSettled(it.bearingDegrees, h, it.trueBearingDegrees, t, HEADING_EPSILON_DEG)) it
                    else it.copy(bearingDegrees = h, trueBearingDegrees = t)
                }
                delay(250)
            }
        }
    }

    /** The BLE connection to the chosen CSC sensor, with its own decode state. */
    private inner class SensorConnection(val address: String) : BluetoothGattCallback() {

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
                    val wasReady = ready
                    g.close()
                    ready = false
                    // Only clear the slot if it is still THIS connection: after a fast flap
                    // a newer SensorConnection may already own it, and a late callback for
                    // the old GATT must not evict the live one.
                    if (connection.compareAndSet(this, null)) {
                        // Continuous scanning will rediscover and reconnect if still paired.
                        markConnected(address, false)
                        // A drop before we ever subscribed is a flap (e.g. status 133): back
                        // off so the scan doesn't immediately retry into the same failure. A
                        // drop after a healthy subscription gets no penalty — reconnect fast.
                        reconnect.onDisconnect(address, wasSubscribed = wasReady)
                    }
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Subscription failed: notifications will never arrive, so don't report a
                // false "CONNECTED". Disconnect to drop into the rescan/reconnect path.
                Log.e(TAG, "CCCD write failed for $address (status=$status); disconnecting")
                g.disconnect()
                return
            }
            ready = true
            reconnect.recordSuccess(address)
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
            val result = decoder.decode(bytes, circumference, SystemClock.elapsedRealtime())

            // Persist every wheel-flagged packet losslessly for the time-series. In practice
            // the firmware notifies once per real edge, so deltaRevolutions is normally > 0;
            // the rare zero-delta row (first packet, or a coasting packet where the count
            // didn't advance) is kept on purpose — it records sensor liveness / a timestamp
            // even while stopped, and the DAO's SUM(deltaRevolutions) distance math ignores it.
            result.wheelCumulativeRevs?.let { revs ->
                val h = heading()
                _readings.trySend(
                    WheelRevolutionReading(
                        timestampMillis = System.currentTimeMillis(),
                        cumulativeRevolutions = revs,
                        deltaRevolutions = result.wheelDeltaRevs,
                        sensorEventTime1024 = result.wheelEventTime1024 ?: 0,
                        wheelCircumferenceM = circumference,
                        headingDegrees = h,
                        trueHeadingDegrees = trueFromMagnetic(h, declination()),
                    )
                )
            }

            var changed = false
            synchronized(stateLock) {
                // Distance comes from the (reliable) cumulative count, so count it even
                // when speed isn't derivable (event-time delta 0, or a >64 s wrap).
                if (result.distanceMeters > 0.0) {
                    sensorDistanceM += result.distanceMeters
                    recomputeOdometerLocked()
                    lastActivityAt = SystemClock.elapsedRealtime()
                    changed = true
                }
                result.speedKph?.let {
                    speedKph = it
                    lastActivityAt = SystemClock.elapsedRealtime()
                    changed = true
                }
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
