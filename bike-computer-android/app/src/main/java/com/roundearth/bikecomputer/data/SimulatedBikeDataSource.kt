package com.roundearth.bikecomputer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.sin

class SimulatedBikeDataSource(
    private val wheelCircumferenceM: () -> Double = { PreferencesStore.DEFAULT_CIRCUMFERENCE },
    /** Magnetic declination in degrees (positive east); applied to the synthetic bearing. */
    private val declination: () -> Float = { 0f },
) : BikeDataSource {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    override val connectionState = MutableStateFlow(ConnectionState.SIMULATED)
    override val data = MutableStateFlow(RawBikeData(0.0, 0.0, 0f, 0f, 0.0))
    private val _readings = Channel<WheelRevolutionReading>(Channel.UNLIMITED)
    override val revolutionReadings = _readings.receiveAsFlow()

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var time = 0.0
            var bearing = 45f
            var cumulativeRevs = 0L
            var fractionalRevs = 0.0

            while (true) {
                val circ = wheelCircumferenceM()
                val speedKph = (21.5 + 6.5 * sin(time * 0.08)).coerceAtLeast(0.0)
                bearing = (bearing + 0.3f) % 360f
                val trueBearing = trueFromMagnetic(bearing, declination())

                // Advance the wheel by one second of travel, emitting one reading
                // per whole revolution so the stream stays lossless like the sensor.
                val speedMs = speedKph / 3.6
                fractionalRevs += speedMs / circ
                val newWhole = fractionalRevs.toLong()
                while (cumulativeRevs < newWhole) {
                    cumulativeRevs++
                    val now = System.currentTimeMillis()
                    _readings.trySend(
                        WheelRevolutionReading(
                            timestampMillis = now,
                            cumulativeRevolutions = cumulativeRevs,
                            sensorEventTime1024 = ((now * 1024L / 1000L) and 0xFFFF).toInt(),
                            wheelCircumferenceM = circ,
                            headingDegrees = bearing,
                            trueHeadingDegrees = trueBearing,
                        )
                    )
                }

                val wheelRpm = speedMs / circ * 60.0
                data.value = RawBikeData(
                    speedKph = speedKph,
                    cadenceRpm = wheelRpm,
                    bearingDegrees = bearing,
                    trueBearingDegrees = trueBearing,
                    odometerKm = cumulativeRevs * circ / 1000.0,
                )

                time += 1.0
                delay(1_000)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
