package com.roundearth.bikecomputer.data

data class RawBikeData(
    val speedKph: Double,
    val cadenceRpm: Double,
    /** Heading clockwise from magnetic north [0, 360). */
    val bearingDegrees: Float,
    /** Heading clockwise from true (geographic) north [0, 360) = magnetic + declination. */
    val trueBearingDegrees: Float,
    val odometerKm: Double,
)
