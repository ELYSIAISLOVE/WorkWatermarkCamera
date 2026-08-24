package com.watermark.camera.data.watermark

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.util.OrientationHelper

/**
 * Live preview watermark. Drawing delegated to [WatermarkRenderer].
 * Drag clamped so all four edges of the card stay inside the view.
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

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

    var deviceOrientation: OrientationHelper.DeviceOrientation =
        OrientationHelper.DeviceOrientation.PORTRAIT
        set(value) {
            field = value
            invalidate()
        }

    var onDragPosition: ((nx: Float, ny: Float, slot: WatermarkPosition) -> Unit)? = null
    var onPositionChanged: ((WatermarkPosition) -> Unit)? = null

    private val renderer = WatermarkRenderer()
    private var lastMetrics: WatermarkRenderer.CardMetrics? = null
    private var dragging = false
    private var grabDx = 0f
    private var grabDy = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        lastMetrics = renderer.draw(
            canvas = canvas,
            areaWidth = width.toFloat(),
            areaHeight = height.toFloat(),
            config = watermarkConfig.copy(showLocation = true),
            locationText = locationText,
            deviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT,
            timeMs = System.currentTimeMillis()
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val m = lastMetrics ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inside = event.x >= m.left && event.x <= m.left + m.width &&
                    event.y >= m.top && event.y <= m.top + m.height
                if (!inside) return false
                dragging = true
                grabDx = event.x - m.left
                grabDy = event.y - m.top
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val cardW = m.width
                val cardH = m.height
                val maxL = (width - cardW).coerceAtLeast(0f)
                val maxT = (height - cardH).coerceAtLeast(0f)
                // Four-edge clamp
                val left = (event.x - grabDx).coerceIn(0f, maxL)
                val top = (event.y - grabDy).coerceIn(0f, maxT)
                val nx = if (maxL > 0f) left / maxL else 0f
                val ny = if (maxT > 0f) top / maxT else 0f
                val slot = when {
                    ny < 0.33f && nx < 0.33f -> WatermarkPosition.TOP_LEFT
                    ny < 0.33f && nx > 0.66f -> WatermarkPosition.TOP_RIGHT
                    ny > 0.66f && nx < 0.33f -> WatermarkPosition.BOTTOM_LEFT
                    ny > 0.66f && nx > 0.66f -> WatermarkPosition.BOTTOM_RIGHT
                    else -> WatermarkPosition.CENTER
                }
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

    fun captureSnapshot(): WatermarkSnapshot {
        return WatermarkSnapshot(
            config = watermarkConfig.copy(showLocation = true),
            locationText = locationText,
            deviceOrientation = deviceOrientation,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    fun updateLocation(location: String?) {
        locationText = location ?: "位置获取中…"
    }
}
