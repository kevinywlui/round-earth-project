package com.roundearth.bikecomputer.data

/**
 * A single raw wheel-revolution notification from the CSC sensor, before it is
 * stamped with a session id and persisted. Carries everything needed to derive
 * speed/distance later, so storage stays lossless.
 */
data class WheelRevolutionReading(
    val timestampMillis: Long,
    val cumulativeRevolutions: Long,
    /** Revolutions this event advanced by (reboot/rollover-safe; 0 if unknown). */
    val deltaRevolutions: Long,
    val sensorEventTime1024: Int,
    /** Monotonic accumulated sensor time (1/1024 s) for jitter-free offline timing. */
    val cumulativeEventTime1024: Long,
    val wheelCircumferenceM: Double,
    /** Heading clockwise from magnetic north [0, 360). */
    val headingDegrees: Float,
    /** Heading clockwise from true (geographic) north [0, 360) = magnetic + declination. */
    val trueHeadingDegrees: Float,
)
