package com.watermark.camera.data.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.Logger
import com.watermark.camera.util.OrientationHelper

/**
 * Photo burn-in. Same [WatermarkRenderer] as live preview.
 * [capturedAtMs] must be shutter time so clock matches the freeze.
 */
class WatermarkCanvas {

    companion object {
        private const val TAG = "WatermarkCanvas"
    }

    private val renderer = WatermarkRenderer()

    fun drawWatermark(
        sourceBitmap: Bitmap,
        config: WatermarkConfig,
        locationStr: String = "",
        deviceOrientation: OrientationHelper.DeviceOrientation =
            OrientationHelper.DeviceOrientation.PORTRAIT,
        capturedAtMs: Long = System.currentTimeMillis()
    ): Bitmap {
        return drawWatermark(
            sourceBitmap,
            WatermarkSnapshot(
                config = config,
                locationText = locationStr,
                deviceOrientation = deviceOrientation,
                capturedAtMs = capturedAtMs
            )
        )
    }

    fun drawWatermark(sourceBitmap: Bitmap, snapshot: WatermarkSnapshot): Bitmap {
        val resultBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val metrics = renderer.draw(
            canvas = canvas,
            areaWidth = resultBitmap.width.toFloat(),
            areaHeight = resultBitmap.height.toFloat(),
            config = snapshot.config,
            locationText = snapshot.locationText,
            deviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT,
            timeMs = snapshot.capturedAtMs
        )
        if (metrics != null) {
            Logger.i(
                TAG,
                "Watermark: ${metrics.lines.size} lines font=${metrics.fontSize.toInt()} " +
                    "${resultBitmap.width}x${resultBitmap.height}"
            )
        }
        return resultBitmap
    }

    /**
     * Estimate watermark card size for preview layout / ApplyWatermarkUseCase.
     * Uses the same measure path as draw so proportions stay consistent.
     */
    fun estimateDimensions(
        photoWidth: Int,
        config: WatermarkConfig,
        locationStr: String = ""
    ): Pair<Int, Int> {
        val w = photoWidth.coerceAtLeast(1).toFloat()
        // Approximate portrait area when only width is known
        val h = w * 4f / 3f
        val m = renderer.measure(
            areaWidth = w,
            areaHeight = h,
            config = config,
            locationText = locationStr
        ) ?: return 0 to 0
        return m.width.toInt() to m.height.toInt()
    }
}
