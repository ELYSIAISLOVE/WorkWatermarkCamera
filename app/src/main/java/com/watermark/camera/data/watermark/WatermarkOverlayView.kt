package com.watermark.camera.data.watermark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.util.OrientationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera preview watermark. Layout math matches [WatermarkCanvas] via [WatermarkLayout]
 * so preview position/size tracks the final saved image (fixed 4:3 framing).
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BASE_WIDTH = 1080f
        private const val BASE_FONT_SIZE = 28f
        private const val BASE_PADDING = 20f
        private const val BASE_LINE_SPACING = 8f
        private const val BASE_CARD_RADIUS = 16f
    }

    var watermarkConfig: WatermarkConfig = WatermarkConfig()
        set(value) {
            field = value
            invalidate()
        }

    var locationText: String = "未开启定位权限"
        set(value) {
            field = value
            invalidate()
        }

    var isWatermarkVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Device orientation (for future use); placement still follows [WatermarkConfig.position]. */
    var deviceOrientation: OrientationHelper.DeviceOrientation =
        OrientationHelper.DeviceOrientation.PORTRAIT
        set(value) {
            field = value
            invalidate()
        }

    /** Called when user drags/taps to change watermark slot. */
    var onPositionChanged: ((WatermarkPosition) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    private val glassRenderer = GlassmorphismRenderer()

    private var lastCardLeft = 0f
    private var lastCardTop = 0f
    private var lastCardW = 0f
    private var lastCardH = 0f

    private val clockRunnable = object : Runnable {
        override fun run() {
            if (isAttachedToWindow) {
                invalidate()
                postDelayed(this, 1000L)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(clockRunnable)
        post(clockRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clockRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isWatermarkVisible || width <= 0 || height <= 0) return

        val lines = buildPreviewLines()
        if (lines.isEmpty()) return

        // Same scale model as WatermarkCanvas: relative to area width (~ photo width)
        val scaleFactor = width / BASE_WIDTH
        val fontScale = watermarkConfig.fontScale.coerceIn(0.5f, 8.0f)
        val fontSize = (BASE_FONT_SIZE * scaleFactor * fontScale).coerceIn(12f, 96f)
        val padding = (BASE_PADDING * scaleFactor).coerceAtLeast(8f)
        val lineSpacing = (BASE_LINE_SPACING * scaleFactor).coerceAtLeast(2f)
        val radius = (BASE_CARD_RADIUS * scaleFactor).coerceAtLeast(8f)

        textPaint.textSize = fontSize
        textPaint.color = Color.WHITE

        var maxWidth = 0f
        var totalHeight = 0f
        val bounds = android.graphics.Rect()
        for ((index, line) in lines.withIndex()) {
            textPaint.getTextBounds(line, 0, line.length, bounds)
            maxWidth = maxOf(maxWidth, bounds.width().toFloat())
            totalHeight += fontSize
            if (index < lines.size - 1) totalHeight += lineSpacing
        }

        val cardWidth = maxWidth + padding * 2
        val cardHeight = totalHeight + padding * 2
        val (cardLeft, cardTop) = WatermarkLayout.cardOrigin(
            position = watermarkConfig.position,
            areaWidth = width.toFloat(),
            areaHeight = height.toFloat(),
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            margin = padding
        )
        lastCardLeft = cardLeft
        lastCardTop = cardTop
        lastCardW = cardWidth
        lastCardH = cardHeight

        glassRenderer.drawGlassCard(
            canvas = canvas,
            left = cardLeft,
            top = cardTop,
            width = cardWidth.toInt().coerceAtLeast(1),
            height = cardHeight.toInt().coerceAtLeast(1),
            radius = radius,
            borderWidth = 1f,
            transparency = watermarkConfig.transparency,
            template = watermarkConfig.template
        )

        var currentY = cardTop + padding + fontSize
        for (line in lines) {
            canvas.drawText(line, cardLeft + padding, currentY, textPaint)
            currentY += fontSize + lineSpacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inside =
                    event.x >= lastCardLeft && event.x <= lastCardLeft + lastCardW &&
                        event.y >= lastCardTop && event.y <= lastCardTop + lastCardH
                return if (inside || true) {
                    // Allow tap anywhere on overlay to snap position
                    true
                } else {
                    super.onTouchEvent(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                val pos = WatermarkLayout.positionFromTouch(
                    event.x, event.y, width.toFloat(), height.toFloat()
                )
                if (pos != watermarkConfig.position) {
                    watermarkConfig = watermarkConfig.copy(position = pos)
                    onPositionChanged?.invoke(pos)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun buildPreviewLines(): List<String> {
        val lines = mutableListOf<String>()
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val weekFormat = SimpleDateFormat("EEEE", Locale.CHINA)

        lines.add(watermarkConfig.template.displayName + "水印")
        lines.add("${timeFormat.format(now)} | ${dateFormat.format(now)}")
        lines.add(weekFormat.format(now))

        if (watermarkConfig.showLocation) {
            val loc = locationText.ifBlank { watermarkConfig.location }
            if (loc.isNotBlank()) lines.add("● $loc")
        }
        if (watermarkConfig.name.isNotBlank()) {
            lines.add("汇报人: ${watermarkConfig.name}")
        }
        return lines
    }

    fun updateLocation(location: String?) {
        locationText = location ?: "位置获取中…"
    }
}
