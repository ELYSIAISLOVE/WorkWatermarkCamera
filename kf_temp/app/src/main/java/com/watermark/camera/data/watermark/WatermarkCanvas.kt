package com.watermark.camera.data.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.Logger
import com.watermark.camera.util.OrientationHelper

/**
 * Photo burn-in entry. Delegates all drawing to [WatermarkRenderer]
 * so output matches the live overlay for the same config + area math.
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
            OrientationHelper.DeviceOrientation.PORTRAIT
    ): Bitmap {
        return drawWatermark(
            sourceBitmap = sourceBitmap,
            snapshot = WatermarkSnapshot(
                config = config.copy(showLocation = true, fontScale = 2.5f),
                locationText = locationStr,
                deviceOrientation = deviceOrientation,
                capturedAtMs = System.currentTimeMillis()
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
            deviceOrientation = snapshot.deviceOrientation,
            timeMs = snapshot.capturedAtMs
        )
        if (metrics != null) {
            Logger.i(
                TAG,
                "Watermark drawn via unified renderer: ${metrics.lines.size} lines " +
                    "at (${metrics.left.toInt()},${metrics.top.toInt()}) " +
                    "${resultBitmap.width}x${resultBitmap.height}"
            )
        }
        return resultBitmap
    }

    fun estimateDimensions(
        photoWidth: Int,
        config: WatermarkConfig,
        locationStr: String = ""
    ): Pair<Int, Int> {
        val m = renderer.measure(
            areaWidth = photoWidth.toFloat(),
            areaHeight = photoWidth * 4f / 3f,
            config = config,
            locationText = locationStr
        ) ?: return 0 to 0
        return m.width.toInt() to m.height.toInt()
    }
}
