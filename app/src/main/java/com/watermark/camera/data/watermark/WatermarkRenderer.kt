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
 * Unified watermark for preview + JPEG.
 *
 * All lines are measured with FontMetrics and drawn strictly inside the glass card:
 *   1) Title (bold, larger)
 *   2) Date  yyyy年MM月dd日
 *   3) Time + weekday
 *   4) Location (wrap, centered)
 *
 * Two-pass measure: wrap → size card → re-wrap to final inner width → height.
 * Canvas rotates with deviceOrientation so watermark follows phone posture.
 */
class WatermarkRenderer {

    companion object {
        private const val BASE_SHORT = 1080f
        private const val BASE_FONT = 15f
        private const val BASE_PAD_H = 16f
        private const val BASE_PAD_V = 14f
        private const val BASE_GAP = 6f
        private const val BASE_RADIUS = 14f
        private const val MIN_F = 10f
        private const val MAX_F = 72f
    }

    private val glassRenderer = GlassmorphismRenderer()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
    }

    data class LineSpec(
        val text: String,
        val bold: Boolean,
        val scale: Float,
        val colorArgb: Int? = null
    )

    data class CardMetrics(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val paddingH: Float,
        val paddingV: Float,
        val fontSize: Float,
        val lineSpacing: Float,
        val radius: Float,
        val lines: List<LineSpec>
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
        val m = measure(areaWidth, areaHeight, config, locationText, timeMs) ?: return null

        // Rotate watermark with device posture so text stays readable relative to gravity
        val degrees = when (deviceOrientation) {
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 90f
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> -90f
            OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180f
            else -> 0f
        }
        val pivotX = m.left + m.width / 2f
        val pivotY = m.top + m.height / 2f
        canvas.save()
        if (degrees != 0f) {
            canvas.rotate(degrees, pivotX, pivotY)
        }

        glassRenderer.drawGlassCard(
            canvas = canvas,
            left = m.left,
            top = m.top,
            width = m.width.toInt().coerceAtLeast(1),
            height = m.height.toInt().coerceAtLeast(1),
            radius = m.radius,
            borderWidth = 1.5f,
            transparency = config.transparency.coerceIn(0.35f, 1f),
            template = config.template
        )

        val defaultColor = textColor(config)
        textPaint.textAlign = Paint.Align.CENTER
        val cx = m.left + m.width / 2f

        // cursorY = top edge of current line's glyph box
        var cursorY = m.top + m.paddingV
        for (spec in m.lines) {
            val size = (m.fontSize * spec.scale).coerceIn(MIN_F, MAX_F)
            textPaint.textSize = size
            textPaint.typeface = typefaceFor(config.timeStyle, spec.bold)
            textPaint.color = spec.colorArgb ?: defaultColor
            val fm = textPaint.fontMetrics
            // baseline so that glyph top (baseline+ascent) == cursorY
            val baseline = cursorY - fm.ascent
            canvas.drawText(spec.text, cx, baseline, textPaint)
            // next line top = this glyph bottom + gap
            cursorY = baseline + fm.descent + m.lineSpacing
        }
        canvas.restore()
        return m
    }

    fun measure(
        areaWidth: Float,
        areaHeight: Float,
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long = System.currentTimeMillis()
    ): CardMetrics? {
        val shortSide = minOf(areaWidth, areaHeight)
        val scale = (shortSide / BASE_SHORT).coerceAtLeast(0.25f)
        val user = config.fontScale.coerceIn(0.5f, 8f)
        val body = (BASE_FONT * scale * user).coerceIn(MIN_F, MAX_F)
        val gap = BASE_GAP * scale
        val padH = BASE_PAD_H * scale
        val padV = BASE_PAD_V * scale
        val radius = BASE_RADIUS * scale

        val maxCardW = areaWidth * 0.90f
        // Preferred inner text width (~0.72 of area, or based on body)
        val preferInner = (body * 18f).coerceIn(areaWidth * 0.42f, maxCardW - padH * 2f)

        val raw = buildLineSpecs(config, locationText, timeMs)

        // Pass 1: wrap with preferred width
        var lines = wrapAll(raw, preferInner, body, config.timeStyle)
        if (lines.isEmpty()) return null

        // Compute content size
        var contentW = 0f
        var contentH = 0f
        for ((i, spec) in lines.withIndex()) {
            val size = body * spec.scale
            textPaint.textSize = size
            textPaint.typeface = typefaceFor(config.timeStyle, spec.bold)
            contentW = maxOf(contentW, textPaint.measureText(spec.text))
            val fm = textPaint.fontMetrics
            contentH += (fm.descent - fm.ascent)
            if (i < lines.lastIndex) contentH += gap
        }

        var cardW = (contentW + padH * 2f).coerceIn(body * 12f, maxCardW)
        // Pass 2: re-wrap to actual inner width so no glyph exceeds card
        val innerW = (cardW - padH * 2f).coerceAtLeast(body * 6f)
        lines = wrapAll(raw, innerW, body, config.timeStyle)
        contentW = 0f
        contentH = 0f
        for ((i, spec) in lines.withIndex()) {
            val size = body * spec.scale
            textPaint.textSize = size
            textPaint.typeface = typefaceFor(config.timeStyle, spec.bold)
            contentW = maxOf(contentW, textPaint.measureText(spec.text))
            val fm = textPaint.fontMetrics
            contentH += (fm.descent - fm.ascent)
            if (i < lines.lastIndex) contentH += gap
        }
        cardW = (contentW + padH * 2f).coerceIn(body * 12f, maxCardW)
        // Extra bottom pad so last descent never clips
        val cardH = contentH + padV * 2f + (body * 0.15f)

        val (left, top) = resolveOrigin(config, areaWidth, areaHeight, cardW, cardH)
        return CardMetrics(left, top, cardW, cardH, padH, padV, body, gap, radius, lines)
    }

    private fun wrapAll(
        raw: List<LineSpec>,
        maxTextW: Float,
        body: Float,
        style: TimeStyle
    ): List<LineSpec> {
        val out = mutableListOf<LineSpec>()
        for (spec in raw) {
            val size = body * spec.scale
            textPaint.textSize = size
            textPaint.typeface = typefaceFor(style, spec.bold)
            for (w in wrapLine(spec.text, maxTextW)) {
                out.add(spec.copy(text = w))
            }
        }
        return out
    }

    private fun buildLineSpecs(
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long
    ): List<LineSpec> {
        val date = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date(timeMs))
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(Date(timeMs))
        val (timeStr, accent) = when (config.timeStyle) {
            TimeStyle.DIGITAL_TUBE ->
                SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs)) to 0xFF00FF66.toInt()
            TimeStyle.FLIP_CALENDAR ->
                SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeMs)) to 0xFFFFCC66.toInt()
            TimeStyle.RETRO_SLASH ->
                SimpleDateFormat("HH/mm/ss", Locale.CHINA).format(Date(timeMs)) to 0xFFD4A574.toInt()
            else ->
                SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs)) to null
        }
        val loc = locationText.ifBlank { "定位中…" }
        val lines = mutableListOf<LineSpec>()
        lines.add(LineSpec(config.template.displayName, bold = true, scale = 1.28f))
        if (config.name.isNotBlank()) {
            lines.add(LineSpec("姓名: ${config.name}", bold = false, scale = 1.0f))
        }
        if (config.projectName.isNotBlank()) {
            lines.add(LineSpec("项目: ${config.projectName}", bold = false, scale = 1.0f))
        }
        lines.add(LineSpec(date, bold = false, scale = 1.0f))
        lines.add(LineSpec("$timeStr  $week", bold = false, scale = 1.0f, colorArgb = accent))
        if (config.showLocation) {
            lines.add(LineSpec(loc, bold = false, scale = 0.92f))
        }
        if (config.remark.isNotBlank()) {
            lines.add(LineSpec(config.remark, bold = false, scale = 0.9f))
        }
        return lines
    }

    private fun typefaceFor(style: TimeStyle, bold: Boolean): Typeface {
        val base = when (style) {
            TimeStyle.DIGITAL_TUBE -> Typeface.MONOSPACE
            TimeStyle.FLIP_CALENDAR -> Typeface.SERIF
            TimeStyle.RETRO_SLASH -> Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
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

    private fun textColor(config: WatermarkConfig): Int {
        val a = (config.transparency.coerceIn(0.35f, 1f) * 255).toInt().coerceIn(120, 255)
        return (a shl 24) or 0x00FFFFFF
    }
}
