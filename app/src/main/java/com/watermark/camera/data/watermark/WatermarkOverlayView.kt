package com.watermark.camera.data.watermark

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
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

    /** Provenance label shown in the card footer, e.g. "可信时间" / "离线推算 09:14". */
    var timeBadge: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var onDragPosition: ((nx: Float, ny: Float, slot: WatermarkPosition) -> Unit)? = null
    var onPositionChanged: ((WatermarkPosition) -> Unit)? = null

    private val renderer = WatermarkRenderer()
    private var lastMetrics: WatermarkRenderer.CardMetrics? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeTicker = object : Runnable {
        override fun run() {
            if (isAttachedToWindow && visibility == View.VISIBLE) {
                invalidate()
            }
            timeHandler.postDelayed(this, 1000L)
        }
    }

    private var dragging = false
    private var grabDx = 0f
    private var grabDy = 0f

    /** 拖动时的吸附状态，用于只在吸附瞬间触发一次触感（规范 §4）。 */
    private var snappedX = false
    private var snappedY = false

    /** 九宫格吸附线与卡片安全边距（规范 §4：卡片距边缘保持 12dp）。 */
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val safetyMarginPx = dp(12f)
    private val snapThresholdPx = dp(8f)

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (dragging) {
            // 拖动时显示九宫格吸附线
            for (i in 1..2) {
                val x = width * i / 3f
                val y = height * i / 3f
                canvas.drawLine(x, 0f, x, height.toFloat(), guidePaint)
                canvas.drawLine(0f, y, width.toFloat(), y, guidePaint)
            }
        }
        val orient = if (watermarkConfig.useGyroscope) deviceOrientation
            else OrientationHelper.DeviceOrientation.PORTRAIT
        lastMetrics = renderer.draw(
            canvas = canvas,
            areaWidth = width.toFloat(),
            areaHeight = height.toFloat(),
            config = watermarkConfig,
            locationText = locationText,
            deviceOrientation = orient,
            timeMs = System.currentTimeMillis(),
            timeBadge = timeBadge
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val m = lastMetrics ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 横竖屏均可拖拽；位置以视图像素为准，与成片 resolveOrigin 一致
                val inside = event.x >= m.left && event.x <= m.left + m.width &&
                    event.y >= m.top && event.y <= m.top + m.height
                if (!inside) return false
                dragging = true
                snappedX = false
                snappedY = false
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
                // 四边夹紧，并保留 12dp 安全边距（边距过大时退化为贴边）
                val marginX = safetyMarginPx.coerceAtMost(maxL / 2f)
                val marginY = safetyMarginPx.coerceAtMost(maxT / 2f)
                var left = (event.x - grabDx).coerceIn(marginX, maxL - marginX)
                var top = (event.y - grabDy).coerceIn(marginY, maxT - marginY)

                // 九宫格吸附：靠近水平/垂直中线时吸附，并在吸附瞬间给触感
                val centerX = maxL / 2f
                val centerY = maxT / 2f
                val snapX = kotlin.math.abs(left - centerX) <= snapThresholdPx
                val snapY = kotlin.math.abs(top - centerY) <= snapThresholdPx
                if (snapX) left = centerX
                if (snapY) top = centerY
                if (snapX != snappedX || snapY != snappedY) {
                    if (snapX || snapY) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    snappedX = snapX
                    snappedY = snapY
                }
                val nx = if (maxL > 0f) left / maxL else 0f
                val ny = if (maxT > 0f) top / maxT else 0f
                val slot = when {
                    snapX && snapY -> WatermarkPosition.CENTER
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


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        timeHandler.removeCallbacks(timeTicker)
        timeHandler.post(timeTicker)
    }

    override fun onDetachedFromWindow() {
        timeHandler.removeCallbacks(timeTicker)
        super.onDetachedFromWindow()
    }

    fun captureSnapshot(): WatermarkSnapshot {
        return WatermarkSnapshot(
            config = watermarkConfig,
            locationText = locationText,
            deviceOrientation = deviceOrientation,
            capturedAtMs = System.currentTimeMillis(),
            timeBadge = timeBadge
        )
    }

    fun updateLocation(location: String?) {
        locationText = location ?: "位置获取中…"
    }
}
