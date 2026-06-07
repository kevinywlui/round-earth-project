package com.roundearth.bikecomputer.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Tracks compass heading (azimuth) from the device's rotation-vector sensor.
 *
 * Heading is degrees clockwise from magnetic north [0, 360). Used both for the
 * live bearing and to stamp each recorded revolution so northward distance can
 * be reconstructed later: north = Σ (Δrevs · circumference · cos(heading)).
 *
 * [degrees] is [Float.NaN] until a real reading is available — there is no
 * rotation-vector sensor, or no event has arrived yet. Callers must treat NaN as
 * "unknown" rather than 0° (due north), which would otherwise silently corrupt
 * the recorded heading and the northward-distance reconstruction.
 */
class HeadingProvider(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** True when the device actually has a rotation-vector sensor to read from. */
    val hasSensor: Boolean get() = rotationSensor != null

    @Volatile
    var degrees: Float = Float.NaN
        private set

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        val sensor = rotationSensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        // getOrientation()'s azimuth is only valid for a roughly flat device. A bike
        // computer is clamped near-vertical on the handlebars, where the flat-frame
        // azimuth gimbal-locks. When the device is tilted up past ~45°, remap the axes
        // so the world-frame azimuth stays meaningful (the standard vertical-mount remap).
        val pitchRad = orientation[1]
        if (abs(pitchRad) > Math.PI / 4) {
            SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped,
            )
            SensorManager.getOrientation(remapped, orientation)
        }
        val azimuthRad = orientation[0] // radians, [-π, π], 0 = magnetic north
        degrees = ((Math.toDegrees(azimuthRad.toDouble()).toFloat()) + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
