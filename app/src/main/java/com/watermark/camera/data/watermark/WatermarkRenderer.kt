package com.watermark.camera.data.watermark

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.util.OrientationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 表单卡片式水印（预览 + 成片共用）。
 * 布局对齐设计稿：彩色表头 + 白底双列字段 + 彩色页脚标语。
 */
class WatermarkRenderer {

    companion object {
        private const val BASE_SHORT = 1080f
        private const val MIN_CARD_W = 280f
        private const val MAX_CARD_W_RATIO = 0.92f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    data class LineSpec(
        val text: String,
        val bold: Boolean = false,
        val scale: Float = 1f,
        val colorArgb: Int? = null,
        val useTimeStyle: Boolean = false
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

    private data class FieldRow(
        val leftLabel: String,
        val leftValue: String,
        val rightLabel: String? = null,
        val rightValue: String? = null
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

        drawFormCard(canvas, m, config, locationText, timeMs)
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
        if (areaWidth <= 1f || areaHeight <= 1f) return null
        val short = minOf(areaWidth, areaHeight)
        val scale = (short / BASE_SHORT).coerceIn(0.35f, 2.2f)
        val user = config.clampedFontScale()
        val body = (15f * scale * user).coerceIn(12f, 48f)
        val titleSize = body * 1.35f
        val labelSize = body * 0.92f
        val padH = body * 0.9f
        val padV = body * 0.7f
        val headerH = titleSize * 2.1f
        val footerH = body * 1.55f
        val rowH = body * 1.75f
        val gap = body * 0.35f
        val radius = body * 0.7f

        val fields = buildFields(config, locationText, timeMs)
        // 两列：每列约 9 个汉字宽
        val colW = labelSize * 11f
        val cardW = (padH * 2f + colW * 2f + gap).coerceIn(
            MIN_CARD_W * scale,
            areaWidth * MAX_CARD_W_RATIO
        )
        val fullRows = fields.size
        val bodyH = padV + fullRows * rowH + padV
        val cardH = headerH + bodyH + footerH

        val (left, top) = resolveOrigin(config, areaWidth, areaHeight, cardW, cardH)
        return CardMetrics(
            left = left,
            top = top,
            width = cardW,
            height = cardH,
            paddingH = padH,
            paddingV = padV,
            fontSize = body,
            lineSpacing = gap,
            radius = radius,
            lines = emptyList()
        )
    }

    private fun drawFormCard(
        canvas: Canvas,
        m: CardMetrics,
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long
    ) {
        val tmpl = config.template
        val headerColor = withAlpha(tmpl.resolvedHeaderColor(), config.transparency)
        val footerColor = headerColor
        val bodyColor = withAlpha(0xFFFFFFFF.toInt(), (config.transparency * 0.96f).coerceIn(0.5f, 1f))
        val labelColor = withAlpha(headerColor or 0x00FFFFFF, 1f).let {
            // darken label toward header hue on white
            tmpl.resolvedHeaderColor()
        }
        val valueColor = 0xFF222222.toInt()
        val titleColor = 0xFFFFFFFF.toInt()
        val footerTextColor = 0xCCFFFFFF.toInt()

        val rect = RectF(m.left, m.top, m.left + m.width, m.top + m.height)
        val radius = m.radius

        // 整体圆角裁剪
        canvas.save()
        val clip = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)

        // 白底
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = bodyColor
        canvas.drawRect(rect, fillPaint)

        val titleSize = m.fontSize * 1.35f
        val headerH = titleSize * 2.1f
        val footerH = m.fontSize * 1.55f
        val bodyTop = m.top + headerH
        val bodyBottom = m.top + m.height - footerH

        // 表头
        fillPaint.color = headerColor
        canvas.drawRect(m.left, m.top, m.left + m.width, bodyTop, fillPaint)

        // 页脚
        fillPaint.color = footerColor
        canvas.drawRect(m.left, bodyBottom, m.left + m.width, m.top + m.height, fillPaint)

        canvas.restore()

        // 描边
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = maxOf(1.5f, m.fontSize * 0.06f)
        fillPaint.color = withAlpha(tmpl.resolvedHeaderColor(), 0.85f)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        fillPaint.style = Paint.Style.FILL

        // 标题
        val formSpec = TemplateFormCatalog.specOf(tmpl)
        val title = if (tmpl == WatermarkTemplate.GENERAL && config.customTitle.isNotBlank()) config.customTitle.trim() else formSpec.title
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = titleSize
        textPaint.color = titleColor
        textPaint.textAlign = Paint.Align.LEFT
        val titleX = m.left + m.paddingH + m.fontSize * 1.6f
        val titleY = m.top + headerH * 0.62f + titleSize * 0.15f
        canvas.drawText(title, titleX, titleY, textPaint)

        // 简易图标圆点
        accentPaint.color = 0x33FFFFFF
        canvas.drawCircle(
            m.left + m.paddingH * 0.85f + m.fontSize * 0.35f,
            m.top + headerH * 0.5f,
            m.fontSize * 0.55f,
            accentPaint
        )
        accentPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(
            m.left + m.paddingH * 0.85f + m.fontSize * 0.35f,
            m.top + headerH * 0.5f,
            m.fontSize * 0.28f,
            accentPaint
        )

        // 字段
        val fields = buildFields(config, locationText, timeMs)
        val labelSize = m.fontSize * 0.92f
        val valueSize = m.fontSize * 0.95f
        val rowH = m.fontSize * 1.75f
        val colMid = m.left + m.width / 2f
        val leftColX = m.left + m.paddingH
        val rightColX = colMid + m.paddingH * 0.4f
        var rowY = bodyTop + m.paddingV + labelSize

        textPaint.textAlign = Paint.Align.LEFT
        for (row in fields) {
            drawField(canvas, leftColX, rowY, row.leftLabel, row.leftValue, labelSize, valueSize, labelColor, valueColor, config.timeStyle)
            if (row.rightLabel != null) {
                drawField(
                    canvas, rightColX, rowY, row.rightLabel, row.rightValue.orEmpty(),
                    labelSize, valueSize, labelColor, valueColor, config.timeStyle
                )
            }
            rowY += rowH
        }

        // 页脚标语
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = m.fontSize * 0.78f
        textPaint.color = footerTextColor
        textPaint.textAlign = Paint.Align.CENTER
        val slogan = formSpec.slogan.ifBlank { tmpl.footerSlogan.ifBlank { tmpl.description } }
        val sloganY = bodyBottom + footerH * 0.62f
        canvas.drawText(slogan, m.left + m.width / 2f, sloganY, textPaint)
    }

    private fun drawField(
        canvas: Canvas,
        x: Float,
        baseline: Float,
        label: String,
        value: String,
        labelSize: Float,
        valueSize: Float,
        labelColor: Int,
        valueColor: Int,
        timeStyle: TimeStyle
    ) {
        // 小竖条
        accentPaint.color = labelColor
        canvas.drawRoundRect(
            RectF(x, baseline - labelSize * 0.75f, x + labelSize * 0.18f, baseline + labelSize * 0.15f),
            2f, 2f, accentPaint
        )
        val textX = x + labelSize * 0.4f
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = labelSize
        textPaint.color = labelColor
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("$label ", textX, baseline, textPaint)
        val labelW = textPaint.measureText("$label ")
        // 值：时间相关可用花样字体
        val isTime = label.contains("时间")
        textPaint.typeface = if (isTime) typefaceFor(timeStyle, false) else Typeface.DEFAULT
        textPaint.textSize = valueSize
        textPaint.color = valueColor
        val display = value.ifBlank { "—" }
        canvas.drawText(display, textX + labelW, baseline, textPaint)
    }

    /**
     * 按模板生成双列字段（对齐设计稿）。
     */
    private fun buildFields(
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long
    ): List<FieldRow> {
        val values = mapOf(
            "time" to formatTime(config.timeStyle, timeMs),
            "location" to locationText.ifBlank { "定位中…" },
            "name" to config.name.ifBlank { "—" },
            "project" to config.projectName.ifBlank { "—" },
            "remark" to config.remark.ifBlank { "—" },
            "content" to config.customTitle.ifBlank { config.remark.ifBlank { "—" } },
            "status" to (config.customTitle.ifBlank { "正常" })
        )
        val spec = TemplateFormCatalog.specOf(config.template)
        return spec.rows.map { (left, right) ->
            FieldRow(
                leftLabel = left.label,
                leftValue = values[left.key] ?: "—",
                rightLabel = right?.label,
                rightValue = right?.let { values[it.key] ?: "—" }
            )
        }
    }


    private fun formatTime(style: TimeStyle, timeMs: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timeMs))
        val time = when (style) {
            TimeStyle.DIGITAL_TUBE -> SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs))
            TimeStyle.FLIP_CALENDAR -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeMs))
            TimeStyle.RETRO_SLASH -> SimpleDateFormat("HH/mm/ss", Locale.CHINA).format(Date(timeMs))
            else -> SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs))
        }
        return "$date $time"
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

    private fun withAlpha(color: Int, alpha01: Float): Int {
        val a = (alpha01.coerceIn(0.2f, 1f) * 255).toInt().coerceIn(40, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
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
}
