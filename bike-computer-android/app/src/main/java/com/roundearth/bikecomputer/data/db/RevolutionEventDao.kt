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
               MAX(cumulativeRevolutions) - MIN(cumulativeRevolutions) AS totalRevolutions
        FROM revolution_events
        GROUP BY sessionId
        ORDER BY sessionId DESC
        """
    )
    fun observeSessions(): Flow<List<SessionSummary>>

    /** All events, oldest first — used for CSV export. */
    @Query("SELECT * FROM revolution_events ORDER BY timestampMillis ASC")
    suspend fun getAll(): List<RevolutionEvent>

    @Query("DELETE FROM revolution_events")
    suspend fun deleteAll()
}
