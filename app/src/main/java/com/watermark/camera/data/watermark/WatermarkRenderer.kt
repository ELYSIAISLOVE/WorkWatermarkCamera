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
 * Preview + JPEG single path.
 * Layout (all centered):
 *  1) Template title — bold, larger
 *  2) Date yyyy/MM/dd — centered
 *  3) Time HH:mm:ss + weekday
 *  4) Location — wrap, centered
 * TimeStyle changes typeface + time format so style picks are visible.
 */
class WatermarkRenderer {

    companion object {
        private const val BASE_SHORT = 1080f
        private const val BASE_FONT = 15f
        private const val BASE_TITLE = 18f
        private const val BASE_PAD = 12f
        private const val BASE_GAP = 5f
        private const val BASE_RADIUS = 12f
        private const val MIN_F = 10f
        private const val MAX_F = 72f
        private const val TARGET_ASPECT = 4f / 3f
    }

    private val glassRenderer = GlassmorphismRenderer()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

    data class CardMetrics(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val padding: Float,
        val fontSize: Float,
        val lineSpacing: Float,
        val radius: Float,
        val lines: List<LineSpec>
    )

    data class LineSpec(
        val text: String,
        val bold: Boolean,
        val scale: Float // relative to body font
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
        // Activity is portrait-locked: never rotate the watermark card.
        // Rotating by accelerometer orientation makes text sideways on a fixed portrait preview.
        @Suppress("UNUSED_PARAMETER")
        val _orient = deviceOrientation

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

        applyStyleTypeface(config.timeStyle)
        val color = textColor(config)
        textPaint.color = color

        var y = metrics.top + metrics.padding
        val centerX = metrics.left + metrics.width / 2f
        for (spec in metrics.lines) {
            val size = (metrics.fontSize * spec.scale).coerceIn(MIN_F, MAX_F)
            textPaint.textSize = size
            textPaint.typeface = if (spec.bold) {
                Typeface.create(textPaint.typeface, Typeface.BOLD)
            } else {
                Typeface.create(baseTypeface(config.timeStyle), Typeface.NORMAL)
            }
            textPaint.textAlign = Paint.Align.CENTER
            val baseline = y - textPaint.ascent()
            canvas.drawText(spec.text, centerX, baseline, textPaint)
            y = baseline + textPaint.descent() + metrics.lineSpacing
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
        applyStyleTypeface(config.timeStyle)
        val shortSide = minOf(areaWidth, areaHeight)
        val scale = (shortSide / BASE_SHORT).coerceAtLeast(0.25f)
        val user = config.fontScale.coerceIn(0.5f, 8f)
        val body = (BASE_FONT * scale * user).coerceIn(MIN_F, MAX_F)
        val gap = BASE_GAP * scale * user
        val pad = BASE_PAD * scale
        val radius = BASE_RADIUS * scale

        val maxCardW = areaWidth * 0.92f
        val targetW = (body * 14f).coerceIn(areaWidth * 0.4f, maxCardW)
        val maxTextW = (targetW - pad * 2f).coerceAtLeast(body * 8f)

        textPaint.textSize = body
        textPaint.typeface = Typeface.create(baseTypeface(config.timeStyle), Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.LEFT

        val raw = buildLineSpecs(config, locationText, timeMs)
        val lines = mutableListOf<LineSpec>()
        for (spec in raw) {
            val size = body * spec.scale
            textPaint.textSize = size
            textPaint.typeface = if (spec.bold) {
                Typeface.create(baseTypeface(config.timeStyle), Typeface.BOLD)
            } else {
                Typeface.create(baseTypeface(config.timeStyle), Typeface.NORMAL)
            }
            val wrapped = wrapLine(spec.text, maxTextW)
            for (w in wrapped) {
                lines.add(LineSpec(w, spec.bold, spec.scale))
            }
        }
        if (lines.isEmpty()) return null

        var measuredW = 0f
        var measuredH = pad * 2f
        for ((i, spec) in lines.withIndex()) {
            val size = body * spec.scale
            textPaint.textSize = size
            measuredW = maxOf(measuredW, textPaint.measureText(spec.text))
            measuredH += size
            if (i < lines.lastIndex) measuredH += gap
        }
        val cardW = (measuredW + pad * 2f).coerceAtMost(maxCardW)
        // nudge toward 4:3 if too tall/narrow
        val minWForAspect = (measuredH * TARGET_ASPECT * 0.85f).coerceAtMost(maxCardW)
        val finalW = maxOf(cardW, minWForAspect).coerceAtMost(maxCardW)

        val (left, top) = resolveOrigin(config, areaWidth, areaHeight, finalW, measuredH)
        return CardMetrics(left, top, finalW, measuredH, pad, body, gap, radius, lines)
    }

    private fun buildLineSpecs(
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long
    ): List<LineSpec> {
        val date = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date(timeMs))
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(Date(timeMs))
        val timeStr = when (config.timeStyle) {
            TimeStyle.DIGITAL_TUBE -> SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs))
            TimeStyle.FLIP_CALENDAR -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeMs))
            TimeStyle.RETRO_SLASH -> SimpleDateFormat("HH/mm/ss", Locale.CHINA).format(Date(timeMs))
            else -> SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs))
        }
        val title = config.template.displayName // 执勤 / 物业巡检 …
        val loc = locationText.ifBlank { "定位中…" }
        return listOf(
            LineSpec(title, bold = true, scale = 1.25f),
            LineSpec(date, bold = false, scale = 1.0f),
            LineSpec("$timeStr  $week", bold = false, scale = 1.0f),
            LineSpec(loc, bold = false, scale = 0.95f)
        )
    }

    private fun baseTypeface(style: TimeStyle): Typeface {
        return when (style) {
            TimeStyle.DIGITAL_TUBE -> Typeface.MONOSPACE
            TimeStyle.FLIP_CALENDAR -> Typeface.SERIF
            TimeStyle.RETRO_SLASH -> Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
    }

    private fun applyStyleTypeface(style: TimeStyle) {
        textPaint.typeface = baseTypeface(style)
    }

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
                if (textPaint.measureText(text, start, end) <= maxWidth) {
                    lastFit = end
                    end++
                } else break
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
            return (cx.coerceIn(0f, 1f) * maxL).coerceIn(0f, maxL) to
                (cy.coerceIn(0f, 1f) * maxT).coerceIn(0f, maxT)
        }
        return when (config.position) {
            WatermarkPosition.TOP_LEFT -> 0f to 0f
            WatermarkPosition.TOP_RIGHT -> maxL to 0f
            WatermarkPosition.BOTTOM_LEFT -> 0f to maxT
            WatermarkPosition.BOTTOM_RIGHT -> maxL to maxT
            WatermarkPosition.CENTER -> (maxL / 2f) to (maxT / 2f)
        }
    }

    private fun orientationDegrees(o: OrientationHelper.DeviceOrientation): Float = when (o) {
        OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 90f
        OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> -90f
        OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180f
        else -> 0f
    }

    private fun textColor(config: WatermarkConfig): Int {
        val a = (config.transparency.coerceIn(0.3f, 1f) * 255).toInt().coerceIn(80, 255)
        return (a shl 24) or 0x00FFFFFF
    }
}
