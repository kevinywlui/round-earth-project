package com.roundearth.bikecomputer.data

/**
 * Converts a magnetic heading to a true (geographic) heading by adding the local
 * magnetic declination, normalized back into [0, 360).
 *
 * Declination is positive east: true = magnetic + declination.
 */
fun trueFromMagnetic(magneticDeg: Float, declinationDeg: Float): Float =
    ((magneticDeg + declinationDeg) % 360f + 360f) % 360f
