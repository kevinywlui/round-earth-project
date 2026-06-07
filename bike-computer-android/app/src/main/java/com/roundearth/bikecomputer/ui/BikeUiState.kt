package com.roundearth.bikecomputer.ui

import com.roundearth.bikecomputer.data.ConnectionState

data class BikeUiState(
    val speed: Double = 0.0,
    val cadenceRpm: Double = 0.0,
    /** Heading clockwise from magnetic north [0, 360). */
    val bearingDegrees: Float = 0f,
    /** Heading clockwise from true (geographic) north [0, 360). */
    val trueBearingDegrees: Float = 0f,
    val odometer: Double = 0.0,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val useImperial: Boolean = false,
) {
    val speedLabel: String get() = if (useImperial) "MPH" else "KM/H"
    val distanceLabel: String get() = if (useImperial) "MI" else "KM"

    val bearingCardinal: String get() = cardinal(bearingDegrees)
    val trueBearingCardinal: String get() = cardinal(trueBearingDegrees)
}

/** Eight-point compass label for a heading in degrees. */
fun cardinal(degrees: Float): String {
    val index = ((degrees + 22.5).toInt() % 360) / 45
    return listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").getOrElse(index) { "N" }
}
