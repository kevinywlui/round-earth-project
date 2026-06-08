package com.roundearth.bikecomputer.data

/**
 * Stateful decoder for the CSC Measurement characteristic (0x2A5B), per the
 * Cycling Speed and Cadence Profile §4.4. Pure Kotlin with no Android
 * dependencies so it can be unit tested on the JVM.
 *
 * This sensor reports **wheel** data only (the firmware advertises just the
 * wheel-revolution feature), so only the wheel half of the characteristic is
 * decoded; crank/cadence is ignored.
 *
 * Feed it successive packets from a single sensor; each call returns the values
 * derivable from that packet relative to the previous one. The first packet (or
 * any with no forward progress, e.g. coasting) yields null speed. The raw wheel
 * revolution count and event time are always surfaced when wheel data is present,
 * so callers can persist a lossless time-series.
 */
class CscMeasurementDecoder {

    data class Result(
        val speedKph: Double? = null,     // null when not derivable from this packet
        val distanceMeters: Double = 0.0, // wheel distance added by this packet
        // Raw wheel fields, present whenever the wheel-data flag is set:
        val wheelCumulativeRevs: Long? = null,
        val wheelEventTime1024: Int? = null,
        /** Wheel revolutions this packet advanced by (0 on the first packet / a reboot). */
        val wheelDeltaRevs: Long = 0L,
        /**
         * Monotonic sensor time in 1/1024 s units, accumulated by unwrapping the 16-bit
         * event time across packets (starts at 0 on the first packet). Unlike the wall-clock
         * receive time, successive-revolution deltas of this are free of BLE delivery jitter,
         * so offline speed analysis is precise while moving. A reboot or a >=64 s stop makes
         * the event time ambiguous for that one gap, which is then estimated from the wall
         * clock — see [decode]; the series stays monotonic throughout.
         *
         * The accumulator advances on every wheel-flagged packet. With this firmware a
         * [wheelDeltaRevs]==0 row after the baseline is NOT a stationary coast — the firmware
         * notifies strictly once per real revolution and emits no keepalive/liveness packets —
         * so it can only be the first packet of a segment or a sensor reboot. The forward step
         * on such a row is a wall-clock estimate, not jitter-free. Offline speed reconstruction
         * must therefore guard against [wheelDeltaRevs]==0 when dividing and not treat that
         * step as precise timing.
         *
         * Scope: accumulated over ONE decoder's lifetime, i.e. one BLE connection. A
         * reconnect builds a fresh decoder, so this restarts at 0 — offline analysis should
         * treat a backward jump as a connection-segment boundary and bridge it using the
         * stored wall-clock timestamp. Note that the cross-segment bridge timestamp is the
         * persisted wall-clock receive time; the in-gap estimates here come from the monotonic
         * clock passed to [decode]. They run at the same rate but are different clocks, so do
         * not mix them within a single segment.
         */
        val cumulativeEventTime1024: Long = 0L,
    )

    // All decoder state below is single-threaded by contract: decode() is entered only from
    // the serialized GATT callback thread of the one connection that owns this decoder, never
    // shared across connections and never re-invoked off-thread. It is therefore unsynchronized;
    // a future refactor that drives decode() from a coroutine/Handler must add synchronization.
    private var haveWheel = false
    private var lastWheelRevs = 0L
    private var lastWheelTime = 0
    // Wall-clock of the previous wheel packet: detects event-time wrap and estimates the
    // elapsed time across an ambiguous (reboot / >64 s) gap for the monotonic accumulator.
    private var haveReceiveTime = false
    private var lastReceiveMs = 0L
    // Monotonic accumulated sensor time (1/1024 s), surfaced as Result.cumulativeEventTime1024.
    private var cumulativeEventTime1024 = 0L

    /**
     * @param receivedAtMs wall-clock (monotonic, e.g. elapsedRealtime) at which this
     *   packet arrived, used to detect when the 16-bit event time has wrapped. Pass
     *   [NO_TIMESTAMP] only when no clock is available; doing so **disables the 64 s
     *   wrap guard**, so a long coast that aliases into a tiny dTicks then yields a
     *   large false speed. Production callers must always pass a real clock; the
     *   default exists only for tests that exercise the no-clock path deliberately.
     */
    fun decode(data: ByteArray, circumferenceMeters: Double, receivedAtMs: Long = NO_TIMESTAMP): Result {
        if (data.isEmpty()) return Result()
        val flags = data[0].toInt() and 0xFF
        if (flags and FLAG_WHEEL == 0) return Result()

        var i = 1
        if (data.size < i + 6) return Result()
        val revs = readUInt32(data, i); i += 4
        val time = readUInt16(data, i)

        var speedKph: Double? = null
        var distance = 0.0
        var wheelDelta = 0L

        if (haveWheel) {
            // 32-bit wrap-aware forward delta. A backward jump past half the range is a
            // sensor reboot (counter restarted), not a real advance, so no distance.
            val raw = (revs - lastWheelRevs) and 0xFFFFFFFFL
            val reboot = raw >= 0x8000_0000L
            val dTicks = (time - lastWheelTime) and 0xFFFF // 1/1024 s, wraps at 16 bits
            val realGapMs = if (receivedAtMs != NO_TIMESTAMP && haveReceiveTime) {
                receivedAtMs - lastReceiveMs
            } else {
                null
            }
            // Speed suppression: the event time wraps every 64 s, aliasing a long stop into a
            // tiny dTicks (and a huge false speed). The 60 s guard is deliberately conservative
            // — better to drop a sample than emit a false spike near the wrap boundary.
            val wrapped = realGapMs != null && realGapMs >= SPEED_WRAP_GUARD_MS

            // Advance the monotonic sensor time. dTicks is the exact, BLE-jitter-free sensor
            // delta whenever the event time has NOT actually wrapped, i.e. the real gap is
            // under the true 64 s wrap period — so prefer it there (this includes the 60–64 s
            // band the conservative speed guard rejects). Only when the gap reaches a real wrap,
            // or the counter rebooted, is dTicks ambiguous; then estimate that one gap from the
            // wall clock (falling back to dTicks when no clock is available). Always >= 0.
            //
            // The wrap discriminator is the wall-clock gap, which carries its own delivery
            // jitter, so it is only a proxy for the sensor's own 16-bit wrap. A real gap landing
            // within a few hundred ms of the 64 s boundary may thus be misclassified: a true
            // >64 s gap read as <64 s would advance by the ALIASED (tiny) dTicks instead of the
            // estimate — under-counting that one gap while staying monotonic. Reaching that case
            // needs sub-second wall-clock jitter exactly at the boundary, so the "exact while
            // moving" guarantee holds in practice; the inverse (a <64 s gap read as >64 s) merely
            // swaps an exact delta for a same-magnitude estimate and is harmless.
            val ambiguous = reboot || (realGapMs != null && realGapMs >= EVENT_TIME_WRAP_MS)
            cumulativeEventTime1024 += if (ambiguous) {
                wallClockTicks(receivedAtMs)
            } else {
                dTicks.toLong()
            }

            val dRevs = if (raw in 1L until 0x8000_0000L) raw else 0L
            if (dRevs > 0L) {
                wheelDelta = dRevs
                // Distance comes from the cumulative count, which is reliable even when
                // the event-time delta is 0 or has wrapped — so always count it.
                distance = dRevs * circumferenceMeters
                if (dTicks > 0 && !wrapped) {
                    speedKph = distance / (dTicks / 1024.0) * 3.6
                }
            }
        }
        haveWheel = true
        lastWheelRevs = revs
        lastWheelTime = time
        if (receivedAtMs != NO_TIMESTAMP) {
            lastReceiveMs = receivedAtMs
            haveReceiveTime = true
        }

        return Result(speedKph, distance, revs, time, wheelDelta, cumulativeEventTime1024)
    }

    /**
     * Elapsed time since the previous packet in 1/1024 s, estimated from the wall clock —
     * used to advance the monotonic accumulator across a gap where the 16-bit event time is
     * ambiguous (a reboot or a >64 s wrap). Falls back to 0 when no clock is available
     * (tests), keeping the accumulator monotonic without inventing time. Clamped to >= 0.
     */
    private fun wallClockTicks(receivedAtMs: Long): Long {
        if (receivedAtMs == NO_TIMESTAMP || !haveReceiveTime) return 0L
        return ((receivedAtMs - lastReceiveMs).coerceAtLeast(0L) * 1024L) / 1000L
    }

    companion object {
        const val FLAG_WHEEL = 0x01
        /** Sentinel for [decode]'s receivedAtMs when no wall-clock is available. */
        const val NO_TIMESTAMP = Long.MIN_VALUE
        // Conservative guard for SPEED suppression: a gap at/over this is treated as a
        // possible wrap and speed is dropped rather than risk a false spike near the boundary.
        const val SPEED_WRAP_GUARD_MS = 60_000L
        // True 16-bit event-time wrap period: 65536/1024 = 64 s exactly. Used only by the
        // monotonic accumulator, where the exact dTicks is preferable right up to a real wrap
        // (so the 60–64 s band keeps the jitter-free sensor delta instead of a wall-clock guess).
        const val EVENT_TIME_WRAP_MS = 64_000L

        fun readUInt16(d: ByteArray, o: Int): Int =
            (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

        fun readUInt32(d: ByteArray, o: Int): Long =
            (d[o].toLong() and 0xFF) or
                ((d[o + 1].toLong() and 0xFF) shl 8) or
                ((d[o + 2].toLong() and 0xFF) shl 16) or
                ((d[o + 3].toLong() and 0xFF) shl 24)
    }
}
