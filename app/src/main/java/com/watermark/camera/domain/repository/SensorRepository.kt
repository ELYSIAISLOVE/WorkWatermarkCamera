package com.watermark.camera.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for device sensors.
 *
 * Manages accelerometer (gyroscope) for adaptive watermark positioning.
 */
interface SensorRepository {

    /**
     * Current device orientation.
     */
    val orientation: StateFlow<DeviceOrientation>

    /**
     * Start listening to sensor events.
     *
     * @param samplingRateUs Sampling rate in microseconds.
     *        Default: 20000us (50Hz) as per PRD requirement.
     */
    fun startListening(samplingRateUs: Int = 20000)

    /**
     * Stop listening to sensor events.
     */
    fun stopListening()

    /**
     * Check if accelerometer is available.
     */
    fun isAccelerometerAvailable(): Boolean

    /**
     * Get the recommended watermark position based on current orientation.
     *
     * @return Watermark position (bottom-left, bottom-right, etc.).
     */
    fun getWatermarkPosition(): WatermarkPosition
}

/**
 * Device orientation based on accelerometer readings.
 */
enum class DeviceOrientation {
    PORTRAIT,           // 0 degrees (normal upright)
    PORTRAIT_INVERTED,  // 180 degrees (upside down)
    LANDSCAPE_LEFT,     // 90 degrees (left side down)
    LANDSCAPE_RIGHT,    // 270 degrees (right side down)
    UNKNOWN             // Transition state or flat
}

/**
 * Watermark position on the photo.
 */
enum class WatermarkPosition {
    BOTTOM_LEFT,    // Default for portrait
    BOTTOM_RIGHT,   // For landscape left
    TOP_LEFT,       // For landscape right (rotated)
    TOP_RIGHT,      // For portrait inverted (rotated)
    CENTER          // Manual override option
}
