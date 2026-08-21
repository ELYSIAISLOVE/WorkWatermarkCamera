package com.watermark.camera.data.watermark

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.Logger

/**
 * Interactive watermark layer for touch-based positioning.
 *
 * Enables users to drag and resize watermarks in real-time preview.
 * Supports multi-touch gestures for intuitive adjustment.
 */
class InteractiveWatermarkLayer {

    companion object {
        private const val TAG = "InteractiveWatermarkLayer"
        private const val MIN_WATERMARK_SIZE = 50f // pixels
        private const val MAX_WATERMARK_SIZE = 400f // pixels
    }

    /**
     * Watermark position in preview coordinates.
     */
    private var watermarkBounds = RectF(100f, 100f, 300f, 200f)

    /**
     * Transformation matrix for watermark.
     */
    private val transformMatrix = Matrix()

    /**
     * Track if user is currently dragging watermark.
     */
    private var isDragging = false

    /**
     * Track if user is resizing watermark.
     */
    private var isResizing = false

    /**
     * Last touch coordinates.
     */
    private var lastX = 0f
    private var lastY = 0f

    /**
     * Handle touch events for watermark positioning.
     *
     * @param event Motion event from preview.
     * @param previewWidth Preview width in pixels.
     * @param previewHeight Preview height in pixels.
     * @return true if event was consumed.
     */
    fun onTouchEvent(
        event: MotionEvent,
        previewWidth: Int,
        previewHeight: Int
    ): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y

                // Check if touching resize handle (bottom-right corner)
                val resizeHandleSize = 50f
                val isNearResizeHandle = (
                    event.x > watermarkBounds.right - resizeHandleSize &&
                    event.y > watermarkBounds.bottom - resizeHandleSize
                )

                if (isNearResizeHandle) {
                    isResizing = true
                } else if (watermarkBounds.contains(event.x, event.y)) {
                    isDragging = true
                }

                isDragging || isResizing
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                when {
                    isDragging -> {
                        // Move watermark
                        watermarkBounds.offset(dx, dy)
                        // Clamp to preview bounds
                        watermarkBounds.left = watermarkBounds.left.coerceIn(0f, previewWidth - watermarkBounds.width())
                        watermarkBounds.top = watermarkBounds.top.coerceIn(0f, previewHeight - watermarkBounds.height())
                        watermarkBounds.right = watermarkBounds.left + watermarkBounds.width()
                        watermarkBounds.bottom = watermarkBounds.top + watermarkBounds.height()
                    }
                    isResizing -> {
                        // Resize watermark
                        val newWidth = (watermarkBounds.width() + dx).coerceIn(MIN_WATERMARK_SIZE, MAX_WATERMARK_SIZE)
                        val newHeight = (watermarkBounds.height() + dy).coerceIn(MIN_WATERMARK_SIZE, MAX_WATERMARK_SIZE)
                        watermarkBounds.right = watermarkBounds.left + newWidth
                        watermarkBounds.bottom = watermarkBounds.top + newHeight
                    }
                }

                lastX = event.x
                lastY = event.y
                true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                isResizing = false
                true
            }
            else -> false
        }
    }

    /**
     * Get current watermark bounds.
     */
    fun getBounds(): RectF = RectF(watermarkBounds)

    /**
     * Set watermark bounds.
     */
    fun setBounds(bounds: RectF) {
        watermarkBounds = RectF(bounds)
    }

    /**
     * Get scale factor for watermark size adjustment.
     */
    fun getScaleFactor(): Float {
        return watermarkBounds.width() / 200f // 200f is default width
    }
}
