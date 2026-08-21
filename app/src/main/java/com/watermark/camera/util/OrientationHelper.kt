package com.watermark.camera.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.watermark.camera.util.Logger

/**
 * Helper class for detecting device orientation using accelerometer.
 *
 * Provides adaptive watermark positioning based on device rotation:
 * - Portrait (0°): watermark at bottom
 * - Landscape Left (90°): watermark at right side
 * - Landscape Right (270°/-90°): watermark at left side
 * - Upside Down (180°): watermark at top
 *
 * Uses accelerometer gravity vector for orientation detection.
 * Does not require magnetic field sensor (simpler, no calibration needed).
 */
class OrientationHelper(context: Context) {

    companion object {
        private const val TAG = "OrientationHelper"
        /** Threshold for determining orientation change (in G). */
        private const val TILT_THRESHOLD = 0.7f
        /** Minimum time between orientation updates (ms). */
        private const val UPDATE_INTERVAL_MS = 500L
    }

    /**
     * Device orientation states.
     */
    enum class DeviceOrientation {
        PORTRAIT,           // Normal upright (0°)
        LANDSCAPE_LEFT,     // Rotated 90° clockwise
        LANDSCAPE_RIGHT,    // Rotated 90° counter-clockwise
        UPSIDE_DOWN,        // Rotated 180°
        UNKNOWN             // Flat or indeterminate
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastUpdateTime = 0L
    private var currentOrientation = DeviceOrientation.PORTRAIT

    private var listener: ((DeviceOrientation) -> Unit)? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return
            lastUpdateTime = now

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val newOrientation = calculateOrientation(x, y, z)
            if (newOrientation != currentOrientation && newOrientation != DeviceOrientation.UNKNOWN) {
                currentOrientation = newOrientation
                Logger.i(TAG, "Orientation changed: $currentOrientation")
                listener?.invoke(currentOrientation)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Calculate orientation from accelerometer gravity vector.
     *
     * Logic:
     * - |z| > threshold: device is flat (UNKNOWN)
     * - |y| > |x|: portrait mode (y > 0 = upright, y < 0 = upside down)
     * - |x| > |y|: landscape mode (x > 0 = left tilt, x < 0 = right tilt)
     */
    private fun calculateOrientation(x: Float, y: Float, z: Float): DeviceOrientation {
        // If device is nearly flat, return unknown
        if (kotlin.math.abs(z) > TILT_THRESHOLD * SensorManager.GRAVITY_EARTH) {
            return DeviceOrientation.UNKNOWN
        }

        return when {
            kotlin.math.abs(y) > kotlin.math.abs(x) -> {
                if (y > 0) DeviceOrientation.PORTRAIT else DeviceOrientation.UPSIDE_DOWN
            }
            else -> {
                if (x > 0) DeviceOrientation.LANDSCAPE_LEFT else DeviceOrientation.LANDSCAPE_RIGHT
            }
        }
    }

    /**
     * Start listening for orientation changes.
     *
     * @param onOrientationChanged Callback invoked when orientation changes.
     */
    fun startListening(onOrientationChanged: (DeviceOrientation) -> Unit) {
        listener = onOrientationChanged
        if (accelerometer != null) {
            sensorManager.registerListener(
                sensorListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
            Logger.i(TAG, "Orientation sensor registered")
        } else {
            Logger.w(TAG, "Accelerometer not available")
        }
    }

    /**
     * Stop listening for orientation changes.
     */
    fun stopListening() {
        sensorManager.unregisterListener(sensorListener)
        listener = null
        Logger.i(TAG, "Orientation sensor unregistered")
    }

    /**
     * Get current device orientation.
     */
    fun getCurrentOrientation(): DeviceOrientation = currentOrientation
}
