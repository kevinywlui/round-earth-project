package com.roundearth.bikecomputer.data

import com.roundearth.bikecomputer.data.db.RevolutionEvent
import com.roundearth.bikecomputer.data.db.RevolutionEventDao
import com.roundearth.bikecomputer.data.db.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
    private val dao: RevolutionEventDao,
    private val scope: CoroutineScope,
) {
    /** Identifies the current ride; all events recorded this run share it. */
    private val sessionId: Long = System.currentTimeMillis()
    private var started = false

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

    val recordedEventCount: Flow<Int> = dao.observeCount()
    val sessions: Flow<List<SessionSummary>> = dao.observeSessions()

    /** Starts the data source and persists every revolution as time-series data. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            source.revolutionReadings.collect { reading ->
                dao.insert(
                    RevolutionEvent(
                        sessionId = sessionId,
                        timestampMillis = reading.timestampMillis,
                        cumulativeRevolutions = reading.cumulativeRevolutions,
                        sensorEventTime1024 = reading.sensorEventTime1024,
                        wheelCircumferenceM = reading.wheelCircumferenceM,
                        headingDegrees = reading.headingDegrees,
                    )
                )
            }
        }
        source.start()
    }

    /** Renders all recorded events as CSV for export/analysis. */
    suspend fun exportCsv(): String = buildString {
        append("session_id,timestamp_ms,cumulative_revolutions,sensor_event_time_1024,wheel_circumference_m,heading_degrees\n")
        for (e in dao.getAll()) {
            append(e.sessionId).append(',')
            append(e.timestampMillis).append(',')
            append(e.cumulativeRevolutions).append(',')
            append(e.sensorEventTime1024).append(',')
            append(e.wheelCircumferenceM).append(',')
            append(e.headingDegrees).append('\n')
        }
    }

    suspend fun clearHistory() = dao.deleteAll()
}
