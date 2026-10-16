package com.compass.vault.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Turns the magnetometer into a smooth compass heading.
 *
 * Two sources are supported:
 *  1. `TYPE_ROTATION_VECTOR` - the fused sensor, accurate and already
 *     gravity compensated. This is what modern phones use.
 *  2. `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` - the classic pair, used
 *     as a fallback when the fused sensor is missing.
 */
class CompassSensor(context: Context) : SensorEventListener {

    companion object {
        /** How much of the difference is applied per sample (0..1). */
        private const val SMOOTHING = 0.16f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** True when the device has any usable magnetometer. */
    val isAvailable: Boolean
        get() = rotationVector != null || (accelerometer != null && magnetometer != null)

    /** Called with the smoothed heading in degrees (0..360). */
    var onHeading: ((Float) -> Unit)? = null

    /** Called with the sensor accuracy (unreliable -> low -> medium -> high). */
    var onAccuracy: ((Int) -> Unit)? = null

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var smoothed = 0f
    private var hasValue = false

    private var running = false

    fun start() {
        // Registering twice would deliver every sensor event twice, so the
        // call is idempotent.
        if (running) return
        running = true
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            return@let
        }
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        onAccuracy?.invoke(accuracy)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                publish()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity)
                hasGravity = true
                computeFromGravityAndMagnetic()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, geomagnetic)
                hasGeomagnetic = true
                computeFromGravityAndMagnetic()
            }
        }
    }

    private fun computeFromGravityAndMagnetic() {
        if (!hasGravity || !hasGeomagnetic) return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            publish()
        }
    }

    /**
     * Converts the rotation matrix into a heading in degrees and publishes
     * the smoothed value.
     *
     * The device is assumed to be held in portrait; the X axis is remapped
     * so the heading is the direction the top of the phone points at.
     */
    private fun publish() {
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedMatrix
        )
        SensorManager.getOrientation(remappedMatrix, orientation)

        var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
        degrees = ((degrees % 360f) + 360f) % 360f

        if (!hasValue) {
            smoothed = degrees
            hasValue = true
        } else {
            // Smooth across the 0/360 boundary as well.
            smoothed += angleDifference(degrees, smoothed) * SMOOTHING
            smoothed = ((smoothed % 360f) + 360f) % 360f
        }
        onHeading?.invoke(smoothed)
    }

    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = if (i < 3) output[i] * 0.8f + input[i] * 0.2f else input[i]
        }
    }

    private fun angleDifference(a: Float, b: Float): Float {
        var diff = (a - b) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }
}
