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
 * 单列紧凑表单水印（预览 + 成片同一路径）。
 * 彩色表头(含 Logo) + 白底单列字段 + 彩色页脚。
 */
class WatermarkRenderer {

    companion object {
        private const val BASE_SHORT = 1080f
        /** 卡片最大约占短边宽度比例 — 缩小体积 */
        private const val MAX_CARD_W_RATIO = 0.92f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 兼容旧调用方 / 拖拽命中 */
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

    private data class Row(val label: String, val value: String, val isTime: Boolean = false)

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
        // 参考「马克水印相机」：
        // 预览 + 陀螺仪开启 + 非竖持：旋转卡片，使文字相对重力正立，并贴重力朝下的边。
        // 成片路径传 PORTRAIT：图已转正，不旋转，贴图像底边。
        val gyro = config.useGyroscope
        val orient = if (gyro) deviceOrientation else OrientationHelper.DeviceOrientation.PORTRAIT
        val degrees = if (!gyro) 0f else when (orient) {
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 90f
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> -90f
            OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180f
            else -> 0f
        }

        if (degrees == 0f) {
            val effective = applyGravityEdge(config, orient)
            val m = measure(areaWidth, areaHeight, effective, locationText, timeMs)
            if (m != null) drawCard(canvas, m, effective, locationText, timeMs)
            return m
        }

        // 绕中心旋转后，90° 时宽高对调；再平移使旋转坐标系的 (0,0) 落在新的左上角。
        val drawW: Float
        val drawH: Float
        val tx: Float
        val ty: Float
        if (degrees == 90f || degrees == -90f) {
            drawW = areaHeight
            drawH = areaWidth
            tx = (areaWidth - areaHeight) / 2f
            ty = (areaHeight - areaWidth) / 2f
        } else {
            drawW = areaWidth
            drawH = areaHeight
            tx = 0f
            ty = 0f
        }
        val placed = config.copy(
            position = com.watermark.camera.data.model.WatermarkPosition.BOTTOM_LEFT,
            customX = null,
            customY = null
        )
        val m = measure(drawW, drawH, placed, locationText, timeMs)
        canvas.save()
        canvas.rotate(degrees, areaWidth / 2f, areaHeight / 2f)
        canvas.translate(tx, ty)
        if (m != null) drawCard(canvas, m, placed, locationText, timeMs)
        canvas.restore()
        return m
    }

    /**
     * 陀螺仪「跟重力贴边」——与 OrientationHelper 注释一致：
     * - PORTRAIT: 底边
     * - LANDSCAPE_LEFT (x>0, 顺时针 90°): **右边**
     * - LANDSCAPE_RIGHT (x<0, 逆时针 90°): **左边**
     * - UPSIDE_DOWN: 顶边
     *
     * 开启陀螺仪时忽略 customX/Y（拖拽坐标），始终跟重力；
     * 关闭陀螺仪后才使用用户拖拽/角落设定。
     *
     * 成片请传 PORTRAIT：bitmap 已转正，重力底 = 图底 → BOTTOM_*。
     */
    fun applyGravityEdge(
        config: WatermarkConfig,
        orientation: OrientationHelper.DeviceOrientation
    ): WatermarkConfig {
        if (!config.useGyroscope) return config
        // 开启陀螺仪：强制跟重力，清掉拖拽坐标，避免卡在旧位置
        val preferRight = config.position == WatermarkPosition.TOP_RIGHT ||
            config.position == WatermarkPosition.BOTTOM_RIGHT
        val pos = when (orientation) {
            OrientationHelper.DeviceOrientation.PORTRAIT,
            OrientationHelper.DeviceOrientation.UNKNOWN ->
                if (preferRight) WatermarkPosition.BOTTOM_RIGHT else WatermarkPosition.BOTTOM_LEFT
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT ->
                // 顺时针 90°：右侧朝下 → 贴右边缘
                if (preferRight) WatermarkPosition.TOP_RIGHT else WatermarkPosition.BOTTOM_RIGHT
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT ->
                // 逆时针 90°：左侧朝下 → 贴左边缘
                if (preferRight) WatermarkPosition.TOP_LEFT else WatermarkPosition.BOTTOM_LEFT
            OrientationHelper.DeviceOrientation.UPSIDE_DOWN ->
                if (preferRight) WatermarkPosition.TOP_RIGHT else WatermarkPosition.TOP_LEFT
        }
        return config.copy(position = pos, customX = null, customY = null)
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
        val scale = (short / BASE_SHORT).coerceIn(0.32f, 1.5f)
        val user = config.clampedFontScale().coerceIn(0.85f, 2.2f)
        // 和谐比例：正文为基准，标题略大，行距宽松
        val body = (16.5f * scale * user).coerceIn(13f, 40f)
        val titleSize = body * 1.12f
        val headerH = titleSize * 2.4f
        val footerH = body * 1.15f
        val rowH = body * 1.48f
        val padH = body * 0.75f
        val padV = body * 0.45f
        val radius = body * 0.48f

        val rows = buildRows(config, locationText, timeMs)
        textPaint.textSize = body
        // 卡片尽量宽以完整显示地址（上限 92% 画面）
        val maxCardW = areaWidth * MAX_CARD_W_RATIO
        val minCardW = (body * 16f).coerceAtMost(maxCardW)
        var contentW = body * 12f
        for (r in rows) {
            val w = textPaint.measureText(r.label + r.value)
            if (w > contentW) contentW = w
        }
        val cardW = (padH * 2f + contentW + body * 0.8f).coerceIn(minCardW, maxCardW)
        val labelCol = estimateLabelCol(rows, body)
        val valueMaxW = (cardW - padH * 2f - labelCol).coerceAtLeast(body * 4f)
        var linesCount = 0
        for (r in rows) {
            linesCount += wrapLines(r.value.ifBlank { "—" }, valueMaxW, body).size.coerceAtLeast(1)
        }
        val bodyH = padV + linesCount * rowH + padV
        val cardH = headerH + bodyH + footerH
        val (left, top) = resolveOrigin(config, areaWidth, areaHeight, cardW, cardH)
        return CardMetrics(left, top, cardW, cardH, padH, padV, body, body * 0.18f, radius, emptyList())
    }

    private fun estimateLabelCol(rows: List<Row>, body: Float): Float {
        textPaint.textSize = body * 0.95f
        var col = body * 3.2f
        for (r in rows) col = maxOf(col, textPaint.measureText(r.label))
        return col + body * 0.3f
    }

    /** 按宽度拆行，保证地址等长文本完整显示 */
    private fun wrapLines(text: String, maxW: Float, textSize: Float): List<String> {
        if (text.isEmpty()) return listOf("—")
        textPaint.textSize = textSize
        if (textPaint.measureText(text) <= maxW) return listOf(text)
        val lines = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var j = i + 1
            var lastOk = i + 1
            while (j <= text.length) {
                val chunk = text.substring(i, j)
                if (textPaint.measureText(chunk) <= maxW) {
                    lastOk = j
                    j++
                } else break
            }
            if (lastOk <= i) lastOk = (i + 1).coerceAtMost(text.length)
            lines.add(text.substring(i, lastOk))
            i = lastOk
            if (lines.size >= 6) { // 最多 6 行，防止极端长串撑爆
                if (i < text.length) {
                    val last = lines.removeLast()
                    lines.add(last) // 已完整切分到 lastOk
                }
                break
            }
        }
        return lines.ifEmpty { listOf(text) }
    }

    private fun drawCard(
        canvas: Canvas,
        m: CardMetrics,
        config: WatermarkConfig,
        locationText: String,
        timeMs: Long
    ) {
        val tmpl = config.template
        val spec = TemplateFormCatalog.specOf(tmpl)
        val headerColor = withAlpha(tmpl.resolvedHeaderColor(), config.transparency)
        val bodyColor = withAlpha(0xFFFFFFFF.toInt(), (config.transparency * 0.96f).coerceIn(0.55f, 1f))
        val labelColor = tmpl.resolvedHeaderColor()
        val valueColor = 0xFF222222.toInt()

        val rect = RectF(m.left, m.top, m.left + m.width, m.top + m.height)
        val titleSize = m.fontSize * 1.2f
        val headerH = titleSize * 2.55f
        val footerH = m.fontSize * 1.3f
        val bodyTop = m.top + headerH
        val bodyBottom = m.top + m.height - footerH

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = 0x33000000
        canvas.drawRoundRect(
            RectF(rect.left + 2f, rect.top + 3f, rect.right + 2f, rect.bottom + 4f),
            m.radius, m.radius, fillPaint
        )
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, m.radius, m.radius, Path.Direction.CW) })
        fillPaint.color = bodyColor
        canvas.drawRect(rect, fillPaint)

        // 表头底色
        fillPaint.color = headerColor
        canvas.drawRect(m.left, m.top, m.left + m.width, bodyTop, fillPaint)

        // 斜切分界：标题区 | 时间区
        val cutX = m.left + m.width * 0.52f
        val diag = Path().apply {
            moveTo(cutX - headerH * 0.25f, m.top)
            lineTo(cutX + headerH * 0.15f, bodyTop)
            lineTo(m.left + m.width, bodyTop)
            lineTo(m.left + m.width, m.top)
            close()
        }
        fillPaint.color = withAlpha(tmpl.resolvedHeaderColor(), (config.transparency * 0.88f).coerceIn(0.4f, 1f))
        // 右侧略深
        fillPaint.color = withAlpha(
            (tmpl.resolvedHeaderColor() and 0x00FFFFFF) or 0x000000,
            0.25f
        )
        // 用更深一点的叠色
        fillPaint.color = 0x33000000
        canvas.drawPath(diag, fillPaint)

        fillPaint.color = headerColor
        canvas.drawRect(m.left, bodyBottom, m.left + m.width, m.top + m.height, fillPaint)
        canvas.restore()

        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = maxOf(1.2f, m.fontSize * 0.05f)
        fillPaint.color = withAlpha(tmpl.resolvedHeaderColor(), 0.9f)
        canvas.drawRoundRect(rect, m.radius, m.radius, fillPaint)
        fillPaint.style = Paint.Style.FILL

        // 斜切亮线
        accentPaint.color = 0x66FFFFFF
        accentPaint.strokeWidth = maxOf(2f, m.fontSize * 0.06f)
        canvas.drawLine(
            cutX - headerH * 0.25f, m.top,
            cutX + headerH * 0.15f, bodyTop,
            accentPaint
        )

        // 表头分左右两栏：左标题、右时间，互不重叠
        // Logo 放大：半径约 0.78 * 正文字号（原 0.50）
        val iconR = m.fontSize * 0.78f
        val iconCx = m.left + m.paddingH + iconR
        val iconCy = m.top + headerH * 0.5f
        drawLogo(canvas, tmpl, iconCx, iconCy, iconR)
        val title = if ((tmpl == WatermarkTemplate.GENERAL || tmpl == WatermarkTemplate.WORK_REPORT)
            && config.customTitle.isNotBlank()
        ) config.customTitle.trim() else spec.title

        // 右栏预留时间宽度（约 5.5 个数字宽）
        val timeReserve = m.fontSize * 6.2f
        val titleMaxRight = m.left + m.width - m.paddingH - timeReserve - m.fontSize * 0.3f
        val titleStart = iconCx + iconR + m.fontSize * 0.3f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = titleSize
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textAlign = Paint.Align.LEFT
        var drawnTitle = title
        while (drawnTitle.isNotEmpty() && textPaint.measureText(drawnTitle) > (titleMaxRight - titleStart).coerceAtLeast(m.fontSize * 2f)) {
            drawnTitle = if (drawnTitle.length <= 1) "" else drawnTitle.dropLast(1)
        }
        if (drawnTitle != title && drawnTitle.isNotEmpty()) {
            drawnTitle = drawnTitle.dropLast(1).trimEnd() + "…"
        }
        canvas.drawText(
            drawnTitle,
            titleStart,
            m.top + headerH * 0.58f + titleSize * 0.08f,
            textPaint
        )

        // 右栏时间（只占右半，不压标题）
        drawHeaderTime(
            canvas,
            config.timeStyle,
            timeMs,
            m.left + m.width - m.paddingH,
            m.top + headerH * 0.48f,
            m.fontSize * 1.0f
        )

        // 正文：地点 + 字段（不再重复时间），字号加大、对齐
        val rows = buildRows(config, locationText, timeMs).filter { !it.isTime }
        val labelSize = m.fontSize * 0.95f
        val valueSize = m.fontSize * 1.0f
        val rowH = m.fontSize * 1.48f
        var y = bodyTop + m.paddingV + labelSize
        val tx = m.left + m.paddingH
        val labelCol = estimateLabelCol(rows, m.fontSize)
        val valueMaxW = (m.left + m.width - m.paddingH) - (tx + labelCol)
        for (row in rows) {
            accentPaint.color = labelColor
            canvas.drawRoundRect(
                RectF(
                    m.left + m.paddingH * 0.4f,
                    y - labelSize * 0.72f,
                    m.left + m.paddingH * 0.4f + labelSize * 0.14f,
                    y + labelSize * 0.08f
                ),
                2f, 2f, accentPaint
            )
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = labelSize
            textPaint.color = labelColor
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(row.label, tx, y, textPaint)
            textPaint.textSize = valueSize
            textPaint.color = valueColor
            val lines = wrapLines(row.value.ifBlank { "—" }, valueMaxW.coerceAtLeast(m.fontSize * 3f), valueSize)
            for ((idx, line) in lines.withIndex()) {
                canvas.drawText(line, tx + labelCol, y + idx * rowH, textPaint)
            }
            y += rowH * lines.size
        }

        // Footer
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = m.fontSize * 0.72f
        textPaint.color = 0xCCFFFFFF.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(spec.slogan, m.left + m.width / 2f, bodyBottom + footerH * 0.62f, textPaint)
    }

    private fun drawLogo(canvas: Canvas, t: WatermarkTemplate, cx: Float, cy: Float, r: Float) {
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = 0x33FFFFFF
        canvas.drawCircle(cx, cy, r, iconPaint)
        iconPaint.color = 0xFFFFFFFF.toInt()
        iconPaint.strokeWidth = maxOf(1.4f, r * 0.11f)
        iconPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.strokeJoin = Paint.Join.ROUND
        val p = Path()
        when (t) {
            WatermarkTemplate.PROPERTY_INSPECTION -> {
                p.moveTo(cx, cy - r * 0.7f)
                p.lineTo(cx + r * 0.55f, cy - r * 0.35f)
                p.lineTo(cx + r * 0.45f, cy + r * 0.25f)
                p.quadTo(cx, cy + r * 0.75f, cx - r * 0.45f, cy + r * 0.25f)
                p.lineTo(cx - r * 0.55f, cy - r * 0.35f)
                p.close()
                canvas.drawPath(p, iconPaint)
            }
            WatermarkTemplate.DUTY -> {
                canvas.drawCircle(cx, cy - r * 0.28f, r * 0.28f, iconPaint)
                p.addOval(RectF(cx - r * 0.45f, cy + r * 0.05f, cx + r * 0.45f, cy + r * 0.7f), Path.Direction.CW)
                canvas.drawPath(p, iconPaint)
            }
            WatermarkTemplate.ENGINEERING -> {
                canvas.drawArc(RectF(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.25f), 180f, 180f, true, iconPaint)
                canvas.drawRect(cx - r * 0.65f, cy + r * 0.05f, cx + r * 0.65f, cy + r * 0.28f, iconPaint)
            }
            WatermarkTemplate.ATTENDANCE -> {
                iconPaint.style = Paint.Style.STROKE
                canvas.drawRoundRect(RectF(cx - r * 0.5f, cy - r * 0.4f, cx + r * 0.5f, cy + r * 0.5f), r * 0.1f, r * 0.1f, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy - r * 0.1f, cx + r * 0.5f, cy - r * 0.1f, iconPaint)
                iconPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx - r * 0.18f, cy + r * 0.18f, r * 0.08f, iconPaint)
                canvas.drawCircle(cx + r * 0.18f, cy + r * 0.18f, r * 0.08f, iconPaint)
            }
            WatermarkTemplate.EVIDENCE -> {
                iconPaint.style = Paint.Style.STROKE
                canvas.drawRoundRect(RectF(cx - r * 0.55f, cy - r * 0.35f, cx + r * 0.55f, cy + r * 0.4f), r * 0.12f, r * 0.12f, iconPaint)
                canvas.drawCircle(cx, cy + r * 0.02f, r * 0.22f, iconPaint)
                iconPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + r * 0.02f, r * 0.1f, iconPaint)
            }
            else -> {
                val s = r * 0.28f
                val g = r * 0.12f
                canvas.drawRoundRect(RectF(cx - s - g, cy - s - g, cx - g, cy - g), s * 0.2f, s * 0.2f, iconPaint)
                canvas.drawRoundRect(RectF(cx + g, cy - s - g, cx + s + g, cy - g), s * 0.2f, s * 0.2f, iconPaint)
                canvas.drawRoundRect(RectF(cx - s - g, cy + g, cx - g, cy + s + g), s * 0.2f, s * 0.2f, iconPaint)
                canvas.drawRoundRect(RectF(cx + g, cy + g, cx + s + g, cy + s + g), s * 0.2f, s * 0.2f, iconPaint)
            }
        }
    }

    private fun buildRows(config: WatermarkConfig, locationText: String, timeMs: Long): List<Row> {
        val tmpl = config.template
        val rows = mutableListOf<Row>()
        // 日期在白色正文；时间只在顶栏
        rows.add(Row("日期: ", formatDateOnly(config.timeStyle, timeMs), isTime = false))
        if (config.showLocation) {
            rows.add(Row("地点: ", locationText.ifBlank { "定位中…" }))
        }
        for (f in TemplateFormCatalog.specOf(tmpl).editable) {
            if (f.key == "wm_name") continue
            val v = TemplateFormCatalog.readField(config, tmpl, f.key)
            rows.add(Row("${f.label}: ", v.ifBlank { "—" }))
        }
        return rows
    }

    private fun formatTime(style: TimeStyle, timeMs: Long): String {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timeMs))
        val hm = when (style) {
            TimeStyle.FLIP_CALENDAR -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeMs))
            TimeStyle.RETRO_SLASH -> toHanDigits(SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs)))
            else -> SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs))
        }
        return if (style == TimeStyle.RETRO_SLASH) {
            toHanDigits(d.replace("-", "")) + " " + hm
        } else "$d $hm"
    }

    private fun formatTimeOnly(style: TimeStyle, timeMs: Long): String {
        return when (style) {
            TimeStyle.FLIP_CALENDAR -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeMs))
            TimeStyle.RETRO_SLASH -> toHanDigits(SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs)))
            else -> SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timeMs))
        }
    }

    private fun formatDateOnly(style: TimeStyle, timeMs: Long): String {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timeMs))
        return if (style == TimeStyle.RETRO_SLASH) toHanDigits(d) else d
    }

    private fun toHanDigits(s: String): String {
        val map = charArrayOf('〇', '一', '二', '三', '四', '五', '六', '七', '八', '九')
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                in '0'..'9' -> sb.append(map[c - '0'])
                ':' -> sb.append('：')
                '-' -> sb.append('－')
                '/' -> sb.append('／')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun typefaceFor(style: TimeStyle): Typeface = when (style) {
        TimeStyle.DIGITAL_TUBE -> Typeface.MONOSPACE
        TimeStyle.FLIP_CALENDAR -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        TimeStyle.RETRO_SLASH -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else -> Typeface.DEFAULT_BOLD
    }

    /** 顶部栏右侧大号时间（数字样式） */
    private fun drawHeaderTime(
        canvas: Canvas,
        style: TimeStyle,
        timeMs: Long,
        right: Float,
        cy: Float,
        fontSize: Float
    ) {
        val timeStr = formatTimeOnly(style, timeMs)
        val dateStr = formatDateOnly(style, timeMs)
        val tp = textPaint
        tp.textAlign = Paint.Align.RIGHT
        when (style) {
            TimeStyle.DIGITAL_TUBE -> {
                tp.typeface = Typeface.MONOSPACE
                tp.color = 0xFF00E676.toInt()
                tp.textSize = fontSize * 1.35f
                canvas.drawText(timeStr, right, cy + fontSize * 0.35f, tp)
            }
            TimeStyle.FLIP_CALENDAR -> {
                // 翻页块
                val digitH = fontSize * 1.15f
                val digitW = fontSize * 0.72f
                val gap = fontSize * 0.08f
                var x = right
                for (i in timeStr.length - 1 downTo 0) {
                    val ch = timeStr[i].toString()
                    if (ch == ":") {
                        tp.color = 0xFFFFFFFF.toInt()
                        tp.textSize = fontSize * 0.9f
                        tp.typeface = Typeface.DEFAULT_BOLD
                        canvas.drawText(":", x, cy + fontSize * 0.2f, tp)
                        x -= fontSize * 0.35f
                        continue
                    }
                    val left = x - digitW
                    fillPaint.color = 0xFF1A1A1A.toInt()
                    canvas.drawRoundRect(RectF(left, cy - digitH * 0.55f, x, cy + digitH * 0.45f), 6f, 6f, fillPaint)
                    fillPaint.color = 0xFF333333.toInt()
                    canvas.drawRect(left, cy - 1f, x, cy + 1f, fillPaint)
                    tp.color = 0xFFFFFFFF.toInt()
                    tp.textSize = fontSize * 0.85f
                    tp.typeface = Typeface.DEFAULT_BOLD
                    tp.textAlign = Paint.Align.CENTER
                    canvas.drawText(ch, (left + x) / 2f, cy + fontSize * 0.28f, tp)
                    tp.textAlign = Paint.Align.RIGHT
                    x = left - gap
                }
            }
            TimeStyle.RETRO_SLASH -> {
                tp.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                tp.color = 0xFFFFFFFF.toInt()
                tp.textSize = fontSize * 1.05f
                canvas.drawText(timeStr, right, cy + fontSize * 0.35f, tp)
            }
            else -> {
                tp.typeface = Typeface.DEFAULT_BOLD
                tp.color = 0xFFFFFFFF.toInt()
                tp.textSize = fontSize * 1.45f
                canvas.drawText(timeStr, right, cy + fontSize * 0.35f, tp)
            }
        }
        tp.textAlign = Paint.Align.LEFT
    }


    private fun withAlpha(color: Int, a01: Float): Int {
        val a = (a01.coerceIn(0.25f, 1f) * 255).toInt().coerceIn(50, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun resolveOrigin(
        config: WatermarkConfig,
        areaWidth: Float,
        areaHeight: Float,
        cardWidth: Float,
        cardHeight: Float
    ): Pair<Float, Float> {
        // 与边缘留一点间距，横竖屏都更贴「角」且不贴死边
        val edge = minOf(areaWidth, areaHeight) * 0.02f
        val maxL = (areaWidth - cardWidth - edge).coerceAtLeast(0f)
        val maxT = (areaHeight - cardHeight - edge).coerceAtLeast(0f)
        val cx = config.customX
        val cy = config.customY
        if (cx != null && cy != null) {
            return (edge + cx.coerceIn(0f, 1f) * maxL) to (edge + cy.coerceIn(0f, 1f) * maxT)
        }
        return when (config.position) {
            WatermarkPosition.TOP_LEFT -> edge to edge
            WatermarkPosition.TOP_RIGHT -> (edge + maxL) to edge
            WatermarkPosition.BOTTOM_RIGHT -> (edge + maxL) to (edge + maxT)
            WatermarkPosition.CENTER -> (edge + maxL / 2f) to (edge + maxT / 2f)
            else -> edge to (edge + maxT) // BOTTOM_LEFT
        }
    }
}
