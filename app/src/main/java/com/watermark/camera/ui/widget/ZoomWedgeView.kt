package com.watermark.camera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ln
import kotlin.math.exp

/**
 * 音量条式变焦楔形控件：竖直方向，底端窄（小倍）顶端宽（大倍）。
 * 按住拖动改变 zoom；对外回调线性 ratio。
 */
class ZoomWedgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前倍率（绝对） */
    var zoomRatio: Float = 1f
        set(value) {
            field = value.coerceIn(minZoom, maxZoom)
            invalidate()
        }

    var minZoom: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0.5f)
            invalidate()
        }

    var maxZoom: Float = 10f
        set(value) {
            field = value.coerceAtLeast(minZoom + 0.1f)
            invalidate()
        }

    /** 拖动/变化回调 */
    var onZoomChanged: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0x66FFFFFF
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val wedgePath = Path()
    private val dens = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return

        // 楔形：顶部宽、底部窄（像音量格子倒过来：上=放大）
        val topHalf = w * 0.48f
        val botHalf = w * 0.16f
        val pad = 4f * dens
        wedgePath.reset()
        wedgePath.moveTo(w / 2f - botHalf, h - pad)
        wedgePath.lineTo(w / 2f + botHalf, h - pad)
        wedgePath.lineTo(w / 2f + topHalf, pad)
        wedgePath.lineTo(w / 2f - topHalf, pad)
        wedgePath.close()

        trackPaint.color = 0x44000000
        canvas.drawPath(wedgePath, trackPaint)

        // 填充到当前 zoom 高度（从底向上）
        val t = zoomToT(zoomRatio)
        val yThumb = h - pad - t * (h - 2f * pad)
        canvas.save()
        canvas.clipPath(wedgePath)
        fillPaint.shader = LinearGradient(
            0f, h, 0f, 0f,
            intArrayOf(0xFF4CAF50.toInt(), 0xFF8BC34A.toInt(), 0xFFFFC107.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, yThumb, w, h, fillPaint)
        fillPaint.shader = null
        canvas.restore()

        canvas.drawPath(wedgePath, strokePaint)

        // 拇指横线
        thumbPaint.strokeWidth = 3f * dens
        val half = botHalf + t * (topHalf - botHalf)
        canvas.drawLine(w / 2f - half - 4f * dens, yThumb, w / 2f + half + 4f * dens, yThumb, thumbPaint)
        canvas.drawCircle(w / 2f, yThumb, 5f * dens, thumbPaint)

        // 倍率文字
        textPaint.textSize = 12f * dens
        canvas.drawText(String.format("%.1fx", zoomRatio), w / 2f, pad + 14f * dens, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val h = height.toFloat()
                val pad = 4f * dens
                val t = ((h - pad - event.y) / (h - 2f * pad)).coerceIn(0f, 1f)
                val z = tToZoom(t)
                zoomRatio = z
                onZoomChanged?.invoke(z)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 0..1 → zoom，对数映射更符合变焦手感 */
    private fun tToZoom(t: Float): Float {
        val a = ln(minZoom.toDouble())
        val b = ln(maxZoom.toDouble())
        return exp(a + t.coerceIn(0f, 1f) * (b - a)).toFloat()
    }

    private fun zoomToT(z: Float): Float {
        val a = ln(minZoom.toDouble())
        val b = ln(maxZoom.toDouble())
        if (b <= a) return 0f
        return ((ln(z.toDouble().coerceIn(minZoom.toDouble(), maxZoom.toDouble())) - a) / (b - a))
            .toFloat()
            .coerceIn(0f, 1f)
    }
}
