package com.watermark.camera.data.watermark

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.util.OrientationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single draw path for preview Overlay and saved JPEG.
 * Scale is always: shortSide / BASE_SHORT * config.fontScale — identical for both surfaces.
 * No device-orientation canvas rotation (bitmap/preview already upright).
 */
class WatermarkRenderer {

    companion object {
        /** Design reference short side (px). */
        private const val BASE_SHORT = 1080f
        private const val BASE_FONT_SIZE = 28f
        private const val BASE_LINE_SPACING = 8f
        private const val BASE_PADDING = 20f
        private const val BASE_CARD_RADIUS = 16f
        private const val MIN_FONT = 10f
        private const val MAX_FONT = 120f
    }

    private val glassRenderer = GlassmorphismRenderer()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }

    data class CardMetrics(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val padding: Float,
        val fontSize: Float,
        val lineSpacing: Float,
        val radius: Float,
        val lines: List<String>
    )

    fun draw(
        canvas: Canvas,
        areaWidth: Float,
        areaHeight: Float,
        config: WatermarkConfig,
        locationText: String,
        deviceOrientation: OrientationHelper.DeviceOrientation =
            OrientationHelper.DeviceOrientation.PORTRAIT,
        timeMs: Long = System.currentTimeMillis()
    ): CardMetrics? {
        if (areaWidth <= 0f || areaHeight <= 0f) return null
        val metrics = measure(areaWidth, areaHeight, config, locationText, timeMs) ?: return null
        // deviceOrientation kept in signature for API compat; do NOT rotate — preview & JPEG are upright
        glassRenderer.drawGlassCard(
            canvas = canvas,
            left = metrics.left,
            top = metrics.top,
            width = metrics.width.toInt().coerceAtLeast(1),
            height = metrics.height.toInt().coerceAtLeast(1),
            radius = metrics.radius,
            borderWidth = 1f,
            transparency = config.transparency.coerceIn(0.3f, 1f),
            template = config.template
        )
        textPaint.textSize = metrics.fontSize
        textPaint.color = textColor(config)
        var y = metrics.top + metrics.padding - textPaint.ascent()
        val x = metrics.left + metrics.padding
        for (line in metrics.lines) {
            canvas.drawText(line, x, y, textPaint)
            y += metrics.fontSize + metrics.lineSpacing
        }
        return metrics
    }

    fun measure(
        areaWidth: Float,
        areaHeight: Float,
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long = System.currentTimeMillis()
    ): CardMetrics? {
        val lines = buildLines(config, locationText, timeMs)
        if (lines.isEmpty()) return null

        // Same formula for preview and photo: relative to short side of the draw area
        val shortSide = minOf(areaWidth, areaHeight)
        val scale = (shortSide / BASE_SHORT).coerceAtLeast(0.25f)
        val userScale = config.fontScale.coerceIn(0.5f, 8f)
        val fontSize = (BASE_FONT_SIZE * scale * userScale).coerceIn(MIN_FONT, MAX_FONT)
        val lineSpacing = BASE_LINE_SPACING * scale * userScale
        val padding = BASE_PADDING * scale
        val radius = BASE_CARD_RADIUS * scale

        textPaint.textSize = fontSize
        var maxTextW = 0f
        for (line in lines) {
            maxTextW = maxOf(maxTextW, textPaint.measureText(line))
        }
        val cardW = (maxTextW + padding * 2f).coerceAtMost(areaWidth * 0.95f)
        val cardH = padding * 2f + lines.size * fontSize + (lines.size - 1).coerceAtLeast(0) * lineSpacing

        val (left, top) = resolveOrigin(
            config = config,
            areaWidth = areaWidth,
            areaHeight = areaHeight,
            cardWidth = cardW,
            cardHeight = cardH
        )
        return CardMetrics(left, top, cardW, cardH, padding, fontSize, lineSpacing, radius, lines)
    }

    private fun resolveOrigin(
        config: WatermarkConfig,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float
    ): Pair<Float, Float> {
        val margin = minOf(areaWidth, areaHeight) * 0.03f
        val cx = config.customX
        val cy = config.customY
        if (cx != null && cy != null) {
            val left = (cx * areaWidth).coerceIn(0f, (areaWidth - cardWidth).coerceAtLeast(0f))
            val top = (cy * areaHeight).coerceIn(0f, (areaHeight - cardHeight).coerceAtLeast(0f))
            return left to top
        }
        return when (config.position) {
            WatermarkPosition.TOP_LEFT -> margin to margin
            WatermarkPosition.TOP_RIGHT -> (areaWidth - cardWidth - margin) to margin
            WatermarkPosition.BOTTOM_RIGHT ->
                (areaWidth - cardWidth - margin) to (areaHeight - cardHeight - margin)
            WatermarkPosition.CENTER ->
                ((areaWidth - cardWidth) / 2f) to ((areaHeight - cardHeight) / 2f)
            else -> margin to (areaHeight - cardHeight - margin) // BOTTOM_LEFT default
        }
    }

    private fun textColor(config: WatermarkConfig): Int = when (config.timeStyle) {
        TimeStyle.DIGITAL_TUBE -> 0xFF00FF66.toInt()
        TimeStyle.RETRO_SLASH -> 0xFFD4A574.toInt()
        TimeStyle.FLIP_CALENDAR -> 0xFFFFFFFF.toInt()
        else -> config.template.textColor
    }

    private fun buildLines(config: WatermarkConfig, locationText: String, timeMs: Long): List<String> {
        val lines = mutableListOf<String>()
        val now = Date(timeMs)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.CHINA)
        lines.add(config.template.displayName + "水印")
        lines.add("${timeFormat.format(now)} | ${dateFormat.format(now)}")
        lines.add(weekFormat.format(now))
        if (config.showLocation) {
            val loc = locationText.ifBlank { config.location }.ifBlank { "定位中…" }
            lines.add("● $loc")
        }
        if (config.name.isNotBlank()) lines.add("汇报人: ${config.name}")
        if (config.projectName.isNotBlank()) lines.add("项目: ${config.projectName}")
        if (config.remark.isNotBlank()) lines.add("备注: ${config.remark}")
        return lines
    }
}
