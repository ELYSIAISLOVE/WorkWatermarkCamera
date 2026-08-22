package com.watermark.camera.data.watermark

import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition

/**
 * Shared placement for preview + saved JPEG.
 */
object WatermarkLayout {

    fun cardOrigin(
        config: WatermarkConfig,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float
    ): Pair<Float, Float> {
        val m = margin.coerceAtLeast(0f)
        val maxL = (areaWidth - cardWidth - m).coerceAtLeast(m)
        val maxT = (areaHeight - cardHeight - m).coerceAtLeast(m)
        val spanX = (maxL - m).coerceAtLeast(1f)
        val spanY = (maxT - m).coerceAtLeast(1f)

        val cx = config.customX
        val cy = config.customY
        if (cx != null && cy != null) {
            val left = (m + cx.coerceIn(0f, 1f) * spanX).coerceIn(m, maxL)
            val top = (m + cy.coerceIn(0f, 1f) * spanY).coerceIn(m, maxT)
            return left to top
        }

        return when (config.position) {
            WatermarkPosition.TOP_LEFT -> m to m
            WatermarkPosition.TOP_RIGHT -> maxL to m
            WatermarkPosition.BOTTOM_LEFT -> m to maxT
            WatermarkPosition.BOTTOM_RIGHT -> maxL to maxT
            WatermarkPosition.CENTER ->
                ((areaWidth - cardWidth) / 2f).coerceAtLeast(0f) to
                    ((areaHeight - cardHeight) / 2f).coerceAtLeast(0f)
        }
    }

    fun clampOrigin(
        left: Float,
        top: Float,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float
    ): Pair<Float, Float> {
        val m = margin.coerceAtLeast(0f)
        val maxL = (areaWidth - cardWidth - m).coerceAtLeast(m)
        val maxT = (areaHeight - cardHeight - m).coerceAtLeast(m)
        return left.coerceIn(m, maxL) to top.coerceIn(m, maxT)
    }

    fun toNormalized(
        left: Float,
        top: Float,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float
    ): Pair<Float, Float> {
        val m = margin.coerceAtLeast(0f)
        val maxL = (areaWidth - cardWidth - m).coerceAtLeast(m)
        val maxT = (areaHeight - cardHeight - m).coerceAtLeast(m)
        val spanX = (maxL - m).coerceAtLeast(1f)
        val spanY = (maxT - m).coerceAtLeast(1f)
        val nx = ((left - m) / spanX).coerceIn(0f, 1f)
        val ny = ((top - m) / spanY).coerceIn(0f, 1f)
        return nx to ny
    }

    fun nearestSlot(x: Float, y: Float, areaWidth: Float, areaHeight: Float): WatermarkPosition {
        val left = x < areaWidth / 2f
        val top = y < areaHeight / 2f
        val nearCenter =
            kotlin.math.abs(x - areaWidth / 2f) < areaWidth * 0.15f &&
                kotlin.math.abs(y - areaHeight / 2f) < areaHeight * 0.15f
        if (nearCenter) return WatermarkPosition.CENTER
        return when {
            top && left -> WatermarkPosition.TOP_LEFT
            top && !left -> WatermarkPosition.TOP_RIGHT
            !top && left -> WatermarkPosition.BOTTOM_LEFT
            else -> WatermarkPosition.BOTTOM_RIGHT
        }
    }
}
