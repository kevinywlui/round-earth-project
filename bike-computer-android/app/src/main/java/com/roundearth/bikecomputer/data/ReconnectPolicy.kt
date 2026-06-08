package com.roundearth.bikecomputer.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-sensor reconnect backoff bookkeeping for [CscBleDataSource], pulled out as a
 * plain class so the reconnect state-machine rules can be unit-tested without a live
 * BLE stack — this is the surface where the classic flaps live (status-133 retries,
 * a null `connectGatt`, a CCCD write that never confirms).
 *
 * The rules:
 *  - A failed/abandoned attempt arms an exponential cooldown ([recordFailure]); while
 *    cooling down, [canAttempt] returns false so the continuous scan can't hot-loop.
 *  - A confirmed-healthy sensor clears its cooldown ([recordSuccess]) so a later drop
 *    reconnects promptly instead of inheriting an old penalty.
 *  - A disconnect *before* a healthy subscription is a flap (penalize); *after* one it
 *    is not ([onDisconnect]).
 *  - A deliberate Bluetooth toggle clears everything ([clear]) — a fresh start should
 *    not inherit stale backoff.
 *
 * Thread-safe: GATT callbacks (binder threads) and the scan callback touch this
 * concurrently, so the maps are concurrent and each operation is self-contained.
 *
 * [now] supplies a monotonic clock — `SystemClock.elapsedRealtime` in production, a
 * fake in tests.
 */
class ReconnectPolicy(private val now: () -> Long) {

    private val nextAttemptAt = ConcurrentHashMap<String, Long>() // address -> gate (monotonic ms)
    private val failureCount = ConcurrentHashMap<String, Int>()   // address -> consecutive failures

    /** True if [address] is not currently cooling down, so a connect may be attempted. */
    fun canAttempt(address: String): Boolean {
        val gate = nextAttemptAt[address] ?: return true
        return now() >= gate
    }

    /** Arms an exponential backoff after a failed/abandoned attempt; returns the delay (ms). */
    fun recordFailure(address: String): Long {
        val failures = (failureCount[address] ?: 0) + 1
        failureCount[address] = failures
        val delay = backoffDelayMs(failures)
        nextAttemptAt[address] = now() + delay
        return delay
    }

    /** Clears [address]'s backoff once it is confirmed healthy (subscribed). */
    fun recordSuccess(address: String) {
        failureCount.remove(address)
        nextAttemptAt.remove(address)
    }

    /**
     * Applies the disconnect rule for [address]: a drop before we ever subscribed
     * ([wasSubscribed] = false) is a flap and arms a backoff; a drop after a healthy
     * subscription gets no penalty so the scan reconnects fast.
     */
    fun onDisconnect(address: String, wasSubscribed: Boolean) {
        if (wasSubscribed) recordSuccess(address) else recordFailure(address)
    }

    /** Drops all backoff (e.g. a Bluetooth adapter toggle: start fresh, no stale penalty). */
    fun clear() {
        nextAttemptAt.clear()
        failureCount.clear()
    }

    /** Consecutive failures recorded for [address] (0 if none) — for diagnostics/tests. */
    fun failures(address: String): Int = failureCount[address] ?: 0

    companion object {
        // Reconnect backoff: 500ms, 1s, 2s, 4s, ... doubling per consecutive failure,
        // capped at 30s. Shift is bounded so the doubling can't overflow.
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_SHIFT = 16

        /** Backoff delay for the Nth consecutive failure (1-based). Pure, for unit testing. */
        fun backoffDelayMs(failures: Int): Long {
            if (failures <= 0) return 0L
            val shift = (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
            return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        }
    }
}
