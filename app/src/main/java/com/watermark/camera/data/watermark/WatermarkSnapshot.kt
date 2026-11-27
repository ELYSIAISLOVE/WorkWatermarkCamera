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
    val capturedAtMs: Long = System.currentTimeMillis(),
    /** Provenance of [capturedAtMs]: "可信时间" / "离线推算 09:14" / "设备时间". */
    val timeBadge: String = ""
)
