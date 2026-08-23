package com.watermark.camera.data.watermark

import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition

/**
 * Unified placement for preview overlay and saved JPEG.
 * Coordinates: customX/Y in [0,1] map to the full movable range
 * [0 .. areaWidth-cardWidth] x [0 .. areaHeight-cardHeight] so edges are reachable.
 */
object WatermarkLayout {

    fun cardOrigin(
        config: WatermarkConfig,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float = 0f
    ): Pair<Float, Float> {
        val maxL = (areaWidth - cardWidth).coerceAtLeast(0f)
        val maxT = (areaHeight - cardHeight).coerceAtLeast(0f)
        // Optional inset only for enum presets (not free-drag)
        val inset = margin.coerceAtLeast(0f)

        val cx = config.customX
        val cy = config.customY
        if (cx != null && cy != null) {
            val left = (cx.coerceIn(0f, 1f) * maxL).coerceIn(0f, maxL)
            val top = (cy.coerceIn(0f, 1f) * maxT).coerceIn(0f, maxT)
            return left to top
        }

        return when (config.position) {
            WatermarkPosition.TOP_LEFT -> inset to inset
            WatermarkPosition.TOP_RIGHT -> (maxL - inset).coerceAtLeast(0f) to inset
            WatermarkPosition.BOTTOM_LEFT -> inset to (maxT - inset).coerceAtLeast(0f)
            WatermarkPosition.BOTTOM_RIGHT ->
                (maxL - inset).coerceAtLeast(0f) to (maxT - inset).coerceAtLeast(0f)
            WatermarkPosition.CENTER -> (maxL / 2f) to (maxT / 2f)
        }
    }

    fun clampOrigin(
        left: Float,
        top: Float,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float = 0f
    ): Pair<Float, Float> {
        val maxL = (areaWidth - cardWidth).coerceAtLeast(0f)
        val maxT = (areaHeight - cardHeight).coerceAtLeast(0f)
        return left.coerceIn(0f, maxL) to top.coerceIn(0f, maxT)
    }

    fun toNormalized(
        left: Float,
        top: Float,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float = 0f
    ): Pair<Float, Float> {
        val maxL = (areaWidth - cardWidth).coerceAtLeast(0f)
        val maxT = (areaHeight - cardHeight).coerceAtLeast(0f)
        val nx = if (maxL <= 0f) 0f else (left / maxL).coerceIn(0f, 1f)
        val ny = if (maxT <= 0f) 0f else (top / maxT).coerceIn(0f, 1f)
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
