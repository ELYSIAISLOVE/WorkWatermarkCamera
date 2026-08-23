package com.watermark.camera.data.watermark

import android.graphics.Rect
import kotlin.math.min

/**
 * Maps between preview pixels, normalized [0,1], and photo pixels under fillCenter crop.
 */
class CoordinateMapper {

    data class ViewportInfo(
        val viewWidth: Int,
        val viewHeight: Int,
        val visibleRect: Rect,
        val isCroppedHorizontal: Boolean
    )

    fun calculatePreviewViewport(
        viewWidth: Int,
        viewHeight: Int,
        photoWidth: Int,
        photoHeight: Int
    ): ViewportInfo {
        if (viewWidth <= 0 || viewHeight <= 0 || photoWidth <= 0 || photoHeight <= 0) {
            val w = viewWidth.coerceAtLeast(1)
            val h = viewHeight.coerceAtLeast(1)
            return ViewportInfo(w, h, Rect(0, 0, w, h), false)
        }
        val photoRatio = photoWidth.toFloat() / photoHeight
        val viewRatio = viewWidth.toFloat() / viewHeight
        return if (photoRatio > viewRatio) {
            val visibleWidth = (viewHeight * photoRatio).toInt().coerceAtLeast(1)
            val cropLeft = (visibleWidth - viewWidth) / 2
            ViewportInfo(
                viewWidth, viewHeight,
                Rect(-cropLeft, 0, viewWidth + cropLeft, viewHeight),
                true
            )
        } else {
            val visibleHeight = (viewWidth / photoRatio).toInt().coerceAtLeast(1)
            val cropTop = (visibleHeight - viewHeight) / 2
            ViewportInfo(
                viewWidth, viewHeight,
                Rect(0, -cropTop, viewWidth, viewHeight + cropTop),
                false
            )
        }
    }

    fun percentToPhotoOrigin(
        pctX: Float,
        pctY: Float,
        photoW: Int,
        photoH: Int,
        cardW: Float,
        cardH: Float
    ): Pair<Float, Float> {
        val maxL = (photoW - cardW).coerceAtLeast(0f)
        val maxT = (photoH - cardH).coerceAtLeast(0f)
        return pctX.coerceIn(0f, 1f) * maxL to pctY.coerceIn(0f, 1f) * maxT
    }

    fun scaleByShortSide(photoW: Int, photoH: Int, fraction: Float): Float {
        return min(photoW, photoH).toFloat() * fraction.coerceIn(0.01f, 1f)
    }
}
