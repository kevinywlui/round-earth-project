package com.roundearth.bikecomputer.ui

import com.roundearth.bikecomputer.data.ConnectionState
import java.util.Locale

data class BikeUiState(
    val speed: Double = 0.0,
    val cadenceRpm: Double = 0.0,
    /** Heading clockwise from magnetic north [0, 360), or NaN when unknown. */
    val bearingDegrees: Float = Float.NaN,
    /** Heading clockwise from true (geographic) north [0, 360), or NaN when unknown. */
    val trueBearingDegrees: Float = Float.NaN,
    val odometer: Double = 0.0,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val useImperial: Boolean = false,
) {
    val speedLabel: String get() = if (useImperial) "MPH" else "KM/H"
    val distanceLabel: String get() = if (useImperial) "MI" else "KM"

    val bearingCardinal: String get() = cardinal(bearingDegrees)
    val trueBearingCardinal: String get() = cardinal(trueBearingDegrees)

    /** Zero-padded integer degrees in [000, 359], or "---" when the heading is unknown. */
    val bearingDegreesText: String get() = degreesText(bearingDegrees)
    val trueBearingDegreesText: String get() = degreesText(trueBearingDegrees)
}

/** Eight-point compass label for a heading in degrees ("--" when unknown). */
private fun cardinal(degrees: Float): String {
    if (degrees.isNaN()) return "--"
    val index = ((degrees + 22.5f).toInt() % 360) / 45
    return listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").getOrElse(index) { "N" }
}

/** Heading rounded to an integer in [0, 359] (so 359.6 reads 000, never 360). */
private fun degreesText(degrees: Float): String =
    if (degrees.isNaN()) "---°" else String.format(Locale.US, "%03d°", Math.round(degrees) % 360)
