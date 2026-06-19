package com.roundearth.bikecomputer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS location fix, sampled periodically while collecting. Optional ANCHORING data for the
 * dead-reckoned trajectory: a fix lets a consumer estimate and subtract the compass heading bias
 * (the dominant 2-D error) over a straight segment, and bounds the absolute drift that pure
 * dead-reckoning accumulates.
 *
 * Kept in its OWN table and exported as a SEPARATE download — raw coordinates are sensitive and are
 * deliberately not bundled with the ride-telemetry export. The dead-reckoning model never depends on
 * these rows; they are a correction aid only.
 */
@Entity(tableName = "gps_fixes", indices = [Index("timestampMillis")])
data class GpsFix(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock of the fix (epoch millis). */
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    /**
     * Horizontal accuracy radius in meters (smaller is better), or NULL when the provider didn't
     * report one. Nullable on purpose: a fix can lack accuracy, and SQLite cannot store a Float.NaN
     * (it coerces to NULL), so a NOT NULL column would throw at insert for an accuracy-less fix.
     */
    val accuracyMeters: Float?,
    /** Altitude in meters, or NULL if the provider didn't report one. */
    val altitudeMeters: Double?,
    /** Course over ground in degrees [0,360), or NULL — a GPS bearing to compare against the compass. */
    val bearingDegrees: Float?,
    /** Ground speed in m/s, or NULL. */
    val speedMps: Float?,
)
