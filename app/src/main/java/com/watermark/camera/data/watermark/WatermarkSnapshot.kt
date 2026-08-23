package com.watermark.camera.data.watermark

import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.OrientationHelper

/**
 * Frozen watermark state at shutter moment.
 * Ensures continuous shooting uses the position/text visible at capture time,
 * not a later drag or config change.
 */
data class WatermarkSnapshot(
    val config: WatermarkConfig,
    val locationText: String,
    val deviceOrientation: OrientationHelper.DeviceOrientation =
        OrientationHelper.DeviceOrientation.PORTRAIT,
    val capturedAtMs: Long = System.currentTimeMillis()
)
