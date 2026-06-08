package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the stateful reconnect state machine [ReconnectPolicy] drives for
 * [CscBleDataSource] — the surface where the real BLE flaps live. A fake clock stands
 * in for SystemClock.elapsedRealtime so the gate, the exponential arm, the success
 * reset, the adapter-toggle clear, and the flap-vs-healthy disconnect rule are all
 * pinned down without a live BLE stack.
 */
class ReconnectPolicyTest {

    private var clock = 0L
    private fun policy() = ReconnectPolicy { clock }

    private val A = "AA:BB:CC:DD:EE:01"
    private val B = "AA:BB:CC:DD:EE:02"

    @Test
    fun freshAddressMayAttemptImmediately() {
        assertTrue(policy().canAttempt(A))
    }

    @Test
    fun failureArmsCooldownThatBlocksUntilItElapses() {
        val p = policy()
        val delay = p.recordFailure(A) // first failure -> 500ms
        assertEquals(500L, delay)
        assertFalse("still cooling down", p.canAttempt(A))

        clock += 499
        assertFalse("499ms in, gate not yet open", p.canAttempt(A))

        clock += 1 // exactly at the gate
        assertTrue("gate is inclusive once now >= gate", p.canAttempt(A))
    }

    @Test
    fun consecutiveFailuresBackOffExponentiallyFromTheCurrentClock() {
        val p = policy()
        assertEquals(500L, p.recordFailure(A))
        clock += 500
        assertEquals(1_000L, p.recordFailure(A))
        clock += 1_000
        assertEquals(2_000L, p.recordFailure(A))
        // The gate is armed relative to *now*, not stacked onto the previous gate.
        clock += 1_999
        assertFalse(p.canAttempt(A))
        clock += 1
        assertTrue(p.canAttempt(A))
    }

    @Test
    fun successClearsBackoffSoNextFailureStartsOver() {
        val p = policy()
        repeat(4) { p.recordFailure(A) } // would be a 4s cooldown
        assertEquals(4, p.failures(A))

        p.recordSuccess(A)
        assertEquals(0, p.failures(A))
        assertTrue("a healthy subscription reconnects with no penalty", p.canAttempt(A))

        // A later drop starts the schedule from the base again, not from where it left off.
        assertEquals(500L, p.recordFailure(A))
    }

    @Test
    fun disconnectAfterSubscriptionIsNotPenalized() {
        val p = policy()
        p.recordFailure(A) // armed
        p.onDisconnect(A, wasSubscribed = true)
        assertEquals(0, p.failures(A))
        assertTrue(p.canAttempt(A))
    }

    @Test
    fun disconnectBeforeSubscriptionIsTreatedAsAFlap() {
        val p = policy()
        p.onDisconnect(A, wasSubscribed = false)
        assertEquals(1, p.failures(A))
        assertFalse(p.canAttempt(A))
    }

    @Test
    fun backoffIsTrackedPerAddressIndependently() {
        val p = policy()
        p.recordFailure(A)
        assertFalse(p.canAttempt(A))
        assertTrue("B has no failures of its own", p.canAttempt(B))
        assertEquals(0, p.failures(B))
    }

    @Test
    fun clearDropsAllBackoffForEveryAddress() {
        val p = policy()
        p.recordFailure(A)
        p.recordFailure(B)
        assertFalse(p.canAttempt(A))
        assertFalse(p.canAttempt(B))

        p.clear() // models a deliberate Bluetooth toggle
        assertTrue(p.canAttempt(A))
        assertTrue(p.canAttempt(B))
        assertEquals(0, p.failures(A))
        assertEquals(0, p.failures(B))
    }
}
