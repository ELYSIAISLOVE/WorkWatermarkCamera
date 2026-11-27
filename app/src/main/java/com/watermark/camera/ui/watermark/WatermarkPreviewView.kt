package com.watermark.camera.ui.watermark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.watermark.WatermarkRenderer
import com.watermark.camera.util.OrientationHelper

/**
 * 水印真实预览。
 *
 * 关键设计：**直接复用成片用的 [WatermarkRenderer]**，而不是像过去那样用 TextView
 * 拼一段文字近似。用户在编辑面板里改的任何参数，这里显示的与最终烧进照片的完全一致。
 *
 * @param config 当前水印配置（任何属性变化都会触发重绘）。
 */
class WatermarkPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前预览用的水印配置。 */
    var config: WatermarkConfig = WatermarkConfig()
        set(value) {
            field = value
            invalidate()
        }

    /** 预览用地点文本（真实拍摄时用实际地址）。 */
    var locationText: String = "上海市浦东新区世纪大道 100 号"
        set(value) {
            field = value
            invalidate()
        }

    /** 时间来源徽标，与成片页脚保持一致。 */
    var timeBadge: String = "可信时间"
        set(value) {
            field = value
            invalidate()
        }

    /** 预览用时间戳。 */
    var timeMs: Long = System.currentTimeMillis()
        set(value) {
            field = value
            invalidate()
        }

    private val renderer = WatermarkRenderer()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // 预览底色：浅灰渐变，模拟"照片"，保证白色卡片与深色卡片都能看清
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, 1000f,
            intArrayOf(Color.parseColor("#9EABB8"), Color.parseColor("#5B6570")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // 预览固定在左下角，位置由用户在相机页拖拽决定，这里只反映"长相"
        val previewConfig = config.copy(
            showLocation = true,
            position = WatermarkPosition.BOTTOM_LEFT,
            customX = null,
            customY = null
        )
        renderer.draw(
            canvas = canvas,
            areaWidth = width.toFloat(),
            areaHeight = height.toFloat(),
            config = previewConfig,
            locationText = locationText,
            deviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT,
            timeMs = timeMs,
            timeBadge = timeBadge
        )
    }
}