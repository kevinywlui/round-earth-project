package com.roundearth.bikecomputer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One per-minute wheel-revolution record recovered from the sensor's on-device flash backlog —
 * the revolutions the live CSC stream could not deliver because the app was disconnected (or the
 * sensor rebooted) at the time. Pulled from the firmware's Backlog GATT service on reconnect.
 *
 * This is the authoritative **per-minute distance timeline**. It is deliberately NOT summed with
 * the live per-revolution model ([RevolutionEvent]); the two are alternative views and a consumer
 * picks exactly one per interval (see ARCHITECTURE.md / docs). Distance for a sensor boot epoch is
 * the span of [cumulativeRevolutions] within one [bootId] × circumference — never MAX−MIN across
 * boots (a reboot resets the counter).
 */
@Entity(
    tableName = "backlog_minutes",
    // (sensorMac, recordIndex) is globally unique forever (recordIndex is the sensor's monotonic,
    // reboot-surviving counter; sensorMac salts it so two different units can't collide). INSERT OR
    // IGNORE on this index makes re-streaming the whole ring on every reconnect idempotent.
    indices = [Index(value = ["sensorMac", "recordIndex"], unique = true)],
)
data class BacklogMinute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** BLE address of the sensor — the idempotency salt so two fresh devices can't collide on ids. */
    val sensorMac: String,
    /** Sensor power-on id; [cumulativeRevolutions] resets to ~0 whenever this changes (a reboot). */
    val bootId: Long,
    /** Sensor's global, reboot-surviving record index — unique per [sensorMac]. */
    val recordIndex: Long,
    /** Sensor uptime (seconds since its boot) when this minute was logged. */
    val uptimeSeconds: Long,
    /** Cumulative wheel revolutions at this minute, within this [bootId]. */
    val cumulativeRevolutions: Long,
    /**
     * Wall-clock (epoch millis) for this minute, back-computed from the connect-time uptime anchor:
     * anchorWallClock − (anchorUptime − [uptimeSeconds]). ESTIMATED, not measured — for records from
     * a prior boot (the dark gap) the off-duration is unknowable, so treat it as a lower bound.
     */
    val wallClockMillis: Long,
    /** Wheel circumference (m) in effect at ingest — stored per-row so a later change never rewrites it. */
    val wheelCircumferenceM: Double,
)
