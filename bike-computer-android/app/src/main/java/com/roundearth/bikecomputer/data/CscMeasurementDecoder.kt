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
    )

    private var haveWheel = false
    private var lastWheelRevs = 0L
    private var lastWheelTime = 0

    private var haveCrank = false
    private var lastCrankRevs = 0
    private var lastCrankTime = 0

    fun decode(data: ByteArray, circumferenceMeters: Double): Result {
        if (data.isEmpty()) return Result()
        val flags = data[0].toInt() and 0xFF
        var i = 1
        var speedKph: Double? = null
        var cadenceRpm: Double? = null
        var distance = 0.0
        var wheelRevs: Long? = null
        var wheelTime: Int? = null

        if (flags and FLAG_WHEEL != 0) {
            if (data.size < i + 6) return Result()
            val revs = readUInt32(data, i); i += 4
            val time = readUInt16(data, i); i += 2
            wheelRevs = revs
            wheelTime = time
            // Speed = Δrevs × circumference / Δtime
            if (haveWheel && revs >= lastWheelRevs) {
                val dRevs = revs - lastWheelRevs
                val dTicks = (time - lastWheelTime) and 0xFFFF // 1/1024 s, wraps at 16 bits
                if (dRevs > 0 && dTicks > 0) {
                    distance = dRevs * circumferenceMeters
                    speedKph = distance / (dTicks / 1024.0) * 3.6
                }
            }
            haveWheel = true
            lastWheelRevs = revs
            lastWheelTime = time
        }

        if (flags and FLAG_CRANK != 0) {
            if (data.size < i + 4) return Result(speedKph, cadenceRpm, distance, wheelRevs, wheelTime)
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

        return Result(speedKph, cadenceRpm, distance, wheelRevs, wheelTime)
    }

    companion object {
        const val FLAG_WHEEL = 0x01
        const val FLAG_CRANK = 0x02

        fun readUInt16(d: ByteArray, o: Int): Int =
            (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

        fun readUInt32(d: ByteArray, o: Int): Long =
            (d[o].toLong() and 0xFF) or
                ((d[o + 1].toLong() and 0xFF) shl 8) or
                ((d[o + 2].toLong() and 0xFF) shl 16) or
                ((d[o + 3].toLong() and 0xFF) shl 24)
    }
}
