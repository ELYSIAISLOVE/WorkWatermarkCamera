package com.watermark.camera.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 设备能力与降级开关（规范 §8）。
 *
 * - [supportsBlur]：Android 12 以下不支持背景模糊，毛玻璃改用半透明纯色；
 * - [isLowRamDevice]：低内存机型降低处理分辨率、关闭重特效；
 * - [maxProcessingDimension]：成片处理长边上限，随内存等级变化。
 */
object UiCapabilities {

    private var lowRam = false

    fun init(context: Context) {
        lowRam = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice == true
        }.getOrDefault(false)
    }

    val isLowRamDevice: Boolean get() = lowRam

    /** Android 12（API 31）起支持 blur / RenderEffect 类毛玻璃。 */
    val supportsBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 低内存机型把长边降到 1920，正常机型 2560（规范 §8）。 */
    val maxProcessingDimension: Int get() = if (lowRam) 1920 else 2560
}