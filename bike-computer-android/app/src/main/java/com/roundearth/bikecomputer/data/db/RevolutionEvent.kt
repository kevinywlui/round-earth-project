package com.roundearth.bikecomputer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One raw wheel-revolution event, exactly as reported by the CSC sensor.
 *
 * Stored losslessly so rides can be re-analyzed later: speed and distance are
 * derivable from the cumulative count, the sensor event time, and the configured
 * wheel circumference. Nothing is pre-aggregated.
 */
@Entity(tableName = "revolution_events", indices = [Index("sessionId")])
data class RevolutionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Ride/session this event belongs to (session start, epoch millis). */
    val sessionId: Long,
    /** Wall-clock time the notification was received (epoch millis). */
    val timestampMillis: Long,
    /** Cumulative wheel revolutions reported by the sensor (monotonic). */
    val cumulativeRevolutions: Long,
    /**
     * Revolutions this event advanced the wheel by, reboot/rollover-safe (0 at a
     * baseline reset). Summing this is correct across sensor reboots, unlike
     * MAX-MIN of [cumulativeRevolutions].
     */
    val deltaRevolutions: Long = 0,
    /** Sensor's "last wheel event time" in 1/1024 s units (16-bit, wraps at 64 s). */
    val sensorEventTime1024: Int,
    /** Wheel circumference in meters in effect when this event was recorded. */
    val wheelCircumferenceM: Double,
    /**
     * Compass heading clockwise from magnetic north [0, 360) at this event, or
     * [Float.NaN] if unknown. NaN means "unknown" and must never be coerced to 0f
     * (= due north) — no default, so every writer is forced to supply a value.
     */
    val headingDegrees: Float,
    /**
     * Heading clockwise from true (geographic) north [0, 360) = magnetic + declination,
     * or [Float.NaN] if unknown. Same NaN="unknown, never 0f/north" invariant as
     * [headingDegrees]; no default for the same reason.
     */
    val trueHeadingDegrees: Float,
)
