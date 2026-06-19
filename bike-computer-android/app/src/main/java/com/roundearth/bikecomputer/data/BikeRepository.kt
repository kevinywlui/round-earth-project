package com.roundearth.bikecomputer.data

import com.roundearth.bikecomputer.data.db.RevolutionEvent
import com.roundearth.bikecomputer.data.db.RevolutionEventDao
import com.roundearth.bikecomputer.data.db.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class BikeData(
    val speed: Double,
    val bearingDegrees: Float,
    val trueBearingDegrees: Float,
    val odometer: Double,
    val useImperial: Boolean,
)

class BikeRepository(
    private val source: BikeDataSource,
    private val prefs: PreferencesStore,
    private val dao: RevolutionEventDao,
    private val scope: CoroutineScope,
) {
    /**
     * Identifies the current ride; all events recorded this run share it. The real value is
     * assigned by [resolveSessionId] at the top of [start] before any event is recorded — a
     * recent prior session (e.g. after a mid-ride process restart) is resumed rather than
     * splitting the ride in two. The 0L initializer is an inert placeholder, never observed.
     */
    private var sessionId: Long = 0L
    private var recordingJob: Job? = null

    val connectionState: Flow<ConnectionState> = source.connectionState

    val bikeData: Flow<BikeData> = combine(source.data, prefs.useImperial) { raw, imperial ->
        BikeData(
            speed = if (imperial) raw.speedKph * 0.621371 else raw.speedKph,
            bearingDegrees = raw.bearingDegrees,
            trueBearingDegrees = raw.trueBearingDegrees,
            odometer = if (imperial) raw.odometerKm * 0.621371 else raw.odometerKm,
            useImperial = imperial,
        )
    }

    val recordedEventCount: Flow<Int> = dao.observeCount()
    val sessions: Flow<List<SessionSummary>> = dao.observeSessions()

    /** Starts the data source and persists every revolution as time-series data. */
    fun start() {
        if (recordingJob == null) {
            recordingJob = scope.launch {
                // Resolve the session and seed the live odometer before collecting, so a
                // resumed ride continues from its prior distance instead of zero. The seed is
                // non-zero only when resolveSessionId() resumed a prior session (a restart
                // within SESSION_RESUME_WINDOW_MS); that constant is the single knob deciding
                // whether a mid-ride restart continues the odometer or zeroes it.
                sessionId = resolveSessionId()
                source.seedOdometer(dao.sessionDistanceMeters(sessionId) ?: 0.0)
                source.revolutionReadings.collect { reading ->
                    dao.insert(
                        RevolutionEvent(
                            sessionId = sessionId,
                            timestampMillis = reading.timestampMillis,
                            cumulativeRevolutions = reading.cumulativeRevolutions,
                            deltaRevolutions = reading.deltaRevolutions,
                            sensorEventTime1024 = reading.sensorEventTime1024,
                            cumulativeEventTime1024 = reading.cumulativeEventTime1024,
                            wheelCircumferenceM = reading.wheelCircumferenceM,
                            // Map the in-memory NaN="unknown" heading to a NULL column: a bound
                            // NaN becomes NULL in SQLite and would fail the NOT NULL constraint.
                            headingDegrees = reading.headingDegrees.takeUnless { it.isNaN() },
                            trueHeadingDegrees = reading.trueHeadingDegrees.takeUnless { it.isNaN() },
                        )
                    )
                }
            }
        }
        source.start()
    }

    /**
     * Stops the data source (scan/connections) when collection ends. The recording job is left
     * intact, so a later [start] resumes the same session rather than re-seeding from zero.
     */
    fun onBackground() = source.stop()

    /** A recent prior session is resumed; otherwise a new one begins now. */
    private suspend fun resolveSessionId(): Long {
        val now = System.currentTimeMillis()
        val last = dao.lastEvent()
        return if (last != null && now - last.timestampMillis <= SESSION_RESUME_WINDOW_MS) {
            last.sessionId
        } else {
            now
        }
    }

    /**
     * Streams all recorded events as CSV into [out] (one page at a time) for
     * export/analysis. Avoids materializing the whole table or one giant String,
     * which can OOM on long histories.
     */
    suspend fun exportCsvTo(out: Appendable) {
        out.append(CSV_HEADER)
        var afterId = 0L
        while (true) {
            val page = dao.getPageAfter(afterId, EXPORT_PAGE_SIZE)
            if (page.isEmpty()) break
            for (e in page) {
                out.append(e.sessionId.toString()).append(',')
                out.append(e.timestampMillis.toString()).append(',')
                out.append(e.cumulativeRevolutions.toString()).append(',')
                out.append(e.deltaRevolutions.toString()).append(',')
                out.append(e.sensorEventTime1024.toString()).append(',')
                out.append(e.cumulativeEventTime1024.toString()).append(',')
                out.append(e.wheelCircumferenceM.toString()).append(',')
                // Unknown heading is an empty CSV field (NULL), not "NaN" or "0".
                out.append(e.headingDegrees?.toString() ?: "").append(',')
                out.append(e.trueHeadingDegrees?.toString() ?: "").append('\n')
                afterId = e.id
            }
        }
    }

    suspend fun clearHistory() = dao.deleteAll()

    companion object {
        private const val CSV_HEADER =
            "session_id,timestamp_ms,cumulative_revolutions,delta_revolutions," +
                "sensor_event_time_1024,cumulative_event_time_1024,wheel_circumference_m," +
                "heading_degrees,true_heading_degrees\n"
        private const val EXPORT_PAGE_SIZE = 1_000
        // Resume the prior ride if the app restarts within this window of the last event.
        private const val SESSION_RESUME_WINDOW_MS = 30 * 60 * 1000L
    }
}
