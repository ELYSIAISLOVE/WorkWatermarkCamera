package com.watermark.camera.data.watermark

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.util.OrientationHelper

/**
 * Live preview watermark. Drawing is delegated entirely to [WatermarkRenderer]
 * so burn-in matches what the user sees. Drag is clamped to this view bounds
 * (same as the rounded PreviewView frame).
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

    var onDragPosition: ((Float, Float, WatermarkPosition) -> Unit)? = null
    var onPositionChanged: ((WatermarkPosition) -> Unit)? = null

    private val renderer = WatermarkRenderer()
    private var lastMetrics: WatermarkRenderer.CardMetrics? = null

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
        lastMetrics = renderer.draw(
            canvas = canvas,
            areaWidth = width.toFloat(),
            areaHeight = height.toFloat(),
            config = watermarkConfig,
            locationText = locationText,
            deviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT,
            timeMs = System.currentTimeMillis()
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val m = lastMetrics
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (m == null) return false
                val hit = event.x >= m.left - 24 &&
                    event.x <= m.left + m.width + 24 &&
                    event.y >= m.top - 24 &&
                    event.y <= m.top + m.height + 24
                if (!hit) return false
                dragging = true
                grabDx = event.x - m.left
                grabDy = event.y - m.top
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging || m == null) return false
                val rawL = event.x - grabDx
                val rawT = event.y - grabDy
                val (clampedL, clampedT) = WatermarkLayout.clampOrigin(
                    rawL, rawT,
                    width.toFloat(), height.toFloat(),
                    m.width, m.height, 0f
                )
                val (nx, ny) = WatermarkLayout.toNormalized(
                    clampedL, clampedT,
                    width.toFloat(), height.toFloat(),
                    m.width, m.height, 0f
                )
                val slot = WatermarkLayout.nearestSlot(
                    clampedL + m.width / 2f,
                    clampedT + m.height / 2f,
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

    fun captureSnapshot(): WatermarkSnapshot {
        return WatermarkSnapshot(
            config = watermarkConfig.copy(showLocation = true, fontScale = 2.5f),
            locationText = locationText,
            deviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    fun updateLocation(location: String?) {
        locationText = location ?: "位置获取中…"
    }
}
