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
import android.bluetooth.BluetoothStatusCodes
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
import androidx.annotation.VisibleForTesting
import com.roundearth.bikecomputer.data.diagnostics.Diag
import com.roundearth.bikecomputer.data.diagnostics.LogBus
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
    /** Firmware revision read from the Device Information Service once connected, or null. */
    val firmwareRevision: String? = null,
    /**
     * Tri-state wheel-revolution support from the CSC Feature characteristic:
     * `null` = unknown (not yet read, read failed, or old firmware without the characteristic),
     * `false` = explicitly unsupported (can't drive speed/distance — wrong kind of sensor),
     * `true` = confirmed supported. The picker warns only on an explicit `false`.
     */
    val wheelSupported: Boolean? = null,
)

/**
 * A drained per-minute backlog from the sensor: the records streamed over the Backlog Data
 * characteristic, plus the [BacklogRecordParser.Info] block and the wall-clock captured at the moment
 * the Info block was read (the anchor used to back-compute each record's wall-clock).
 */
data class BacklogBatch(
    val sensorMac: String,
    val anchorWallClockMs: Long,
    val info: BacklogRecordParser.Info,
    val records: List<BacklogRecordParser.Record>,
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
    /** Invoked once per connection after the sensor's per-minute backlog has been drained. */
    private val onBacklogBatch: (BacklogBatch) -> Unit = {},
) : BikeDataSource {

    // Process-lifetime scope by design (this is an Application-scoped singleton); it is
    // never cancelled. Because of that, stop() must cancel EVERY launched job individually
    // — currently staleJob, headingJob, pairedJob, scanRetryJob. Add any new job to that list in stop().
    private val scope = CoroutineScope(SupervisorJob())
    private var staleJob: Job? = null
    private var headingJob: Job? = null
    private var pairedJob: Job? = null
    // Bounded retry after a scan failure. onScanFailed used to go silent, which (with start()'s
    // wantRunning idempotency guard) left scanning dead for the whole process unless the user
    // toggled Bluetooth. scanFailures drives an escalating backoff; both reset on a clean scan start.
    private var scanRetryJob: Job? = null
    private var scanFailures = 0

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
    @VisibleForTesting
    internal val reconnect = ReconnectPolicy { SystemClock.elapsedRealtime() }

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
        if (wantRunning) return // idempotent: a sticky service restart may re-enter while already live
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
            Diag.w(TAG, "Bluetooth disabled; waiting for it to turn on")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        startScan()
    }

    override fun stop() {
        wantRunning = false
        unregisterBtStateReceiver()
        stopScan()
        disconnectAndClose(connection.getAndSet(null)?.gatt)
        reconnect.clear()
        staleJob?.cancel(); staleJob = null
        headingJob?.cancel(); headingJob = null
        pairedJob?.cancel(); pairedJob = null
        scanRetryJob?.cancel(); scanRetryJob = null
        scanFailures = 0
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // --- Bluetooth adapter on/off recovery ---

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Diag.i(TAG, "Bluetooth turned on")
                    if (wantRunning && !scanning) {
                        // Fresh start: a deliberate toggle shouldn't inherit stale backoff.
                        reconnect.clear()
                        startScan()
                    }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Diag.w(TAG, "Bluetooth turning off; tearing down BLE")
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
        // The adapter is down; STATE_ON revives scanning fresh, so drop any pending retry and its
        // backoff rather than firing startScan() against an invalid scanner during the off window.
        scanRetryJob?.cancel(); scanRetryJob = null
        scanFailures = 0
        disconnectAndClose(connection.getAndSet(null)?.gatt)
        reconnect.clear()
        seen.keys.forEach { addr -> seen.computeIfPresent(addr) { _, s -> s.copy(connected = false) } }
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
            Diag.e(TAG, "scan blocked: missing permission", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        scanning = true
        // A clean start clears any scan-failure backoff and pending retry.
        scanFailures = 0
        scanRetryJob?.cancel(); scanRetryJob = null
        updateConnectionState()
        Diag.i(TAG, "scanning for CSC sensors")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    /**
     * Reschedules [startScan] after [ScanCallback.onScanFailed], with an escalating backoff. Without
     * this a scan failure leaves scanning dead for the process lifetime: [start] is idempotent on
     * [wantRunning] (so a sticky service restart can't revive it) and the only other startScan callers
     * are this method and the Bluetooth-on receiver — so the sole recovery would be a BT toggle.
     * Re-checks liveness when the delay fires; a clean [startScan] resets the backoff.
     */
    private fun scheduleScanRetry() {
        if (!wantRunning) return
        scanRetryJob?.cancel()
        val attempt = ++scanFailures
        val delayMs = (BASE_SCAN_RETRY_MS shl (attempt - 1).coerceAtMost(MAX_SCAN_RETRY_SHIFT))
            .coerceAtMost(MAX_SCAN_RETRY_MS)
        Diag.w(TAG, "scan retry #$attempt in ${delayMs}ms")
        scanRetryJob = scope.launch {
            delay(delayMs)
            // Conditions may have changed during the backoff (stop, a connection opened, adapter off).
            if (wantRunning && !scanning && adapter?.isEnabled == true) startScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            // scanRecord.deviceName comes from the advertisement (no CONNECT needed).
            handleScan(device, result.scanRecord?.deviceName ?: device.address, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Diag.e(TAG, "scan failed: $errorCode")
            scanning = false
            updateConnectionState()
            // Don't go silent: reschedule with a bounded backoff so a transient scan failure
            // self-heals without a Bluetooth toggle or a full collection restart.
            scheduleScanRetry()
        }
    }

    /** Records a sighting and connects if it's the paired sensor. Shared by the real scan
     *  callback and the test hook so both take the exact same path into [connectIfNeeded]. */
    private fun handleScan(device: BluetoothDevice, name: String, rssi: Int) {
        val address = device.address
        devices[address] = device
        // compute() folds the read-of-prior-entry into the atomic mutation, so a concurrent
        // setFirmwareRevision (on the GATT binder thread) can't be lost: re-seeing the
        // advertisement preserves a firmware revision already read this session.
        val connected = isConnected(address)
        seen.compute(address) { _, prev -> mergeScanResult(prev, address, name, rssi, connected) }
        publishDiscovered()
        if (address == pairedAddress) connectIfNeeded(address)
    }

    // --- Test seams (Approach C, docs/testing-plan.md §2a). The BLE state machine is
    // otherwise unreachable from a JVM test: scanning and pairing happen on the internal
    // scope behind the system scanner. These let a test feed a sighting, set the paired
    // address, and read the single-connection slot — without a real radio. ---

    /** Feed a scan sighting (bypassing the system scanner) with a Mockito-mocked device. */
    @VisibleForTesting
    internal fun onScanResultForTest(device: BluetoothDevice, name: String, rssi: Int) =
        handleScan(device, name, rssi)

    /** Drive a scanner failure (the system scanner's onScanFailed) to exercise the retry path. */
    @VisibleForTesting
    internal fun onScanFailedForTest(errorCode: Int) = scanCallback.onScanFailed(errorCode)

    /** Set the paired address directly (the production path collects it on the internal scope). */
    @VisibleForTesting
    internal fun pairForTest(address: String?) { pairedAddress = address }

    /** The live connection's GATT callback (the captured [SensorConnection]), or null. Upcast
     *  so the private inner type stays private. */
    @VisibleForTesting
    internal fun connectionCallbackForTest(): BluetoothGattCallback? = connection.get()

    private fun connectIfNeeded(address: String) {
        // Honour the reconnect backoff: while the sensor is cooling down after a failed
        // attempt, ignore scan results for it so we don't hot-loop into the same failure.
        if (!reconnect.canAttempt(address)) return
        val device = devices[address] ?: return
        val conn = SensorConnection(address)
        // Claim the slot atomically so a concurrent scan callback + paired-flow emission
        // can't both open a GATT client (which would leak one).
        if (!connection.compareAndSet(null, conn)) return
        Diag.i(TAG, "connecting to $address")
        val gatt = device.connectGatt(context, false, conn)
        if (gatt == null) {
            // connectGatt can return null (BT briefly unavailable, GATT client limit,
            // stale handle). Don't leave a zombie that blocks every reconnect — drop it
            // and arm a backoff so the next scan result retries with a delay.
            Diag.w(TAG, "connectGatt returned null for $address; backing off")
            connection.compareAndSet(conn, null)
            val delay = reconnect.recordFailure(address)
            Diag.w(TAG, "connect to $address abandoned; retrying in ${delay}ms")
            return
        }
        conn.gatt = gatt
        // Ownership: the slot was claimed (CAS null->conn) BEFORE connectGatt, but a teardown
        // running on another thread (stop/onAdapterOff/paired-change) can null or replace the
        // slot in the window before this line publishes conn.gatt — it would then find
        // conn.gatt == null and close nothing, leaking this live GATT client (one of Android's
        // few per-app slots). Re-check ownership and close it ourselves if we lost the race.
        if (connection.get() !== conn) disconnectAndClose(gatt)
    }

    /** Drops [conn] if it is still the live connection (e.g. the user picked another sensor). */
    private fun teardown(conn: SensorConnection) {
        if (connection.compareAndSet(conn, null)) {
            disconnectAndClose(conn.gatt)
            markConnected(conn.address, false)
        }
    }

    /**
     * Tears down an APP-INITIATED GATT link (stop/onAdapterOff/teardown/lost-race), as opposed to
     * an unsolicited drop. Android's [BluetoothGatt.close] only releases the local client handle —
     * it does NOT terminate the radio link, so a bare close() strands the peer "connected" to
     * nobody: the sensor keeps its ACL up (firmware reports conn=1, disc=0) until its own
     * supervision timeout fires. The next collection start then opens a fresh client and re-attaches
     * over that still-live link — the firmware logs no new connect/disconnect — and the orphaned link
     * eventually dies with a status=8 GATT_CONN_TIMEOUT. disconnect() first asks the stack to drop
     * the ACL so the sensor sees a clean disconnect and can re-advertise; close() then frees the
     * client. (The unsolicited-drop path in onConnectionStateChange runs AFTER the link is already
     * down, so it still closes directly — there is nothing left to disconnect.)
     */
    private fun disconnectAndClose(gatt: BluetoothGatt?) {
        gatt ?: return
        gatt.disconnect()
        gatt.close()
    }

    private fun markConnected(address: String, connected: Boolean) {
        seen.computeIfPresent(address) { _, s -> s.copy(connected = connected) }
        publishDiscovered()
        updateConnectionState()
    }

    /** Records whether a sensor's CSC Feature characteristic advertises wheel-rev support. */
    private fun setWheelSupported(address: String, bytes: ByteArray?) {
        val supported = parseWheelSupported(bytes) ?: return
        if (!supported) {
            Diag.w(TAG, "sensor $address does not advertise CSC wheel-revolution support")
        }
        // computeIfPresent + mergeScanResult's carry-forward keep this atomic and safe against
        // the scan-callback rebuild, exactly as for firmware revision — see setFirmwareRevision.
        seen.computeIfPresent(address) { _, s -> s.copy(wheelSupported = supported) }
        publishDiscovered()
    }

    /** Records the firmware revision read from a sensor's Device Information Service. */
    private fun setFirmwareRevision(address: String, bytes: ByteArray?) {
        val rev = parseFirmwareRevision(bytes) ?: return
        // computeIfPresent keeps this read-modify-write atomic per key. The scan callback
        // rebuilds seen[address] via compute() (also atomic) and carries the prior firmware
        // revision forward, so neither writer can drop the value the other just set.
        // seen[] entries are never removed today, so the key is effectively always
        // present; computeIfPresent is defensive — it would simply no-op if one ever were.
        // (A failed re-read after a reflash leaves the previously read value in place, on
        // purpose — see parseFirmwareRevision returning null and this returning early.)
        seen.computeIfPresent(address) { _, s -> s.copy(firmwareRevision = rev) }
        publishDiscovered()
        Diag.i(TAG, "firmware revision for $address: $rev")
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

        // Best-effort firmware debug-log channel (NUS). Because only one GATT op may be in
        // flight, the NUS CCCD write is chained AFTER the CSC one; this flag routes its
        // completion in onDescriptorWrite without inspecting the descriptor (which the test
        // suite drives with a throwaway mock). fwLogBuffer reassembles the chunked, '\n'-
        // delimited byte stream into whole lines for the LogBus.
        private var awaitingNusCccd = false
        private val fwLogBuffer = StringBuilder()

        // Backlog drain state. The Info read (the 5th, last step of the connect chain) captures the
        // clock anchor; the Data CCCD write then makes the firmware stream the ring; records arrive as
        // notifications and accumulate until the terminator, when the whole batch is handed off.
        private var awaitingBacklogCccd = false
        private var backlogAnchorMs = 0L
        private var backlogInfo: BacklogRecordParser.Info? = null
        private val backlogRecords = ArrayList<BacklogRecordParser.Record>()

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Diag.i(TAG, "connected $address, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Diag.w(TAG, "disconnected $address (status=$status)")
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
            // A failed discovery can leave an empty/partial service table; don't subscribe over it.
            // Drop the half-open link so the DISCONNECTED handler below frees the slot and arms a
            // backoff — otherwise the slot stays occupied (ready=false) forever and the app, whose
            // only reconnect trigger is a fresh scan sighting that short-circuits on the non-null
            // slot (connectIfNeeded's compareAndSet), never reconnects. Mirrors the CCCD-write-fail
            // path in onDescriptorWrite, which already disconnects for exactly this reason.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Diag.e(TAG, "service discovery failed for $address (status=$status); disconnecting")
                g.disconnect()
                return
            }
            val measurement = g.getService(CSC_SERVICE_UUID)?.getCharacteristic(CSC_MEASUREMENT_UUID)
            if (measurement == null) {
                // No usable CSC measurement characteristic (partial GATT cache, sensor mid-reflash):
                // disconnect so scanning can reconnect, rather than wedging the slot forever.
                Diag.e(TAG, "CSC measurement characteristic not found on $address; disconnecting")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(measurement, true)
            val cccd = measurement.getDescriptor(CCCD_UUID) ?: run {
                // Measurement present but no CCCD to enable notifications: same wedge risk, same fix.
                Diag.e(TAG, "CCCD descriptor missing on $address; disconnecting")
                g.disconnect()
                return
            }
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
            // Last write in the chain: the optional Backlog Data CCCD. Subscribing makes the firmware
            // stream its ring as notifications (handled in handleBacklogRecord); nothing else chains
            // after, so just log the outcome. Best-effort — older firmware has no backlog service.
            if (awaitingBacklogCccd) {
                awaitingBacklogCccd = false
                if (status == BluetoothGatt.GATT_SUCCESS) Diag.i(TAG, "subscribed to backlog on $address")
                else Diag.w(TAG, "backlog CCCD write failed for $address (status=$status); no backlog this connection")
                return
            }
            // Second write in the chain: the optional NUS log-channel CCCD. It is best-effort —
            // whatever its outcome, proceed to the optional reads. (Routed by flag, not by the
            // descriptor argument, which the connection tests drive with a throwaway mock.)
            if (awaitingNusCccd) {
                awaitingNusCccd = false
                if (status == BluetoothGatt.GATT_SUCCESS) Diag.i(TAG, "subscribed to firmware logs on $address")
                else Diag.w(TAG, "firmware-log CCCD write failed for $address (status=$status); logs unavailable")
                startInfoReads(g)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Subscription failed: notifications will never arrive, so don't report a
                // false "CONNECTED". Disconnect to drop into the rescan/reconnect path.
                Diag.e(TAG, "CCCD write failed for $address (status=$status); disconnecting")
                g.disconnect()
                return
            }
            ready = true
            reconnect.recordSuccess(address)
            markConnected(address, true)
            Diag.i(TAG, "subscribed to $address")

            // Best-effort: also subscribe to the firmware's NUS debug-log channel. If it's
            // present, the next onDescriptorWrite (awaitingNusCccd) chains into the reads;
            // otherwise start them now. CONNECTED is already reported above, so a sensor with
            // no log channel (older firmware) is unaffected.
            if (subscribeLogChannel(g)) {
                awaitingNusCccd = true
                return
            }
            startInfoReads(g)
        }

        /**
         * Read two optional characteristics — one GATT read may be in flight at a time, so they
         * CHAIN in onRead(): first the CSC Feature (confirm wheel-rev support), then the Device
         * Information firmware revision. If the feature read can't start, skip to the firmware read.
         */
        private fun startInfoReads(g: BluetoothGatt) {
            if (!startRead(g, CSC_SERVICE_UUID, CSC_FEATURE_UUID)) readFirmwareRevision(g)
        }

        /**
         * Subscribes to the firmware's NUS TX log characteristic if the sensor exposes it.
         * Returns true only when the CCCD write actually initiated (so the caller knows to await
         * its callback); false — absent service/char/descriptor or a write that didn't start —
         * means the caller should proceed straight to the optional reads.
         */
        private fun subscribeLogChannel(g: BluetoothGatt): Boolean {
            val logCh = g.getService(NUS_SERVICE_UUID)?.getCharacteristic(NUS_TX_UUID) ?: return false
            g.setCharacteristicNotification(logCh, true)
            val cccd = logCh.getDescriptor(CCCD_UUID) ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        /** Reads the optional DIS firmware revision; if absent, chains straight to the backlog read. */
        private fun readFirmwareRevision(g: BluetoothGatt) {
            if (!startRead(g, DIS_SERVICE_UUID, FIRMWARE_REVISION_UUID)) {
                Diag.w(TAG, "no firmware-revision characteristic to read on $address")
                readBacklogInfo(g)
            }
        }

        /**
         * Reads the optional Backlog Info block (last step of the connect chain). Capturing the
         * wall-clock here pairs it with the Info block's currentUptimeSeconds as the single anchor
         * for back-computing every record's wall-clock. If the sensor has no backlog service (older
         * firmware), the read doesn't start and the chain simply ends.
         */
        private fun readBacklogInfo(g: BluetoothGatt) {
            if (!startRead(g, BACKLOG_SERVICE_UUID, BACKLOG_INFO_UUID)) {
                Diag.i(TAG, "no backlog service on $address (older firmware)")
            }
        }

        /** Subscribes to the Backlog Data characteristic so the firmware streams its ring. */
        private fun subscribeBacklogData(g: BluetoothGatt): Boolean {
            val dataCh = g.getService(BACKLOG_SERVICE_UUID)?.getCharacteristic(BACKLOG_DATA_UUID) ?: return false
            g.setCharacteristicNotification(dataCh, true)
            val cccd = dataCh.getDescriptor(CCCD_UUID) ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        /** Starts a characteristic read; false if it's absent or the read didn't start. */
        private fun startRead(g: BluetoothGatt, service: UUID, characteristic: UUID): Boolean {
            val ch = g.getService(service)?.getCharacteristic(characteristic) ?: return false
            return g.readCharacteristic(ch)
        }

        @Deprecated("Deprecated in API 33; the ByteArray overload is used there")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            onRead(g, c.uuid, c.value, status)
        }

        // API 33+ overload.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = onRead(g, c.uuid, value, status)

        /**
         * Dispatches a completed read and chains the next one. Intentionally NOT
         * ownership-gated (no compareAndSet(this) check): the setters are keyed on address
         * and use computeIfPresent, so a late/stale read after a fast flap only ever updates
         * the correct row by address — it can never corrupt another connection's entry.
         */
        private fun onRead(g: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
            when (uuid) {
                CSC_FEATURE_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) setWheelSupported(address, value)
                    else Diag.w(TAG, "CSC feature read for $address failed: status=$status")
                    readFirmwareRevision(g) // chain regardless of the feature outcome
                }
                FIRMWARE_REVISION_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) setFirmwareRevision(address, value)
                    // A read that started then failed (vs. old firmware with no DIS, which
                    // never starts a read) leaves firmwareRevision null; log so the two cases
                    // are distinguishable. It refills on the next reconnect, so no retry.
                    else Diag.w(TAG, "firmware-revision read for $address failed: status=$status")
                    readBacklogInfo(g) // chain regardless of the firmware-revision outcome
                }
                BACKLOG_INFO_UUID -> {
                    // Capture the anchor (wall-clock now ↔ info.currentUptimeSeconds) at THIS instant,
                    // then subscribe so the firmware streams the ring. Derive each record's wall-clock
                    // once from this snapshot (survives a later NTP jump). End of chain either way.
                    val info = if (status == BluetoothGatt.GATT_SUCCESS) BacklogRecordParser.parseInfo(value ?: ByteArray(0)) else null
                    if (info != null) {
                        backlogInfo = info
                        backlogAnchorMs = System.currentTimeMillis()
                        backlogRecords.clear()
                        if (subscribeBacklogData(g)) awaitingBacklogCccd = true
                        else Diag.w(TAG, "backlog info read but Data subscribe didn't start on $address")
                    } else {
                        Diag.w(TAG, "backlog info read for $address failed/short: status=$status")
                    }
                }
                // The reads above are the only ones issued. An unexpected UUID here means a future
                // read was added without a matching arm to kick the next step. The log surfaces the
                // mistake; the chain still stops here, so the new read must add its own arm.
                else -> Diag.w(TAG, "unexpected characteristic read for $address: $uuid")
            }
        }

        @Deprecated("Deprecated in API 33; the ByteArray overload is used there")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            when (c.uuid) {
                CSC_MEASUREMENT_UUID -> handleMeasurement(c.value)
                NUS_TX_UUID -> handleLogBytes(c.value)
                BACKLOG_DATA_UUID -> handleBacklogRecord(c.value)
            }
        }

        // API 33+ overload.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                CSC_MEASUREMENT_UUID -> handleMeasurement(value)
                NUS_TX_UUID -> handleLogBytes(value)
                BACKLOG_DATA_UUID -> handleBacklogRecord(value)
            }
        }

        /**
         * Accumulates streamed backlog records until the terminator, then hands the whole batch (with
         * the connect-time anchor + Info block) to [onBacklogBatch] for idempotent persistence. Dropped
         * for a torn-down/replaced connection (same identity gate as handleMeasurement).
         */
        private fun handleBacklogRecord(bytes: ByteArray?) {
            if (bytes == null || connection.get() !== this) return
            if (BacklogRecordParser.isTerminator(bytes)) {
                val info = backlogInfo
                // Always clear, even with no Info (a stray stream): never leave records to accumulate.
                val records = backlogRecords.toList()
                backlogRecords.clear()
                if (info != null) onBacklogBatch(BacklogBatch(address, backlogAnchorMs, info, records))
                return
            }
            // Bound the buffer so a firmware that never sends a terminator can't grow it without limit
            // (mirrors fwLogBuffer's cap). The firmware ring is small; this is purely a safety ceiling.
            if (backlogRecords.size >= MAX_BACKLOG_RECORDS) return
            BacklogRecordParser.parseRecord(bytes)?.let { backlogRecords.add(it) }
        }

        /**
         * Reassembles the firmware's chunked, '\n'-delimited NUS log stream into whole lines and
         * pushes each into [LogBus] tagged [LogBus.Source.FW]. Dropped for a torn-down/replaced
         * connection (mirrors handleMeasurement's identity gate). The buffer is bounded so a
         * stream that never sends a newline can't grow without limit.
         */
        private fun handleLogBytes(bytes: ByteArray?) {
            if (bytes == null || bytes.isEmpty() || connection.get() !== this) return
            synchronized(fwLogBuffer) {
                fwLogBuffer.append(String(bytes, Charsets.UTF_8))
                var nl = fwLogBuffer.indexOf("\n")
                while (nl >= 0) {
                    val line = fwLogBuffer.substring(0, nl).trimEnd('\r')
                    fwLogBuffer.delete(0, nl + 1)
                    if (line.isNotEmpty()) LogBus.add(LogBus.Source.FW, LogBus.Level.I, "FW", line)
                    nl = fwLogBuffer.indexOf("\n")
                }
                if (fwLogBuffer.length > 512) fwLogBuffer.delete(0, fwLogBuffer.length - 512)
            }
        }

        private fun handleMeasurement(bytes: ByteArray?) {
            if (bytes == null || bytes.isEmpty()) return
            // Evict-only-if-still-mine: ignore a late in-flight notification for an old GATT
            // after this connection was torn down/replaced. The new cumulativeEventTime1024
            // accumulator resets to 0 per connection, so persisting a stale row (with this
            // decoder's high accumulated value) after a fresh segment's low values would
            // interleave the monotonic series and corrupt the offline "non-increasing step =
            // segment boundary" heuristic. Dropping the stale row is strictly safer.
            if (!ready || connection.get() !== this) return
            val circumference = wheelCircumferenceM()
            val result = decoder.decode(bytes, circumference, SystemClock.elapsedRealtime())

            // Persist every wheel-flagged packet losslessly for the time-series. The firmware
            // notifies strictly once per real edge with no keepalive packets, so deltaRevolutions
            // is normally > 0; a zero-delta row is only the first packet of a connection or a
            // sensor reboot. It is kept on purpose — it records the segment baseline / reboot
            // timestamp — and the DAO's SUM(deltaRevolutions) distance math ignores it.
            result.wheelCumulativeRevs?.let { revs ->
                val h = heading()
                _readings.trySend(
                    WheelRevolutionReading(
                        timestampMillis = System.currentTimeMillis(),
                        cumulativeRevolutions = revs,
                        deltaRevolutions = result.wheelDeltaRevs,
                        sensorEventTime1024 = result.wheelEventTime1024 ?: 0,
                        cumulativeEventTime1024 = result.cumulativeEventTime1024,
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
        // Scan-failure retry backoff: 1s, 2s, 4s, ... capped at 30s, reset on a clean scan start.
        private const val BASE_SCAN_RETRY_MS = 1_000L
        private const val MAX_SCAN_RETRY_MS = 30_000L
        private const val MAX_SCAN_RETRY_SHIFT = 5
        // Safety ceiling on a single connection's backlog buffer (the firmware ring is far smaller);
        // guards against a misbehaving stream that never terminates.
        private const val MAX_BACKLOG_RECORDS = 4_096
        // Minimum heading change (degrees) that warrants a live update.
        private const val HEADING_EPSILON_DEG = 1f

        private val CSC_SERVICE_UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        private val CSC_MEASUREMENT_UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        private val CSC_FEATURE_UUID = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Nordic UART Service — the firmware's debug-log channel (see speed.ino). We only
        // subscribe to the TX (notify) characteristic; the RX direction is unused.
        private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        // Backlog service (custom; see speed.ino). Info (READ) = clock anchor + ring range; Data
        // (NOTIFY) streams the per-minute ring on subscribe. Not advertised — found by discovery.
        private val BACKLOG_SERVICE_UUID = UUID.fromString("5245424C-0000-1000-8000-00805f9b34fb")
        private val BACKLOG_INFO_UUID = UUID.fromString("52454201-0000-1000-8000-00805f9b34fb")
        private val BACKLOG_DATA_UUID = UUID.fromString("52454202-0000-1000-8000-00805f9b34fb")

        /**
         * Whether a CSC Feature value (16-bit little-endian, characteristic 0x2A5C) advertises
         * wheel-revolution support (bit 0), or null if the value is missing/too short. The app
         * only consumes wheel data, so a sensor without this bit can't drive speed/distance.
         */
        internal fun parseWheelSupported(bytes: ByteArray?): Boolean? {
            if (bytes == null || bytes.size < 2) return null
            val feature = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            return (feature and 0x0001) != 0
        }

        // Device Information Service (read once after connecting, optional on the sensor).
        private val DIS_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REVISION_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

        /**
         * Decodes a Device Information Service Firmware Revision value into a display
         * string, or null if there is nothing usable to show.
         *
         * DIS strings are UTF-8 and may be NUL-padded; we trim padding and whitespace.
         * Our firmware uses strlen() so it never NUL-pads, but this stays defensive
         * against other DIS implementations. The '\u0000' is an explicit escape so it
         * stays visible in diffs/editors rather than being a raw control byte.
         */
        internal fun parseFirmwareRevision(bytes: ByteArray?): String? {
            if (bytes == null || bytes.isEmpty()) return null
            val rev = String(bytes, Charsets.UTF_8).trim('\u0000', ' ', '\t', '\n', '\r')
            return rev.ifEmpty { null }
        }

        /**
         * Builds the [DiscoveredSensor] entry for a fresh scan result, carrying the prior
         * entry's [DiscoveredSensor.firmwareRevision] forward. Run inside seen.compute() so
         * the read-of-prior + rebuild is atomic per key: a concurrent firmware read (which
         * uses computeIfPresent on the same key) can never be clobbered back to null.
         *
         * `paired` is always a placeholder here — publishDiscovered() is the single authority
         * and recomputes it on every emission.
         */
        internal fun mergeScanResult(
            prev: DiscoveredSensor?,
            address: String,
            name: String,
            rssi: Int,
            connected: Boolean,
        ): DiscoveredSensor = DiscoveredSensor(
            address = address,
            name = name,
            rssi = rssi,
            paired = false,
            connected = connected,
            firmwareRevision = prev?.firmwareRevision,
            wheelSupported = prev?.wheelSupported,
        )
    }
}
