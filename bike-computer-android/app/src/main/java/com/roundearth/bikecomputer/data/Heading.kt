package com.roundearth.bikecomputer.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Accumulates compass headings into a circular (vector) mean — the only correct way to average
 * angles across the 0/360 wrap. An arithmetic mean of 350° and 10° gives 170° (pointing *south*),
 * which would flip the sign of cos/sin and invert that minute's displacement vector; the vector
 * mean correctly gives 0°. We sum unit vectors (Σsin, Σcos); the mean direction is atan2(Σsin, Σcos).
 *
 * NaN samples ("unknown heading") are skipped so a single dropout can't poison the sum, and [mean]
 * returns [Float.NaN] when no valid sample was added — preserving the NaN="unknown" / NULL invariant
 * for a minute with no usable compass reading (it must never collapse to 0° = due north).
 *
 * Average over the minute (not an instantaneous on-the-minute sample) because a single read can land
 * mid-corner and mislabel the whole minute; and store the MAGNETIC mean so declination stays a
 * recoverable per-row quantity (true − magnetic), exactly as the per-revolution model does.
 */
class CircularMean {
    private var sumSin = 0.0
    private var sumCos = 0.0
    var count = 0
        private set

    fun add(degrees: Float) {
        if (degrees.isNaN()) return
        val r = Math.toRadians(degrees.toDouble())
        sumSin += sin(r)
        sumCos += cos(r)
        count++
    }

    /** Circular mean in [0, 360), or [Float.NaN] if no valid sample was added. */
    fun mean(): Float {
        if (count == 0) return Float.NaN
        val deg = Math.toDegrees(atan2(sumSin, sumCos)).toFloat()
        return (deg % 360f + 360f) % 360f
    }
}

/**
 * Converts a magnetic heading to a true (geographic) heading by adding the local
 * magnetic declination, normalized back into [0, 360).
 *
 * Declination is positive east: true = magnetic + declination.
 */
fun trueFromMagnetic(magneticDeg: Float, declinationDeg: Float): Float =
    ((magneticDeg + declinationDeg) % 360f + 360f) % 360f

/**
 * Shortest angular distance between two headings in degrees, in [0, 180].
 * Wrap-aware: distance(359.8, 0.2) == 0.4, not 359.6.
 */
fun angularDistance(a: Float, b: Float): Float {
    val d = ((a - b) % 360f + 360f) % 360f
    return if (d > 180f) 360f - d else d
}

/**
 * Signed shortest rotation from [from] to [to], in (-180, 180]. Positive is
 * clockwise. Lets a needle animate the short way across the 0/360 wrap instead of
 * spinning ~340° backwards: signedAngleDelta(350, 10) == 20.
 */
fun signedAngleDelta(from: Float, to: Float): Float {
    val d = ((to - from) % 360f + 360f) % 360f
    return if (d > 180f) d - 360f else d
}

/**
 * True when both the magnetic and true headings have moved less than [epsilonDeg] — i.e.
 * the live state is "settled" and a re-emission would only be sub-degree jitter.
 *
 * NaN handling is load-bearing: `angularDistance` returns NaN whenever an input is NaN,
 * and `NaN < epsilon` is false, so ANY transition into or out of "unknown" (NaN) reports
 * NOT settled and therefore forces an emit. That is exactly what the NaN="unknown heading"
 * invariant needs — a reading dropping to unknown must surface as "---", never be suppressed
 * into a stale real bearing, and a first real reading after unknown must surface immediately.
 */
fun headingSettled(
    oldMag: Float, newMag: Float,
    oldTrue: Float, newTrue: Float,
    epsilonDeg: Float,
): Boolean =
    angularDistance(oldMag, newMag) < epsilonDeg &&
        angularDistance(oldTrue, newTrue) < epsilonDeg

/**
 * Corrects a raw sensor azimuth for how the phone is mounted on the bike. The
 * rotation-vector azimuth reflects the phone's own yaw, which is only the bike's
 * direction of travel if the phone happens to be aligned with it; [offsetDegrees]
 * is the fixed angle between the two, so corrected = raw − offset, normalized to
 * [0, 360).
 *
 * [Float.NaN] (no heading yet / no sensor) passes through unchanged — a missing
 * reading must stay "unknown", never collapse to 0° (due north).
 */
fun applyMountingOffset(rawDegrees: Float, offsetDegrees: Float): Float =
    if (rawDegrees.isNaN()) Float.NaN
    else ((rawDegrees - offsetDegrees) % 360f + 360f) % 360f
