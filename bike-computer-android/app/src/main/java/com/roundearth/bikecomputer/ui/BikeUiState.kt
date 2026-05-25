package com.roundearth.bikecomputer.ui

import com.roundearth.bikecomputer.data.ConnectionState

data class BikeUiState(
    val speed: Double = 0.0,
    val cadenceRpm: Double = 0.0,
    val bearingDegrees: Float = 0f,
    val odometer: Double = 0.0,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val useImperial: Boolean = false,
) {
    val speedLabel: String get() = if (useImperial) "MPH" else "KM/H"
    val distanceLabel: String get() = if (useImperial) "MI" else "KM"

    val bearingCardinal: String
        get() {
            val index = ((bearingDegrees + 22.5).toInt() % 360) / 45
            return listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").getOrElse(index) { "N" }
        }
}
