package com.watermark.camera.data.watermark

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.OrientationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single drawing engine for both live preview and photo burn-in.
 * Overlay and Canvas must only call [draw] — no duplicate layout math.
 */
class WatermarkRenderer {

    companion object {
        private const val BASE_WIDTH = 1080f
        private const val BASE_FONT_SIZE = 28f
        private const val BASE_LINE_SPACING = 8f
        private const val BASE_PADDING = 20f
        private const val BASE_CARD_RADIUS = 16f
        private const val MIN_FONT = 12f
        private const val MAX_FONT = 96f
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

    /**
     * Draw watermark into [canvas] within [areaWidth] x [areaHeight].
     * Same code path for OverlayView and saved JPEG.
     */
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

        val rotation = uprightRotation(deviceOrientation)
        val save = canvas.save()
        if (rotation != 0f) {
            canvas.rotate(
                rotation,
                metrics.left + metrics.width / 2f,
                metrics.top + metrics.height / 2f
            )
        }

        glassRenderer.drawGlassCard(
            canvas = canvas,
            left = metrics.left,
            top = metrics.top,
            width = metrics.width.toInt().coerceAtLeast(1),
            height = metrics.height.toInt().coerceAtLeast(1),
            radius = metrics.radius,
            borderWidth = 1f,
            transparency = config.transparency,
            template = config.template
        )

        textPaint.textSize = metrics.fontSize
        textPaint.color = textColor(config)
        var y = metrics.top + metrics.padding + metrics.fontSize
        for (line in metrics.lines) {
            canvas.drawText(line, metrics.left + metrics.padding, y, textPaint)
            y += metrics.fontSize + metrics.lineSpacing
        }
        canvas.restoreToCount(save)
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

        val scale = areaWidth / BASE_WIDTH
        val fontScale = 2.5f
        val fontSize = (BASE_FONT_SIZE * scale * fontScale).coerceIn(MIN_FONT, MAX_FONT)
        val padding = (BASE_PADDING * scale).coerceAtLeast(8f)
        val lineSpacing = (BASE_LINE_SPACING * scale).coerceAtLeast(2f)
        val radius = (BASE_CARD_RADIUS * scale).coerceAtLeast(8f)

        textPaint.textSize = fontSize
        var maxW = 0f
        val bounds = Rect()
        for (line in lines) {
            textPaint.getTextBounds(line, 0, line.length, bounds)
            maxW = maxOf(maxW, bounds.width().toFloat())
        }
        val textH = fontSize * lines.size + lineSpacing * (lines.size - 1)
        val cardW = maxW + padding * 2
        val cardH = textH + padding * 2

        val (left, top) = WatermarkLayout.cardOrigin(
            config = config,
            areaWidth = areaWidth,
            areaHeight = areaHeight,
            cardWidth = cardW,
            cardHeight = cardH,
            margin = 0f
        )
        return CardMetrics(left, top, cardW, cardH, padding, fontSize, lineSpacing, radius, lines)
    }

    private fun textColor(config: WatermarkConfig): Int = when (config.timeStyle) {
        TimeStyle.DIGITAL_TUBE -> 0xFF00FF66.toInt()
        TimeStyle.RETRO_SLASH -> 0xFFD4A574.toInt()
        TimeStyle.FLIP_CALENDAR -> 0xFFFFFFFF.toInt()
        else -> config.template.textColor
    }

    private fun uprightRotation(o: OrientationHelper.DeviceOrientation): Float = when (o) {
        OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 90f
        OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> -90f
        OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180f
        else -> 0f
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
