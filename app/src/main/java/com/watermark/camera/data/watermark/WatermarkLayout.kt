package com.watermark.camera.data.watermark

import com.watermark.camera.data.model.WatermarkPosition

/**
 * Shared watermark card placement for preview (Overlay) and saved photo (Canvas).
 * Ensures what you see matches what is written into the JPEG.
 */
object WatermarkLayout {

    /**
     * Top-left of the watermark card in the target coordinate space.
     */
    fun cardOrigin(
        position: WatermarkPosition,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float
    ): Pair<Float, Float> {
        val m = margin.coerceAtLeast(0f)
        val maxL = (areaWidth - cardWidth - m).coerceAtLeast(m)
        val maxT = (areaHeight - cardHeight - m).coerceAtLeast(m)
        return when (position) {
            WatermarkPosition.TOP_LEFT -> m to m
            WatermarkPosition.TOP_RIGHT -> maxL to m
            WatermarkPosition.BOTTOM_LEFT -> m to maxT
            WatermarkPosition.BOTTOM_RIGHT -> maxL to maxT
            WatermarkPosition.CENTER ->
                ((areaWidth - cardWidth) / 2f).coerceAtLeast(0f) to
                    ((areaHeight - cardHeight) / 2f).coerceAtLeast(0f)
        }
    }

    /**
     * Map a touch point to the nearest corner / center slot.
     */
    fun positionFromTouch(x: Float, y: Float, areaWidth: Float, areaHeight: Float): WatermarkPosition {
        val left = x < areaWidth / 2f
        val top = y < areaHeight / 2f
        val nearCenter =
            kotlin.math.abs(x - areaWidth / 2f) < areaWidth * 0.18f &&
                kotlin.math.abs(y - areaHeight / 2f) < areaHeight * 0.18f
        if (nearCenter) return WatermarkPosition.CENTER
        return when {
            top && left -> WatermarkPosition.TOP_LEFT
            top && !left -> WatermarkPosition.TOP_RIGHT
            !top && left -> WatermarkPosition.BOTTOM_LEFT
            else -> WatermarkPosition.BOTTOM_RIGHT
        }
    }
}
