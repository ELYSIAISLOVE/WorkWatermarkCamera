package com.watermark.camera.data.watermark

import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.OrientationHelper

/**
 * Frozen watermark state at shutter press.
 * Preview and burn-in must both consume the same snapshot.
 */
data class WatermarkSnapshot(
    val config: WatermarkConfig,
    val locationText: String,
    val deviceOrientation: OrientationHelper.DeviceOrientation =
        OrientationHelper.DeviceOrientation.PORTRAIT,
    val capturedAtMs: Long = System.currentTimeMillis()
)
