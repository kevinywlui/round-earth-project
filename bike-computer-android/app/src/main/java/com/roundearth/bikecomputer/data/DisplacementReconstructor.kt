package com.roundearth.bikecomputer.data

import com.roundearth.bikecomputer.data.db.BacklogMinute
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reconstructs 2-D displacement (north–south and east–west) from the per-minute backlog timeline,
 * the coarse view used for windows where the live per-revolution data is absent (revolutions
 * recovered from the sensor's flash over a disconnect, never heading-stamped at the time).
 *
 *   north = Σ Δrev · circ · cos(θ_true)        east = Σ Δrev · circ · sin(θ_true)
 *
 * Load-bearing rules (from the review contract):
 * - Δrev per minute is the forward difference of [BacklogMinute.cumulativeRevolutions] WITHIN one
 *   bootId; never across a boot boundary (a reboot resets the counter), and a negative step is skipped.
 * - A minute with revolutions but an UNKNOWN heading (NULL / NaN) has an UNKNOWN direction — its
 *   north/east are null and its distance is tallied into [Total.unknownDirectionMeters]. It is NEVER
 *   treated as cos(0)=north or as 0, which would silently corrupt the displacement.
 * - Distance is always known (from revolutions) even when direction is not.
 *
 * This is a pure function (no Android, no DB) so it is unit-testable; the repository feeds it the
 * backlog rows and a minute→true-heading lookup.
 */
object DisplacementReconstructor {

    data class MinuteDisplacement(
        val minuteEpoch: Long,
        val distanceMeters: Double,
        /** North component (m), or null when the heading for this minute was unknown. */
        val northMeters: Double?,
        /** East component (m), or null when the heading for this minute was unknown. */
        val eastMeters: Double?,
    )

    data class Total(
        val northMeters: Double,
        val eastMeters: Double,
        /** Distance (m) whose direction was known and folded into north/east. */
        val knownDirectionMeters: Double,
        /** Distance (m) that happened but whose direction was unknown (no heading that minute). */
        val unknownDirectionMeters: Double,
    )

    /**
     * @param backlog all backlog rows (any order); grouped by bootId and ordered by recordIndex here.
     * @param trueHeadingForMinute minuteEpoch → true heading in degrees, or null/NaN when unknown.
     */
    fun reconstruct(
        backlog: List<BacklogMinute>,
        trueHeadingForMinute: (Long) -> Float?,
    ): Pair<List<MinuteDisplacement>, Total> {
        val perMinute = ArrayList<MinuteDisplacement>()
        var north = 0.0
        var east = 0.0
        var knownDist = 0.0
        var unknownDist = 0.0

        // Per boot, ordered by the sensor's monotonic record index, so consecutive deltas are valid.
        val byBoot = backlog.groupBy { it.bootId }
        for ((_, rowsRaw) in byBoot) {
            val rows = rowsRaw.sortedBy { it.recordIndex }
            for (i in 1 until rows.size) {
                val prev = rows[i - 1]
                val cur = rows[i]
                val dRev = cur.cumulativeRevolutions - prev.cumulativeRevolutions
                if (dRev <= 0) continue // reboot/duplicate/no-progress within a boot — no distance
                val dist = dRev * cur.wheelCircumferenceM
                val minute = cur.wallClockMillis / 60_000L
                val heading = trueHeadingForMinute(minute)
                if (heading == null || heading.isNaN()) {
                    // Direction unknown for this minute: distance is real, projection is not.
                    perMinute += MinuteDisplacement(minute, dist, null, null)
                    unknownDist += dist
                } else {
                    val r = Math.toRadians(heading.toDouble())
                    val n = dist * cos(r)
                    val e = dist * sin(r)
                    perMinute += MinuteDisplacement(minute, dist, n, e)
                    north += n
                    east += e
                    knownDist += dist
                }
            }
        }
        return perMinute to Total(north, east, knownDist, unknownDist)
    }
}
