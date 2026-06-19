package com.roundearth.bikecomputer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One per-minute compass heading, sampled app-side (the sensor has no compass). Sampled by an
 * independent ticker while the foreground service runs, so it exists whenever the *phone* is
 * collecting — independent of the BLE connection. Pairs with [BacklogMinute] by wall-clock minute
 * to reconstruct the 2-D displacement vector (N = Σ Δrev·circ·cos θ, E = Σ Δrev·circ·sin θ) at
 * 1-minute resolution for the windows the live per-revolution heading is absent.
 *
 * The NaN→NULL invariant is load-bearing: an unknown heading is NULL here, NEVER 0 (due north),
 * because a minute with revolutions but NULL heading has an UNKNOWN displacement direction that
 * must not be silently read as north. Both magnetic and true are stored so declination stays a
 * recoverable per-row quantity (true − magnetic).
 */
@Entity(
    tableName = "heading_minutes",
    // One row per wall-clock minute; INSERT OR IGNORE makes a sticky-service restart within a
    // minute a no-op rather than a duplicate.
    indices = [Index(value = ["minuteEpoch"], unique = true)],
)
data class HeadingMinute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock minute bucket = floor(timestampMillis / 60000); the join key with [BacklogMinute]. */
    val minuteEpoch: Long,
    /** Representative wall-clock (epoch millis) for the minute. */
    val timestampMillis: Long,
    /** Circular-mean MAGNETIC heading over the minute [0,360), or NULL when no valid sample (never 0). */
    val headingDegrees: Float?,
    /** Circular-mean TRUE heading (= magnetic + declination at write), or NULL. */
    val trueHeadingDegrees: Float?,
    /** Count of valid (non-NaN) compass samples folded into this minute — a confidence/weight signal. */
    val sampleCount: Int,
    /**
     * Worst compass accuracy seen this minute (SensorManager.SENSOR_STATUS_ACCURACY_* ; -1 = unknown).
     * A constant magnetometer bias is the dominant error in 2-D dead-reckoning and is otherwise
     * invisible, so it is recorded per-minute to let a consumer discount low-confidence minutes.
     */
    val compassAccuracy: Int,
)
