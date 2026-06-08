package com.roundearth.bikecomputer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal in-test [BikeDataSource] so Tier-1 persistence tests can drive the
 * exact [WheelRevolutionReading]s that flow through [BikeRepository.start]'s
 * collect loop into the DAO — including the NaN heading that must become NULL.
 *
 * [revolutionReadings] is a no-replay SharedFlow so each emit reaches the
 * repository's collector once the test advances the scheduler. [seededMeters]
 * records the value [BikeRepository] reads back from the DB to continue a ride.
 */
class FakeBikeDataSource : BikeDataSource {
    override val data: Flow<RawBikeData> = MutableStateFlow(
        RawBikeData(speedKph = 0.0, bearingDegrees = 0f, trueBearingDegrees = 0f, odometerKm = 0.0)
    )
    val readings = MutableSharedFlow<WheelRevolutionReading>(extraBufferCapacity = 64)
    override val revolutionReadings: Flow<WheelRevolutionReading> = readings
    override val connectionState: Flow<ConnectionState> =
        MutableStateFlow(ConnectionState.DISCONNECTED)

    var seededMeters: Double? = null
        private set
    var started = false
        private set

    override fun start() { started = true }
    override fun stop() {}
    override fun seedOdometer(meters: Double) { seededMeters = meters }
}
