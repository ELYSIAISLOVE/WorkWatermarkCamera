package com.watermark.camera.data.watermark

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * Coordinate mapping between preview pixels, normalized percent (0~1), and photo pixels.
 * Accounts for Aspect Fill / center-crop on PreviewView.
 */
class CoordinateMapper {

    data class ViewportInfo(
        val viewWidth: Int,
        val viewHeight: Int,
        val visibleRect: Rect,
        val isCroppedHorizontal: Boolean
    )

    /**
     * Compute the preview visible region given view size and captured photo size.
     * Matches PreviewView scaleType fillCenter behavior.
     */
    fun calculatePreviewViewport(
        viewWidth: Int,
        viewHeight: Int,
        photoWidth: Int,
        photoHeight: Int
    ): ViewportInfo {
        if (viewWidth <= 0 || viewHeight <= 0 || photoWidth <= 0 || photoHeight <= 0) {
            return ViewportInfo(
                viewWidth = viewWidth.coerceAtLeast(1),
                viewHeight = viewHeight.coerceAtLeast(1),
                visibleRect = Rect(0, 0, viewWidth.coerceAtLeast(1), viewHeight.coerceAtLeast(1)),
                isCroppedHorizontal = false
            )
        }
        val photoRatio = photoWidth.toFloat() / photoHeight
        val viewRatio = viewWidth.toFloat() / viewHeight

        return if (photoRatio > viewRatio) {
            // Photo wider → crop left/right in the content coordinate space
            val visibleHeight = viewHeight
            val visibleWidth = (viewHeight * photoRatio).toInt().coerceAtLeast(1)
            val cropLeft = (visibleWidth - viewWidth) / 2
            ViewportInfo(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                visibleRect = Rect(-cropLeft, 0, viewWidth + cropLeft, viewHeight),
                isCroppedHorizontal = true
            )
        } else {
            val visibleWidth = viewWidth
            val visibleHeight = (viewWidth / photoRatio).toInt().coerceAtLeast(1)
            val cropTop = (visibleHeight - viewHeight) / 2
            ViewportInfo(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                visibleRect = Rect(0, -cropTop, viewWidth, viewHeight + cropTop),
                isCroppedHorizontal = false
            )
        }
    }

    fun pixelToPercent(pixelX: Float, pixelY: Float, viewport: ViewportInfo): Pair<Float, Float> {
        val w = viewport.visibleRect.width().toFloat().coerceAtLeast(1f)
        val h = viewport.visibleRect.height().toFloat().coerceAtLeast(1f)
        val pctX = (pixelX - viewport.visibleRect.left) / w
        val pctY = (pixelY - viewport.visibleRect.top) / h
        return pctX.coerceIn(0f, 1f) to pctY.coerceIn(0f, 1f)
    }

    fun percentToPixel(pctX: Float, pctY: Float, viewport: ViewportInfo): Pair<Float, Float> {
        val w = viewport.visibleRect.width().toFloat().coerceAtLeast(1f)
        val h = viewport.visibleRect.height().toFloat().coerceAtLeast(1f)
        val pixelX = pctX * w + viewport.visibleRect.left
        val pixelY = pctY * h + viewport.visibleRect.top
        return pixelX to pixelY
    }

    /**
     * Map normalized percent (of full photo) to photo pixel coordinates for card top-left.
     * [cardW]/[cardH] are in photo pixels; result is clamped so the card stays on-image.
     */
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
        val left = pctX.coerceIn(0f, 1f) * maxL
        val top = pctY.coerceIn(0f, 1f) * maxT
        return left to top
    }

    /**
     * Scale a size defined as fraction of the shorter photo side.
     */
    fun scaleByShortSide(photoW: Int, photoH: Int, fraction: Float): Float {
        val shortSide = min(photoW, photoH).toFloat()
        return shortSide * fraction.coerceIn(0.01f, 1f)
    }
}
