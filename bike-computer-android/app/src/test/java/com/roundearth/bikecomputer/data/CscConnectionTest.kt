package com.roundearth.bikecomputer.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tier-2 BLE connection-state-machine tests (Approach C, docs/testing-plan.md §2/§4).
 *
 * The production code talks to the final android.bluetooth.* classes directly, so a JVM
 * test mocks them with Mockito 5's inline mock-maker (under a Robolectric runtime), feeds
 * a sighting via the [CscBleDataSource.onScanResultForTest] seam, captures the
 * [BluetoothGattCallback] (the private SensorConnection) handed to connectGatt, and drives
 * its callbacks directly — no real radio.
 *
 * This first test IS the feasibility spike: it proves the trifecta (Robolectric runner +
 * inline mock-maker on a final BluetoothDevice + ArgumentCaptor on the callback). The other
 * Tier-2 paths fan out only once this is green.
 */
@RunWith(RobolectricTestRunner::class)
// SDK is pinned project-wide in robolectric.properties (sdk=34), NOT a per-class @Config, so the
// single android-all jar stays shared. sdk>=33 means happyPath exercises the production TIRAMISU+
// writeDescriptor(descriptor, value) overload it now verifies.
class CscConnectionTest {

    private val address = "AA:BB:CC:DD:EE:01"

    private lateinit var source: CscBleDataSource
    private lateinit var device: BluetoothDevice
    private lateinit var gatt: BluetoothGatt

    @Before
    fun setUp() {
        source = CscBleDataSource(
            context = ApplicationProvider.getApplicationContext(),
            wheelCircumferenceM = { 2.0 },
        )
        source.pairForTest(address)
        device = mock()
        whenever(device.address).thenReturn(address)
        gatt = mock()
        whenever(device.connectGatt(anyOrNull(), eq(false), any())).thenReturn(gatt)
    }

    @After
    fun tearDown() = source.stop() // cancels staleJob/headingJob/pairedJob (none here, but hygiene)

    /**
     * Stub gatt so onServicesDiscovered finds the CSC measurement characteristic + CCCD, and
     * return the CCCD mock so a test can both verify(gatt).writeDescriptor(cccd, ...) and drive
     * onDescriptorWrite with the SAME descriptor production was handed (not a fresh mock).
     */
    private fun stubCscService(): BluetoothGattDescriptor {
        val service: BluetoothGattService = mock()
        val measurement: BluetoothGattCharacteristic = mock()
        val cccd: BluetoothGattDescriptor = mock()
        whenever(gatt.getService(CSC_SERVICE_UUID)).thenReturn(service)
        whenever(service.getCharacteristic(CSC_MEASUREMENT_UUID)).thenReturn(measurement)
        whenever(measurement.getDescriptor(CCCD_UUID)).thenReturn(cccd)
        // Feature/DIS reads are left unstubbed (getCharacteristic returns null) so the chained
        // reads no-op gracefully — parser logic is covered elsewhere.
        return cccd
    }

    /** Capture the BluetoothGattCallback (the SensorConnection) that connectGatt was given. */
    private fun captureCallback(): BluetoothGattCallback {
        val captor = argumentCaptor<BluetoothGattCallback>()
        verify(device).connectGatt(anyOrNull(), eq(false), captor.capture())
        return captor.firstValue
    }

    @Test
    fun happyPath_connectedOnlyAfterCccdConfirms() {
        // A sighting for the paired sensor opens a GATT client; capture its callback.
        source.onScanResultForTest(device, name = "Bike Speed", rssi = -50)
        val cb = captureCallback()

        // Connect + discover services + subscribe (CCCD write), but NOT yet confirmed.
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        val cccd = stubCscService()
        cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        // Anti-vacuity: prove PRODUCTION actually issued the API-34 CCCD write (sdk=34 exercises the
        // writeDescriptor(descriptor, value) overload). Without this the hand-driven onDescriptorWrite
        // below would flip CONNECTED even if onServicesDiscovered never subscribed.
        verify(gatt).writeDescriptor(eq(cccd), eq(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))

        // Discriminating negative checkpoint: subscribed-but-not-confirmed must NOT be CONNECTED
        // (a broken `ready` gate that flipped CONNECTED here would fail this).
        assertNotEquals(ConnectionState.CONNECTED, source.connectionState.value)

        // CCCD write confirms (on the SAME descriptor production wrote) → CONNECTED. The "no backoff
        // penalty on the happy path" fact is observable as CONNECTED here; the reconnect counter is
        // only asserted on the cccdWriteFailure/staleDisconnect paths where backoff IS the contract.
        cb.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS)
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
    }

    // --- Tier-2 paths beyond the spike (all six are previously-buggy paths, §4) ---

    @Test
    fun cccdWriteFailure_neverConnectedAndArmsBackoff() {
        val cb = connect()
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        val cccd = stubCscService()
        cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        // CCCD write fails: production calls g.disconnect() and returns — must NOT be CONNECTED.
        cb.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_FAILURE)
        assertNotEquals(ConnectionState.CONNECTED, source.connectionState.value)
        // Anti-vacuity: prove PRODUCTION requested the teardown. Without this the backoff below would
        // be armed only by the test's own hand-driven disconnect, not by a failed subscription.
        verify(gatt).disconnect()

        // The follow-on local disconnect (wasReady=false) is what arms the flap backoff. Production
        // ignores the disconnect `status` (backoff is keyed purely on wasReady), so GATT_SUCCESS here
        // is irrelevant by design — a real status-133 flap arms the same backoff via the same wasReady
        // path; do not add a status-133 branch expecting a test to catch its regression.
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertNotEquals(ConnectionState.CONNECTED, source.connectionState.value)
        assertEquals(1, source.reconnect.failures(address))
        assertFalse("backoff must block an immediate retry", source.reconnect.canAttempt(address))
    }

    @Test
    fun staleDisconnectCallback_doesNotEvictTheNewerConnection() {
        val cbA = connect()
        subscribe(cbA) // A healthy + owning the slot
        // Healthy drop of A (wasReady=true): clears backoff, frees the slot, no penalty.
        cbA.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        val cbB = connect() // B now owns the slot
        assertSame(cbB, source.connectionCallbackForTest())

        // A late/duplicate disconnect for the OLD connection must NOT evict the live B.
        cbA.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertSame("stale callback must not evict the live connection", cbB, source.connectionCallbackForTest())
        assertEquals(0, source.reconnect.failures(address))
    }

    @Test
    fun staleMeasurement_onReplacedConnection_isDropped() = runBlocking {
        val cbA = connect()
        subscribe(cbA) // A.ready = true, slot = A
        // stop() (and the paired-change teardown it mirrors) frees the slot but does NOT reset
        // A.ready. A REAL onConnectionStateChange(DISCONNECTED) sets ready=false, which the !ready
        // clause would catch first — so a ready-preserving teardown is the ONLY way to isolate the
        // `connection.get() !== this` identity clause this test exists to lock.
        source.stop()
        val cbB = connect()
        subscribe(cbB) // slot = B
        // Guard the pre-condition the comment relies on: the slot must now be B (distinct from A),
        // so a future refactor that reset ready in stop() can't silently move this test onto the
        // !ready clause and keep passing while abandoning the identity-gate regression lock.
        assertSame(cbB, source.connectionCallbackForTest())
        assertNotEquals(cbA, cbB)

        val received = CopyOnWriteArrayList<WheelRevolutionReading>()
        val job = launch { source.revolutionReadings.collect { received.add(it) } }

        cbA.onCharacteristicChanged(gatt, measurementChar(), wheelPacket(revs = 1000, time = 5000))
        // The negative window is meaningful only because _readings is Channel(UNLIMITED): a broken
        // gate would trySend into the buffer and the collector would drain it during this delay, so
        // the assertion does NOT depend on the collector having subscribed before the notification.
        delay(50) // give any (erroneous) emission time to arrive

        assertTrue("a stale notification on the replaced connection must persist nothing", received.isEmpty())
        assertEquals("odometer must not advance from a stale measurement", 0.0, source.data.value.odometerKm, 1e-9)
        job.cancel()
    }

    /**
     * Locks the OTHER half of the same `if (!ready || connection.get() !== this)` guard
     * (CscBleDataSource handleMeasurement): a notification arriving AFTER the real torn-down path
     * (onConnectionStateChange(DISCONNECTED) sets ready=false AND evicts the slot) must drop on the
     * `!ready` clause. The staleMeasurement test isolates the identity clause via a ready-preserving
     * teardown; this one drives a genuine disconnect so the two real-world races (replaced-by-newer
     * vs torn-down-not-yet-replaced) are each independently asserted against a guard reorder.
     */
    @Test
    fun measurementAfterDisconnect_isDropped() = runBlocking {
        val cbA = connect()
        subscribe(cbA) // A.ready = true, slot = A
        // A real disconnect: sets cbA.ready=false and evicts the slot (compareAndSet(this,null)).
        cbA.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertNull("the disconnect must have freed the slot", source.connectionCallbackForTest())

        val received = CopyOnWriteArrayList<WheelRevolutionReading>()
        val job = launch { source.revolutionReadings.collect { received.add(it) } }

        cbA.onCharacteristicChanged(gatt, measurementChar(), wheelPacket(revs = 2000, time = 7000))
        delay(50) // give any (erroneous) emission time to arrive

        assertTrue("a notification after disconnect (!ready) must persist nothing", received.isEmpty())
        assertEquals("odometer must not advance after disconnect", 0.0, source.data.value.odometerKm, 1e-9)
        job.cancel()
    }

    @Test
    fun nullConnectGatt_armsBackoffAndLeavesNoZombie() {
        whenever(device.connectGatt(anyOrNull(), eq(false), any())).thenReturn(null)
        source.onScanResultForTest(device, name = "Bike Speed", rssi = -50)

        assertNull("a null connectGatt must leave no zombie in the slot", source.connectionCallbackForTest())
        assertEquals(1, source.reconnect.failures(address))
    }

    @Test
    fun cumulativeCountJumpAcrossReconnect_isAbsorbedAsZeroDelta() = runBlocking {
        val received = CopyOnWriteArrayList<WheelRevolutionReading>()
        val job = launch { source.revolutionReadings.collect { received.add(it) } }

        val cbA = connect()
        subscribe(cbA)
        // Long-lived-sensor steady state: a free-running u32 counter already in the millions
        // (matches CscMeasurementDecoderTest's reboot fixture per testing-plan §7a-step6).
        cbA.onCharacteristicChanged(gatt, measurementChar(), wheelPacket(revs = 1_000_000, time = 5000))

        // Healthy drop, reconnect → a FRESH SensorConnection with a FRESH decoder (haveWheel=false).
        cbA.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        val cbB = connect()
        subscribe(cbB)
        // The sensor's free-running counter advanced +50 while disconnected; the first packet of
        // the new connection must land on the decoder's baseline branch (delta 0), NOT count 50
        // phantom revolutions. (time=6000 is INERT here: B's decoder restarts its event-time
        // accumulator at 0 per connection, so this baseline packet's time is recorded but never
        // differenced against A's — this pairing asserts no cross-segment timing relationship.)
        cbB.onCharacteristicChanged(gatt, measurementChar(), wheelPacket(revs = 1_000_050, time = 6000))

        withTimeout(1_000) { while (received.size < 2) delay(5) }
        job.cancel()

        val post = received.last()
        assertEquals(1_000_050L, post.cumulativeRevolutions)
        // deltaRevolutions==0 is what the DAO's SUM(deltaRevolutions) distance math keys on, so a
        // 0-delta baseline packet contributes no phantom distance.
        assertEquals("a +50 jump across a reconnect must not count as 50 revolutions", 0L, post.deltaRevolutions)
    }

    /**
     * Ending collection (CollectionService.onDestroy → app.stopCollection() →
     * repository.onBackground() → source.stop()) must disconnect() the radio link BEFORE close().
     * A bare close() only releases the local GATT
     * client handle and leaves the sensor's ACL up (firmware reports conn=1, disc=0) until its
     * supervision timeout fires (status=8); the next collection start then layers a duplicate client
     * over that still-live link. Asserting the disconnect-before-close ordering pins the fix.
     */
    @Test
    fun stop_disconnectsRadioLinkBeforeClosing() {
        val cb = connect()
        subscribe(cb)
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)

        source.stop()

        // disconnect() asks the stack to drop the ACL; close() then frees the client. Order matters:
        // close()-first (the bug) strands the peer connected. inOrder also proves disconnect() ran.
        inOrder(gatt) {
            verify(gatt).disconnect()
            verify(gatt).close()
        }
        assertNull("stop() must free the connection slot", source.connectionCallbackForTest())
    }

    // --- helpers ---

    /** Feed a sighting for the paired sensor and return the GATT callback now owning the slot. */
    private fun connect(): BluetoothGattCallback {
        source.onScanResultForTest(device, name = "Bike Speed", rssi = -50)
        return source.connectionCallbackForTest() ?: error("no connection was opened")
    }

    /** Drive a clean connect → discover → CCCD-confirm so [cb] becomes ready/CONNECTED. */
    private fun subscribe(cb: BluetoothGattCallback) {
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        stubCscService()
        cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        // A throwaway descriptor mock is sound here ONLY because the success path of
        // onDescriptorWrite ignores its descriptor argument; happyPath is the single test that
        // pins production issued the write on the captured CCCD. Do not copy this where identity matters.
        cb.onDescriptorWrite(gatt, mock(), BluetoothGatt.GATT_SUCCESS)
    }

    /** A mock CSC measurement characteristic (only its UUID is read by the dispatch). */
    private fun measurementChar(): BluetoothGattCharacteristic {
        val c: BluetoothGattCharacteristic = mock()
        whenever(c.uuid).thenReturn(CSC_MEASUREMENT_UUID)
        return c
    }

    /** The exact 7-byte wheel-only CSC packet this firmware emits (flags 0x01). */
    private fun wheelPacket(revs: Long, time: Int): ByteArray = byteArrayOf(
        0x01,
        (revs and 0xFF).toByte(),
        ((revs shr 8) and 0xFF).toByte(),
        ((revs shr 16) and 0xFF).toByte(),
        ((revs shr 24) and 0xFF).toByte(),
        (time and 0xFF).toByte(),
        ((time shr 8) and 0xFF).toByte(),
    )

    private companion object {
        val CSC_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        val CSC_MEASUREMENT_UUID: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
