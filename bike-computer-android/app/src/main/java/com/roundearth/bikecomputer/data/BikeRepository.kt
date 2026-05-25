package com.roundearth.bikecomputer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class BikeData(
    val speed: Double,
    val cadenceRpm: Double,
    val bearingDegrees: Float,
    val odometer: Double,
    val useImperial: Boolean,
)

class BikeRepository(
    private val source: BikeDataSource,
    private val prefs: PreferencesStore,
) {
    val connectionState: Flow<ConnectionState> = source.connectionState

    val bikeData: Flow<BikeData> = combine(source.data, prefs.useImperial) { raw, imperial ->
        BikeData(
            speed = if (imperial) raw.speedKph * 0.621371 else raw.speedKph,
            cadenceRpm = raw.cadenceRpm,
            bearingDegrees = raw.bearingDegrees,
            odometer = if (imperial) raw.odometerKm * 0.621371 else raw.odometerKm,
            useImperial = imperial,
        )
    }
}
