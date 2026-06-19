package com.roundearth.bikecomputer.data

import android.bluetooth.BluetoothGatt
import androidx.test.core.app.ApplicationProvider
import com.roundearth.bikecomputer.data.SimulatedCscDevice.GattGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end BLE connectivity tests driven by a fully [SimulatedCscDevice] — no real radio.
 *
 * Where [CscConnectionTest] hand-wires individual callbacks to lock specific state-machine
 * invariants, this suite scripts whole connection lifecycles through the simulated device so a
 * realistic failure (a sensor that advertises CSC but exposes no usable measurement characteristic
 * after discovery — a partial GATT cache, a sensor mid-reflash) can be reproduced exactly as a user
 * would hit it.
 *
 * The `…recoversInsteadOfWedging` tests assert the CORRECT (hardened) behavior, so they are
 * failing-then-passing regression locks: they FAIL on the historical bug where onServicesDiscovered
 * bare-returned (stranding the connection slot forever so the app never reconnected) and PASS once
 * that branch drops the half-open link via g.disconnect().
 */
@RunWith(RobolectricTestRunner::class)
class CscSimulatedDeviceE2eTest {

    private lateinit var source: CscBleDataSource
    private lateinit var sim: SimulatedCscDevice

    @Before
    fun setUp() {
        source = CscBleDataSource(
            context = ApplicationProvider.getApplicationContext(),
            wheelCircumferenceM = { 2.0 },
        )
        sim = SimulatedCscDevice(source)
    }

    @After
    fun tearDown() = source.stop()

    /**
     * Positive control: a healthy sensor drives the whole pipeline — scan → connect → discover →
     * subscribe → notify — to CONNECTED with revolutions persisted. Proves the simulated device
     * actually exercises the production path end-to-end (so a wedge test below failing means the
     * BUG, not a broken harness).
     */
    @Test
    fun healthySensor_streamsRevolutionsEndToEnd() = runBlocking {
        val readings = CopyOnWriteArrayList<WheelRevolutionReading>()
        val job = launch { source.revolutionReadings.collect { readings.add(it) } }

        sim.scanSighting().connected().discovered(GattGraph.healthy()).confirmCccd()
        assertEquals(ConnectionState.CONNECTED, source.connectionState.value)

        // First packet is the per-connection baseline (delta 0); the second advances the odometer
        // by 10 revs * 2.0 m = 20 m. (Distance comes from the rev-count delta, independent of the
        // shadow clock, so this assertion is deterministic without advancing SystemClock.)
        sim.notify(revs = 100, time = 1_000)
        sim.notify(revs = 110, time = 2_000)

        withTimeout(1_000) { while (readings.size < 2) delay(5) }
        job.cancel()

        assertEquals(110L, readings.last().cumulativeRevolutions)
        assertEquals(10L, readings.last().deltaRevolutions)
        assertTrue("odometer must advance from streamed revolutions", source.data.value.odometerKm > 0.0)
    }

    /**
     * THE BUG (rank #1). A sensor that connects and completes discovery but exposes no CSC
     * measurement characteristic must drop the link so scanning can reconnect — NOT bare-return and
     * leave the connection slot occupied forever (which strands the app: every later sighting
     * short-circuits on the non-null slot, so it never reconnects and never streams data).
     */
    @Test
    fun missingMeasurementChar_recoversInsteadOfWedgingForever() {
        sim.scanSighting()
        val cb = sim.callback()
        assertSame("the sighting must claim the connection slot", cb, sim.slotCallback())

        sim.connected().discovered(GattGraph.missingMeasurementChar())

        // THE discriminating assertion: the hardened branch drops the half-open link. The historical
        // bug bare-returned, so this fails with zero disconnect() invocations.
        verify(sim.gatt).disconnect()
        assertNotEquals(ConnectionState.CONNECTED, source.connectionState.value)

        assertRecoversAfterDisconnect(cb)
    }

    /**
     * Sibling of the rank #1 wedge (rank #4): the measurement characteristic exists but its 0x2902
     * CCCD descriptor is absent, so the source can never subscribe. Same fix, same recovery contract.
     */
    @Test
    fun missingCccdDescriptor_recoversInsteadOfWedgingForever() {
        sim.scanSighting()
        val cb = sim.callback()
        sim.connected().discovered(GattGraph.missingCccd())

        verify(sim.gatt).disconnect()
        assertNotEquals(ConnectionState.CONNECTED, source.connectionState.value)

        assertRecoversAfterDisconnect(cb)
    }

    /**
     * Rank #5: discovery completes with a non-success status and an empty service table (getService
     * returns null). Today onServicesDiscovered ignores its status param and falls into the same
     * wedge; the hardened code drops the link and recovers.
     */
    @Test
    fun failedDiscoveryStatus_recoversInsteadOfWedgingForever() {
        sim.scanSighting()
        val cb = sim.callback()
        // status 129 = GATT_INTERNAL_ERROR with an empty service table (noService -> getService(CSC)
        // is null). The status guard drops the link before discovery is even inspected; even without
        // it, the null table would fall into the same measurement==null wedge.
        sim.connected().discovered(GattGraph.noService(), status = 129)

        verify(sim.gatt).disconnect()
        assertNotEquals(ConnectionState.CONNECTED, source.connectionState.value)

        assertRecoversAfterDisconnect(cb)
    }

    /**
     * Shared recovery oracle: the disconnect the fix requested arrives, frees the slot, and a
     * later sighting (after the flap backoff expires) opens a fresh GATT client — proving the app
     * is no longer wedged. [cb] is the original (now-dead) connection that owned the slot.
     */
    private fun assertRecoversAfterDisconnect(cb: android.bluetooth.BluetoothGattCallback) {
        // The g.disconnect() above surfaces as an unsolicited STATE_DISCONNECTED; its handler frees
        // the slot and arms a flap backoff (this connection never became ready).
        sim.unsolicitedDisconnect()
        assertNull("the disconnect must free the connection slot", sim.slotCallback())
        assertEquals("a pre-subscribe drop is a flap", 1, source.reconnect.failures(sim.address))

        // Past the 500 ms flap cooldown, a fresh sighting reconnects (a brand-new GATT client opens).
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        sim.scanSighting()
        verify(sim.device, times(2)).connectGatt(anyOrNull(), eq(false), any())
    }
}
