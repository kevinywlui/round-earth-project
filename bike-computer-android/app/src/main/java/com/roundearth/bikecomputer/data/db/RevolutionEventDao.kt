package com.roundearth.bikecomputer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Summary of one recorded ride. */
data class SessionSummary(
    val sessionId: Long,
    val eventCount: Int,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val totalRevolutions: Long,
)

@Dao
interface RevolutionEventDao {

    @Insert
    suspend fun insert(event: RevolutionEvent)

    @Query("SELECT COUNT(*) FROM revolution_events")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT sessionId,
               COUNT(*) AS eventCount,
               MIN(timestampMillis) AS firstTimestamp,
               MAX(timestampMillis) AS lastTimestamp,
               COALESCE(SUM(deltaRevolutions), 0) AS totalRevolutions
        FROM revolution_events
        GROUP BY sessionId
        ORDER BY sessionId DESC
        """
    )
    fun observeSessions(): Flow<List<SessionSummary>>

    /** Most recent event by wall-clock, used to decide whether to resume a session. */
    @Query("SELECT * FROM revolution_events ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun lastEvent(): RevolutionEvent?

    /** Reboot/rollover-safe distance (meters) recorded for one session. */
    @Query("SELECT SUM(deltaRevolutions * wheelCircumferenceM) FROM revolution_events WHERE sessionId = :sessionId")
    suspend fun sessionDistanceMeters(sessionId: Long): Double?

    /** A page of events with id greater than [afterId], oldest first — for streamed export. */
    @Query("SELECT * FROM revolution_events WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPageAfter(afterId: Long, limit: Int): List<RevolutionEvent>

    @Query("DELETE FROM revolution_events")
    suspend fun deleteAll()
}
