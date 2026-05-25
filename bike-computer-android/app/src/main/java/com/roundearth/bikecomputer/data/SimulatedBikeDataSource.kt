package com.roundearth.bikecomputer.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

class SimulatedBikeDataSource : BikeDataSource {

    override val connectionState = MutableStateFlow(ConnectionState.SIMULATED)

    override val data: Flow<RawBikeData> = flow {
        var time = 0.0
        var odometerM = 0.0
        var bearing = 45f

        while (true) {
            val speedKph = (21.5 + 6.5 * sin(time * 0.08)).coerceAtLeast(0.0)
            val cadenceRpm = (80.0 + 12.0 * sin(time * 0.11 + 0.7)).coerceAtLeast(0.0)
            bearing = (bearing + 0.3f) % 360f
            odometerM += speedKph / 3.6  // 1 second of travel at current speed

            emit(
                RawBikeData(
                    speedKph = speedKph,
                    cadenceRpm = cadenceRpm,
                    bearingDegrees = bearing,
                    odometerKm = odometerM / 1000.0,
                )
            )

            time += 1.0
            delay(1_000)
        }
    }
}
