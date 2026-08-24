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
 * Scale: shortSide / BASE_SHORT * config.fontScale
 * Card aspect prefers ~4:3 (width:height) via text wrapping.
 * Edges clamp so all four sides of the card stay inside the draw area.
 * Device orientation rotates the card so it follows device pose.
 */
class WatermarkRenderer {

    companion object {
        private const val BASE_SHORT = 1080f
        private const val BASE_FONT_SIZE = 16f
        private const val BASE_LINE_SPACING = 5f
        private const val BASE_PADDING = 12f
        private const val BASE_CARD_RADIUS = 12f
        private const val MIN_FONT = 10f
        private const val MAX_FONT = 96f
        /** Preferred card width / height ≈ 4/3 */
        private const val TARGET_ASPECT = 4f / 3f
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
        val metrics = measure(areaWidth, areaHeight, config, locationText, timeMs) ?: return null

        val degrees = orientationDegrees(deviceOrientation)
        val needRotate = degrees != 0f

        if (needRotate) {
            // Rotate around card center so watermark follows device pose
            val cx = metrics.left + metrics.width / 2f
            val cy = metrics.top + metrics.height / 2f
            canvas.save()
            canvas.rotate(degrees, cx, cy)
        }

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

        if (needRotate) {
            canvas.restore()
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
        val rawLines = buildLines(config, locationText, timeMs)
        if (rawLines.isEmpty()) return null

        val shortSide = minOf(areaWidth, areaHeight)
        val scale = (shortSide / BASE_SHORT).coerceAtLeast(0.25f)
        val userScale = config.fontScale.coerceIn(0.5f, 8f)
        val fontSize = (BASE_FONT_SIZE * scale * userScale).coerceIn(MIN_FONT, MAX_FONT)
        val lineSpacing = BASE_LINE_SPACING * scale * userScale
        val padding = BASE_PADDING * scale
        val radius = BASE_CARD_RADIUS * scale

        textPaint.textSize = fontSize
        // Max text width: keep card near 4:3 and within 92% of area width
        val maxCardW = areaWidth * 0.92f
        // Estimate: if N lines of height h, want width ≈ 4/3 * totalHeight
        val approxLines = rawLines.size.coerceAtLeast(3)
        val targetH = padding * 2f + approxLines * fontSize + (approxLines - 1) * lineSpacing
        val targetW = (targetH * TARGET_ASPECT).coerceIn(areaWidth * 0.35f, maxCardW)
        val maxTextW = (targetW - padding * 2f).coerceAtLeast(fontSize * 6f)

        val lines = mutableListOf<String>()
        for (line in rawLines) {
            lines.addAll(wrapLine(line, maxTextW))
        }
        if (lines.isEmpty()) return null

        var measuredW = 0f
        for (line in lines) {
            measuredW = maxOf(measuredW, textPaint.measureText(line))
        }
        val cardW = (measuredW + padding * 2f).coerceAtMost(maxCardW)
        val cardH = padding * 2f + lines.size * fontSize +
            (lines.size - 1).coerceAtLeast(0) * lineSpacing

        val (left, top) = resolveOrigin(
            config = config,
            areaWidth = areaWidth,
            areaHeight = areaHeight,
            cardWidth = cardW,
            cardHeight = cardH
        )
        return CardMetrics(left, top, cardW, cardH, padding, fontSize, lineSpacing, radius, lines)
    }

    /** Wrap a single logical line into multiple lines by measured width. */
    private fun wrapLine(text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        if (textPaint.measureText(text) <= maxWidth) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        val len = text.length
        while (start < len) {
            var end = start + 1
            var lastFit = start + 1
            while (end <= len) {
                val w = textPaint.measureText(text, start, end)
                if (w <= maxWidth) {
                    lastFit = end
                    end++
                } else {
                    break
                }
            }
            if (lastFit <= start) lastFit = (start + 1).coerceAtMost(len)
            result.add(text.substring(start, lastFit))
            start = lastFit
        }
        return result
    }

    private fun resolveOrigin(
        config: WatermarkConfig,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float
    ): Pair<Float, Float> {
        val maxL = (areaWidth - cardWidth).coerceAtLeast(0f)
        val maxT = (areaHeight - cardHeight).coerceAtLeast(0f)
        val cx = config.customX
        val cy = config.customY
        if (cx != null && cy != null) {
            // Four-edge clamp: card fully inside [0, areaW] x [0, areaH]
            val left = (cx.coerceIn(0f, 1f) * maxL).coerceIn(0f, maxL)
            val top = (cy.coerceIn(0f, 1f) * maxT).coerceIn(0f, maxT)
            return left to top
        }
        return when (config.position) {
            WatermarkPosition.TOP_LEFT -> 0f to 0f
            WatermarkPosition.TOP_RIGHT -> maxL to 0f
            WatermarkPosition.BOTTOM_LEFT -> 0f to maxT
            WatermarkPosition.BOTTOM_RIGHT -> maxL to maxT
            WatermarkPosition.CENTER -> (maxL / 2f) to (maxT / 2f)
        }
    }

    private fun orientationDegrees(o: OrientationHelper.DeviceOrientation): Float {
        return when (o) {
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 90f
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> -90f
            OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180f
            else -> 0f
        }
    }

    private fun textColor(config: WatermarkConfig): Int {
        // Prefer white text with slight transparency from config
        val a = (config.transparency.coerceIn(0.3f, 1f) * 255).toInt().coerceIn(80, 255)
        return (a shl 24) or 0x00FFFFFF
    }

    private fun buildLines(
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long
    ): List<String> {
        val lines = mutableListOf<String>()
        val title = config.template.displayName + "水印"
        lines.add(title)

        val timeFmt = when (config.timeStyle) {
            TimeStyle.DEFAULT -> SimpleDateFormat("HH:mm:ss | yyyy/MM/dd", Locale.CHINA)
            TimeStyle.DIGITAL_TUBE -> SimpleDateFormat("HH:mm:ss | yyyy/MM/dd", Locale.CHINA)
            TimeStyle.FLIP_CALENDAR -> SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
            TimeStyle.RETRO_SLASH -> SimpleDateFormat("HH:mm  yyyy.MM.dd", Locale.CHINA)
            else -> SimpleDateFormat("HH:mm:ss | yyyy/MM/dd", Locale.CHINA)
        }
        lines.add(timeFmt.format(Date(timeMs)))

        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(Date(timeMs))
        lines.add(week)

        val loc = locationText.ifBlank { "定位中…" }
        lines.add(loc)
        return lines
    }
}
