package com.roundearth.bikecomputer.data

/**
 * Stateful decoder for the CSC Measurement characteristic (0x2A5B), per the
 * Cycling Speed and Cadence Profile §4.4. Pure Kotlin with no Android
 * dependencies so it can be unit tested on the JVM.
 *
 * Feed it successive packets from a single sensor; each call returns the values
 * derivable from that packet relative to the previous one. The first packet (or
 * any with no forward progress, e.g. coasting) yields null speed/cadence. The
 * raw wheel revolution count and event time are always surfaced when wheel data
 * is present, so callers can persist a lossless time-series.
 */
class CscMeasurementDecoder {

    data class Result(
        val speedKph: Double? = null,     // null when not derivable from this packet
        val cadenceRpm: Double? = null,   // null when not derivable from this packet
        val distanceMeters: Double = 0.0, // wheel distance added by this packet
        // Raw wheel fields, present whenever the wheel-data flag is set:
        val wheelCumulativeRevs: Long? = null,
        val wheelEventTime1024: Int? = null,
        /** Wheel revolutions this packet advanced by (0 on the first packet / a reboot). */
        val wheelDeltaRevs: Long = 0L,
    )

    private var haveWheel = false
    private var lastWheelRevs = 0L
    private var lastWheelTime = 0
    // Wall-clock of the previous wheel packet, used only to detect event-time wrap.
    private var haveReceiveTime = false
    private var lastReceiveMs = 0L

    private var haveCrank = false
    private var lastCrankRevs = 0
    private var lastCrankTime = 0

    /**
     * @param receivedAtMs wall-clock (monotonic, e.g. elapsedRealtime) at which this
     *   packet arrived, used to detect when the 16-bit event time has wrapped. Pass
     *   [NO_TIMESTAMP] (the default) when no clock is available; speed is then derived
     *   from the event-time delta alone.
     */
    fun decode(data: ByteArray, circumferenceMeters: Double, receivedAtMs: Long = NO_TIMESTAMP): Result {
        if (data.isEmpty()) return Result()
        val flags = data[0].toInt() and 0xFF
        var i = 1
        var speedKph: Double? = null
        var cadenceRpm: Double? = null
        var distance = 0.0
        var wheelDelta = 0L
        var wheelRevs: Long? = null
        var wheelTime: Int? = null

        if (flags and FLAG_WHEEL != 0) {
            if (data.size < i + 6) return Result()
            val revs = readUInt32(data, i); i += 4
            val time = readUInt16(data, i); i += 2
            wheelRevs = revs
            wheelTime = time
            if (haveWheel) {
                // 32-bit wrap-aware forward delta (mirrors the crank's 16-bit masking).
                // A backward jump past half the range is a sensor reboot (counter
                // restarted), not a real advance, so it contributes no distance.
                val raw = (revs - lastWheelRevs) and 0xFFFFFFFFL
                val dRevs = if (raw in 1L until 0x8000_0000L) raw else 0L
                if (dRevs > 0L) {
                    wheelDelta = dRevs
                    // Distance comes from the cumulative count, which is reliable even
                    // when the event-time delta is 0 or has wrapped — so always count it.
                    distance = dRevs * circumferenceMeters
                    val dTicks = (time - lastWheelTime) and 0xFFFF // 1/1024 s, wraps at 16 bits
                    // The event time wraps every 64 s, aliasing a long stop into a tiny
                    // dTicks and a huge false speed. If we can see the real elapsed time
                    // and it is at/over a wrap, skip speed (distance still counts).
                    val wrapped = receivedAtMs != NO_TIMESTAMP && haveReceiveTime &&
                        receivedAtMs - lastReceiveMs >= WHEEL_TIME_WRAP_GUARD_MS
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
        }

        if (flags and FLAG_CRANK != 0) {
            if (data.size < i + 4) return Result(speedKph, cadenceRpm, distance, wheelRevs, wheelTime, wheelDelta)
            val revs = readUInt16(data, i); i += 2
            val time = readUInt16(data, i); i += 2
            // Cadence = Δrevs / Δtime (rev/min)
            if (haveCrank) {
                val dRevs = (revs - lastCrankRevs) and 0xFFFF // 16-bit count wraps
                val dTicks = (time - lastCrankTime) and 0xFFFF
                if (dRevs > 0 && dTicks > 0) {
                    cadenceRpm = dRevs / (dTicks / 1024.0) * 60.0
                }
            }
            haveCrank = true
            lastCrankRevs = revs
            lastCrankTime = time
        }

        return Result(speedKph, cadenceRpm, distance, wheelRevs, wheelTime, wheelDelta)
    }

    companion object {
        const val FLAG_WHEEL = 0x01
        const val FLAG_CRANK = 0x02
        /** Sentinel for [decode]'s receivedAtMs when no wall-clock is available. */
        const val NO_TIMESTAMP = Long.MIN_VALUE
        // Event time wraps at 65536/1024 = 64 s; treat gaps at/over this as a wrap.
        const val WHEEL_TIME_WRAP_GUARD_MS = 60_000L

        fun readUInt16(d: ByteArray, o: Int): Int =
            (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

        fun readUInt32(d: ByteArray, o: Int): Long =
            (d[o].toLong() and 0xFF) or
                ((d[o + 1].toLong() and 0xFF) shl 8) or
                ((d[o + 2].toLong() and 0xFF) shl 16) or
                ((d[o + 3].toLong() and 0xFF) shl 24)
    }
}
