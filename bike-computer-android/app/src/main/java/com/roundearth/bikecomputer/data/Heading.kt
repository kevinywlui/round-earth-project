package com.roundearth.bikecomputer.data

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
