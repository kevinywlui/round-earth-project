package com.roundearth.bikecomputer.data

/**
 * A single raw wheel-revolution notification from the CSC sensor, before it is
 * stamped with a session id and persisted. Carries everything needed to derive
 * speed/distance later, so storage stays lossless.
 */
data class WheelRevolutionReading(
    val timestampMillis: Long,
    val cumulativeRevolutions: Long,
    val sensorEventTime1024: Int,
    val wheelCircumferenceM: Double,
    val headingDegrees: Float,
)
