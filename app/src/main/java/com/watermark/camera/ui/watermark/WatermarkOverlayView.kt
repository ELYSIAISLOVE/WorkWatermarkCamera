package com.watermark.camera.ui.watermark

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.watermark.InteractiveWatermarkLayer
import com.watermark.camera.data.watermark.WatermarkCanvas
import com.watermark.camera.util.Logger

/**
 * Interactive watermark overlay view for camera preview.
 *
 * Features:
 * - Real-time watermark preview with glassmorphism
 * - Touch-based drag and resize functionality
 * - Responsive to configuration changes
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "WatermarkOverlayView"
    }

    private val watermarkCanvas = WatermarkCanvas()
    private val interactiveLayer = InteractiveWatermarkLayer()
    
    var watermarkConfig: WatermarkConfig = WatermarkConfig()
        set(value) {
            field = value
            invalidate()
            Logger.i(TAG, "Watermark config updated: ${value.template.displayName}")
        }

    var locationStr: String = ""
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw watermark preview text
        val (estimatedWidth, estimatedHeight) = watermarkCanvas.estimateDimensions(
            photoWidth = 1080,
            config = watermarkConfig,
            locationStr = locationStr
        )

        // Position watermark based on interactive layer
        val bounds = interactiveLayer.getBounds()
        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        
        // Draw preview text with glassmorphism
        drawPreviewText(canvas)
        
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = interactiveLayer.onTouchEvent(
            event = event,
            previewWidth = width,
            previewHeight = height
        )
        
        if (consumed) {
            invalidate()
            Logger.d(TAG, "Watermark moved/resized")
        }
        
        return consumed
    }

    /**
     * Draw preview text with watermark configuration.
     */
    private fun drawPreviewText(canvas: Canvas) {
        val previewPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val lines = listOf(
            "${watermarkConfig.template.displayName}水印",
            java.text.SimpleDateFormat("HH:mm | yyyy/MM/dd", java.util.Locale.getDefault())
                .format(java.util.Date()),
            watermarkConfig.name.ifBlank { "Sample" }
        )

        var y = 16f
        for (line in lines) {
            canvas.drawText(line, 12f, y, previewPaint)
            y += 16f
        }
    }
}
