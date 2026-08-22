package com.watermark.camera.data.watermark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.util.OrientationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom View for displaying watermark overlay on camera preview.
 *
 * Renders a real-time preview of the watermark that will appear on captured photos.
 * Updates automatically when configuration changes.
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BASE_FONT_SIZE = 13f
        private const val BASE_PADDING = 12f
        private const val BASE_LINE_SPACING = 4f
        private const val BASE_CARD_RADIUS = 12f
    }

    /**
     * Current watermark configuration.
     */
    var watermarkConfig: WatermarkConfig = WatermarkConfig()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Current location string for display.
     */
    var locationText: String = "未开启定位权限"
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether to show the watermark (can be toggled).
     */
    var isWatermarkVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Current device orientation for adaptive positioning.
     * Updated by OrientationHelper in CameraFragment.
     */
    var deviceOrientation: OrientationHelper.DeviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT
        set(value) {
            field = value
            invalidate()
        }


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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BASE_FONT_SIZE
        typeface = Typeface.DEFAULT_BOLD
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26000000") // 15% black
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#4DFFFFFF") // 30% white
    }

    private val glassRenderer = GlassmorphismRenderer()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isWatermarkVisible) return

        val scale = watermarkConfig.fontScale.coerceIn(0.5f, 3.0f)
        textPaint.textSize = BASE_FONT_SIZE * scale * resources.displayMetrics.scaledDensity

        val lines = buildPreviewLines()
        if (lines.isEmpty()) return

        // Measure text
        var maxWidth = 0f
        var totalHeight = 0f
        val bounds = android.graphics.Rect()

        for ((index, line) in lines.withIndex()) {
            textPaint.getTextBounds(line, 0, line.length, bounds)
            maxWidth = maxOf(maxWidth, bounds.width().toFloat())
            totalHeight += textPaint.textSize
            if (index < lines.size - 1) {
                totalHeight += BASE_LINE_SPACING
            }
        }

        val cardWidth = maxWidth + BASE_PADDING * 2
        val cardHeight = totalHeight + BASE_PADDING * 2

        // Calculate position based on device orientation and user config
        val (cardLeft, cardTop) = calculatePosition(cardWidth, cardHeight)

        // Draw card background
        glassRenderer.drawGlassCard(
            canvas = canvas,
            left = cardLeft,
            top = cardTop,
            width = cardWidth.toInt(),
            height = cardHeight.toInt(),
            radius = BASE_CARD_RADIUS,
            borderWidth = 1f,
            transparency = watermarkConfig.transparency,
            template = watermarkConfig.template
        )

        // Draw text (rotate canvas for landscape orientations)
        val saveCount = canvas.save()
        canvas.translate(cardLeft + BASE_PADDING, cardTop + BASE_PADDING + textPaint.textSize)

        // Rotate text for landscape to keep readable
        when (deviceOrientation) {
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> {
                canvas.rotate(90f, 0f, 0f)
            }
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> {
                canvas.rotate(-90f, 0f, 0f)
            }
            else -> {} // No rotation for portrait/upside down
        }

        var currentY = 0f
        for (line in lines) {
            canvas.drawText(line, 0f, currentY, textPaint)
            currentY += textPaint.textSize + BASE_LINE_SPACING
        }
        canvas.restoreToCount(saveCount)
    }

    /**
     * Build text lines for preview display.
     */
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
            if (loc.isNotBlank()) {
                lines.add("● $loc")
            }
        }

        if (watermarkConfig.name.isNotBlank()) {
            lines.add("汇报人: ${watermarkConfig.name}")
        }

        return lines
    }

    /**
     * Calculate watermark position based on device orientation and user config.
     */
    private fun calculatePosition(cardWidth: Float, cardHeight: Float): Pair<Float, Float> {
        val padding = BASE_PADDING

        return when (deviceOrientation) {
            OrientationHelper.DeviceOrientation.PORTRAIT -> {
                when (watermarkConfig.position) {
                    WatermarkPosition.BOTTOM_LEFT -> padding to (height - cardHeight - padding * 8)
                    WatermarkPosition.BOTTOM_RIGHT -> (width - cardWidth - padding) to (height - cardHeight - padding * 8)
                    WatermarkPosition.TOP_LEFT -> padding to padding
                    WatermarkPosition.TOP_RIGHT -> (width - cardWidth - padding) to padding
                    WatermarkPosition.CENTER -> (width - cardWidth) / 2 to (height - cardHeight) / 2
                }
            }
            OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> {
                (width - cardHeight - padding) to padding
            }
            OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> {
                padding to (height - cardWidth - padding)
            }
            OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> {
                padding to padding
            }
            OrientationHelper.DeviceOrientation.UNKNOWN -> {
                padding to (height - cardHeight - padding * 8)
            }
        }
    }

    /**
     * Update location text from external source.
     */
    fun updateLocation(location: String?) {
        locationText = location ?: "位置获取中…"
    }
}
