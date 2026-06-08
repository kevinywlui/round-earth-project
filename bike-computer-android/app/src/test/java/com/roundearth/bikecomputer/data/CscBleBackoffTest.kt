package com.roundearth.bikecomputer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the reconnect backoff schedule used by [CscBleDataSource]. The gate
 * itself depends on the BLE stack, but the delay math is pure and worth pinning down:
 * it throttles the scan-driven reconnect loop after status-133 flaps and null
 * connectGatt results.
 */
class CscBleBackoffTest {

    @Test
    fun firstFailureUsesBaseDelay() {
        assertEquals(500L, CscBleDataSource.backoffDelayMs(1))
    }

    @Test
    fun delayDoublesEachConsecutiveFailure() {
        assertEquals(500L, CscBleDataSource.backoffDelayMs(1))
        assertEquals(1_000L, CscBleDataSource.backoffDelayMs(2))
        assertEquals(2_000L, CscBleDataSource.backoffDelayMs(3))
        assertEquals(4_000L, CscBleDataSource.backoffDelayMs(4))
        assertEquals(8_000L, CscBleDataSource.backoffDelayMs(5))
        assertEquals(16_000L, CscBleDataSource.backoffDelayMs(6))
    }

    @Test
    fun delayIsCappedAtCeiling() {
        // 500 << 6 = 32000 would exceed the cap, so it clamps to 30s and stays there.
        assertEquals(30_000L, CscBleDataSource.backoffDelayMs(7))
        assertEquals(30_000L, CscBleDataSource.backoffDelayMs(20))
        assertEquals(30_000L, CscBleDataSource.backoffDelayMs(1000))
    }

    @Test
    fun nonPositiveFailureCountIsZero() {
        // No failures recorded yet => no cooldown.
        assertEquals(0L, CscBleDataSource.backoffDelayMs(0))
        assertEquals(0L, CscBleDataSource.backoffDelayMs(-1))
    }
}
