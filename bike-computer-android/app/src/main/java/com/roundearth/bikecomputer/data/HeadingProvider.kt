package com.roundearth.bikecomputer.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Tracks compass heading (azimuth) from the device's rotation-vector sensor.
 *
 * Heading is degrees clockwise from magnetic north [0, 360). Used both for the
 * live bearing and to stamp each recorded revolution so northward distance can
 * be reconstructed later: north = Σ (Δrevs · circumference · cos(heading)).
 */
class HeadingProvider(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    var degrees: Float = 0f
        private set

    private val rotationMatrix = FloatArray(9)
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
        val azimuthRad = orientation[0] // radians, [-π, π], 0 = magnetic north
        degrees = ((Math.toDegrees(azimuthRad.toDouble()).toFloat()) + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
