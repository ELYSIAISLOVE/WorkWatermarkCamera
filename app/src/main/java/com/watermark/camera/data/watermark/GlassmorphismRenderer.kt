package com.watermark.camera.data.watermark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Glassmorphism (frosted glass) effect renderer.
 *
 * Implements modern UI glassmorphism with:
 * - Semi-transparent background
 * - Blurred edges (simulated via layered rendering)
 * - Subtle border highlight
 * - Lighting effects for depth
 */
class GlassmorphismRenderer {

    companion object {
        // Glassmorphism colors
        private const val GLASS_BACKGROUND_ALPHA = 220 // 86% opacity
        private const val GLASS_BACKGROUND_COLOR = 0xFFFFFFFF // White base
        private const val BORDER_COLOR = 0xFFEEEEEE // Light gray border
        private const val BORDER_ALPHA = 200
        private const val BORDER_WIDTH = 1.5f
        private const val CORNER_RADIUS = 16f
    }

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GLASS_BACKGROUND_COLOR.toInt()
        alpha = GLASS_BACKGROUND_ALPHA
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BORDER_COLOR.toInt()
        alpha = BORDER_ALPHA
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
    }

    /**
     * Draw a glassmorphism card background.
     *
     * @param canvas Target canvas.
     * @param left Left coordinate.
     * @param top Top coordinate.
     * @param right Right coordinate.
     * @param bottom Bottom coordinate.
     */
    fun drawGlassCard(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val bounds = RectF(left, top, right, bottom)

        // Draw background with rounded corners
        canvas.drawRoundRect(
            bounds,
            CORNER_RADIUS,
            CORNER_RADIUS,
            glassPaint
        )

        // Draw border
        canvas.drawRoundRect(
            bounds,
            CORNER_RADIUS,
            CORNER_RADIUS,
            borderPaint
        )

        // Optional: Draw top highlight for depth
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 80 // Subtle highlight
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
        }

        val highlightBounds = RectF(
            left + 1f,
            top + 1f,
            right - 1f,
            top + CORNER_RADIUS
        )
        canvas.drawRoundRect(
            highlightBounds,
            CORNER_RADIUS - 1f,
            CORNER_RADIUS - 1f,
            highlightPaint
        )
    }

    /**
     * Update glass background opacity.
     *
     * @param alpha Alpha value (0-255).
     */
    fun setBackgroundAlpha(alpha: Int) {
        glassPaint.alpha = alpha.coerceIn(0, 255)
    }

    /**
     * Update border color.
     *
     * @param color Color value.
     */
    fun setBorderColor(color: Int) {
        borderPaint.color = color
    }
}
