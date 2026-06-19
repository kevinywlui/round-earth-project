package com.roundearth.bikecomputer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BacklogMinuteDao {
    /** Idempotent: re-streaming the sensor's whole ring on every reconnect is a no-op for known rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: BacklogMinute)

    /** Batch insert — Room runs a List @Insert in ONE transaction (a whole replayed ring at once). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<BacklogMinute>)

    /** Highest record index already stored for a sensor — lets a future firmware resume from here. */
    @Query("SELECT MAX(recordIndex) FROM backlog_minutes WHERE sensorMac = :sensorMac")
    suspend fun maxRecordIndex(sensorMac: String): Long?

    /** Distance (m) recovered for one sensor boot: span of the cumulative counter within that boot. */
    @Query(
        """
        SELECT (MAX(cumulativeRevolutions) - MIN(cumulativeRevolutions)) * AVG(wheelCircumferenceM)
        FROM backlog_minutes WHERE sensorMac = :sensorMac AND bootId = :bootId
        """
    )
    suspend fun bootDistanceMeters(sensorMac: String, bootId: Long): Double?

    @Query("SELECT * FROM backlog_minutes ORDER BY sensorMac, recordIndex ASC")
    suspend fun all(): List<BacklogMinute>

    @Query("SELECT COUNT(*) FROM backlog_minutes")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM backlog_minutes")
    suspend fun deleteAll()
}

@Dao
interface HeadingMinuteDao {
    /** One row per wall-clock minute; a sticky-service restart within a minute must not duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: HeadingMinute)

    /** True heading for a given wall-clock minute, for the per-minute northward/east join. */
    @Query("SELECT * FROM heading_minutes WHERE minuteEpoch = :minuteEpoch LIMIT 1")
    suspend fun forMinute(minuteEpoch: Long): HeadingMinute?

    @Query("SELECT * FROM heading_minutes ORDER BY minuteEpoch ASC")
    suspend fun all(): List<HeadingMinute>

    @Query("DELETE FROM heading_minutes")
    suspend fun deleteAll()
}

@Dao
interface GpsFixDao {
    @Insert
    suspend fun insert(fix: GpsFix)

    /** Oldest-first, paged for the separate streamed export. */
    @Query("SELECT * FROM gps_fixes WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPageAfter(afterId: Long, limit: Int): List<GpsFix>

    @Query("SELECT COUNT(*) FROM gps_fixes")
    suspend fun count(): Int

    @Query("DELETE FROM gps_fixes")
    suspend fun deleteAll()
}
