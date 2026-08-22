package com.watermark.camera.data.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.OrientationHelper
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.util.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watermark drawing engine using Android Canvas.
 *
 * Renders watermark text onto a bitmap with:
 * - Adaptive font scaling based on photo dimensions
 * - Template-based color schemes
 * - Glassmorphism card background
 * - Multi-line text layout with proper spacing
 */
class WatermarkCanvas {

    companion object {
        private const val TAG = "WatermarkCanvas"

        // Base dimensions for scaling calculations (reference: 1080p width)
        private const val BASE_WIDTH = 1080f
        private const val BASE_FONT_SIZE = 28f
        private const val BASE_LINE_SPACING = 8f
        private const val BASE_PADDING = 20f
        private const val BASE_CARD_RADIUS = 16f
        private const val BASE_CARD_BORDER_WIDTH = 1f

        // Minimum watermark size constraints
        private const val MIN_FONT_SIZE = 12f
        private const val MAX_FONT_SIZE = 72f
    }

    /**
     * Draw watermark onto a bitmap.
     *
     * @param sourceBitmap The original photo bitmap.
     * @param config Watermark configuration.
     * @param locationStr Current location string (may be empty).
     * @return New bitmap with watermark applied.
     */
    fun drawWatermark(
        sourceBitmap: Bitmap,
        config: WatermarkConfig,
        locationStr: String = "",
        deviceOrientation: OrientationHelper.DeviceOrientation =
            OrientationHelper.DeviceOrientation.PORTRAIT
    ): Bitmap {
        val width = sourceBitmap.width
        val height = sourceBitmap.height

        // Create mutable copy
        val resultBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // Calculate scale factor based on photo width
        val scaleFactor = width / BASE_WIDTH
        val fontSize = (BASE_FONT_SIZE * scaleFactor * config.fontScale)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val lineSpacing = (BASE_LINE_SPACING * scaleFactor).toInt()
        val padding = (BASE_PADDING * scaleFactor).toInt()
        val cardRadius = (BASE_CARD_RADIUS * scaleFactor)
        val borderWidth = (BASE_CARD_BORDER_WIDTH * scaleFactor).coerceAtLeast(1f)

        // Build watermark text lines
        val lines = buildWatermarkLines(config, locationStr)
        if (lines.isEmpty()) {
            Logger.w(TAG, "No watermark lines to draw")
            return resultBitmap
        }

        // Measure text dimensions
        val textPaint = createTextPaint(fontSize, config.template.textColor)
        val (textWidth, textHeight) = measureTextDimensions(lines, textPaint, lineSpacing)

        val cardWidth = textWidth + padding * 2
        val cardHeight = textHeight + padding * 2

        // Calculate position
        // Same coordinate model as preview: normalized customX/Y over full bitmap, margin 0
        val (cardLeft, cardTop) = WatermarkLayout.cardOrigin(
            config = config,
            areaWidth = width.toFloat(),
            areaHeight = height.toFloat(),
            cardWidth = cardWidth.toFloat(),
            cardHeight = cardHeight.toFloat(),
            margin = 0f
        )

        // Match preview: rotate watermark card so text stays upright relative to device gravity
        val rotation = when (deviceOrientation) {
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 90f
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> -90f
            OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180f
            else -> 0f
        }
        val saveCount = canvas.save()
        if (rotation != 0f) {
            val cx = cardLeft + cardWidth / 2f
            val cy = cardTop + cardHeight / 2f
            canvas.rotate(rotation, cx, cy)
        }

        // Draw glassmorphism card background
        val glassRenderer = GlassmorphismRenderer()
        glassRenderer.drawGlassCard(
            canvas = canvas,
            left = cardLeft,
            top = cardTop,
            width = cardWidth,
            height = cardHeight,
            radius = cardRadius,
            borderWidth = borderWidth,
            transparency = config.transparency,
            template = config.template
        )

        // Draw text lines
        var currentY = cardTop + padding + textPaint.textSize
        for (line in lines) {
            canvas.drawText(line, cardLeft + padding, currentY, textPaint)
            currentY += textPaint.textSize + lineSpacing
        }
        canvas.restoreToCount(saveCount)

        Logger.i(TAG, "Watermark drawn: ${lines.size} lines, scale=$scaleFactor, " +
            "fontSize=$fontSize, position=(${cardLeft.toInt()}, ${cardTop.toInt()})")

        return resultBitmap
    }

    /**
     * Build watermark text lines from configuration.
     */
    private fun buildWatermarkLines(
        config: WatermarkConfig,
        locationStr: String
    ): List<String> {
        val lines = mutableListOf<String>()
        val now = Date()

        // Time and date (always present)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.CHINA)

        lines.add(config.template.displayName + "水印")
        lines.add("${timeFormat.format(now)} | ${dateFormat.format(now)}")
        lines.add(weekFormat.format(now))

        // Location
        if (config.showLocation) {
            val loc = locationStr.ifBlank { config.location }.ifBlank { "定位中…" }
            lines.add("● $loc")
        }

        // Custom fields
        if (config.name.isNotBlank()) {
            lines.add("汇报人: ${config.name}")
        }
        if (config.projectName.isNotBlank()) {
            lines.add("项目: ${config.projectName}")
        }
        if (config.remark.isNotBlank()) {
            lines.add("备注: ${config.remark}")
        }

        return lines
    }

    /**
     * Create text paint with specified size and color.
     */
    private fun createTextPaint(size: Float, color: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            isSubpixelText = true
        }
    }

    /**
     * Measure total text dimensions.
     */
    private fun measureTextDimensions(
        lines: List<String>,
        paint: Paint,
        lineSpacing: Int
    ): Pair<Int, Int> {
        var maxWidth = 0
        val bounds = Rect()

        for (line in lines) {
            paint.getTextBounds(line, 0, line.length, bounds)
            maxWidth = maxOf(maxWidth, bounds.width())
        }

        val totalHeight = (paint.textSize * lines.size + lineSpacing * (lines.size - 1)).toInt()
        return maxWidth to totalHeight
    }

    /**
     * Calculate watermark card position based on configuration.
     */

    /**
     * Estimate watermark dimensions without drawing.
     * Useful for preview layout calculations.
     */
    fun estimateDimensions(
        photoWidth: Int,
        config: WatermarkConfig,
        locationStr: String = ""
    ): Pair<Int, Int> {
        val scaleFactor = photoWidth / BASE_WIDTH
        val fontSize = (BASE_FONT_SIZE * scaleFactor * config.fontScale)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val lineSpacing = (BASE_LINE_SPACING * scaleFactor).toInt()
        val padding = (BASE_PADDING * scaleFactor).toInt()

        val lines = buildWatermarkLines(config, locationStr)
        val paint = createTextPaint(fontSize, Color.WHITE)
        val (textWidth, textHeight) = measureTextDimensions(lines, paint, lineSpacing)

        return (textWidth + padding * 2) to (textHeight + padding * 2)
    }
}
