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
 * Preview watermark: same layout model as [WatermarkCanvas], free-drag inside viewfinder.
 * Text stays upright relative to device gravity when orientation changes.
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

    var locationText: String = "定位中…"
        set(value) {
            field = value
            invalidate()
        }

    var isWatermarkVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var deviceOrientation: OrientationHelper.DeviceOrientation =
        OrientationHelper.DeviceOrientation.PORTRAIT
        set(value) {
            field = value
            invalidate()
        }

    /** Continuous drag updates (normalized customX/Y + nearest slot). */
    var onDragPosition: ((Float, Float, WatermarkPosition) -> Unit)? = null

    /** Final commit after finger up. */
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
    private var lastMargin = 8f

    private var dragging = false
    private var grabDx = 0f
    private var grabDy = 0f

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

        val scaleFactor = width / BASE_WIDTH
        val fontScale = watermarkConfig.fontScale.coerceIn(0.5f, 8.0f)
        val fontSize = (BASE_FONT_SIZE * scaleFactor * fontScale).coerceIn(12f, 96f)
        val padding = (BASE_PADDING * scaleFactor).coerceAtLeast(8f)
        val lineSpacing = (BASE_LINE_SPACING * scaleFactor).coerceAtLeast(2f)
        val radius = (BASE_CARD_RADIUS * scaleFactor).coerceAtLeast(8f)
        lastMargin = 0f

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
        // margin=0 so watermark can reach viewfinder edges (same as save path)
        val (cardLeft, cardTop) = WatermarkLayout.cardOrigin(
            config = watermarkConfig,
            areaWidth = width.toFloat(),
            areaHeight = height.toFloat(),
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            margin = 0f
        )
        lastCardLeft = cardLeft
        lastCardTop = cardTop
        lastCardW = cardWidth
        lastCardH = cardHeight

        val save = canvas.save()
        // Keep watermark text upright relative to gravity when device rotates
        val rotation = uprightRotationDegrees()
        if (rotation != 0f) {
            val cx = cardLeft + cardWidth / 2f
            val cy = cardTop + cardHeight / 2f
            canvas.rotate(rotation, cx, cy)
        }

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
        canvas.restoreToCount(save)
    }

    private fun uprightRotationDegrees(): Float {
        return when (deviceOrientation) {
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 90f
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> -90f
            OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180f
            else -> 0f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = event.x >= lastCardLeft - 24 &&
                    event.x <= lastCardLeft + lastCardW + 24 &&
                    event.y >= lastCardTop - 24 &&
                    event.y <= lastCardTop + lastCardH + 24
                if (hit) {
                    dragging = true
                    grabDx = event.x - lastCardLeft
                    grabDy = event.y - lastCardTop
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val rawL = event.x - grabDx
                val rawT = event.y - grabDy
                val (clampedL, clampedT) = WatermarkLayout.clampOrigin(
                    rawL, rawT,
                    width.toFloat(), height.toFloat(),
                    lastCardW, lastCardH, 0f
                )
                val (nx, ny) = WatermarkLayout.toNormalized(
                    clampedL, clampedT,
                    width.toFloat(), height.toFloat(),
                    lastCardW, lastCardH, 0f
                )
                val slot = WatermarkLayout.nearestSlot(
                    clampedL + lastCardW / 2f,
                    clampedT + lastCardH / 2f,
                    width.toFloat(), height.toFloat()
                )
                watermarkConfig = watermarkConfig.copy(
                    customX = nx,
                    customY = ny,
                    position = slot
                )
                onDragPosition?.invoke(nx, ny, slot)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onPositionChanged?.invoke(watermarkConfig.position)
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
            val loc = locationText.ifBlank { watermarkConfig.location }.ifBlank { "定位中…" }
            lines.add("● $loc")
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
