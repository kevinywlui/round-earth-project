package com.roundearth.bikecomputer.data

data class RawBikeData(
    val speedKph: Double,
    val cadenceRpm: Double,
    val bearingDegrees: Float,
    val odometerKm: Double,
)
