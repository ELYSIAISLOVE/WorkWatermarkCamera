package com.watermark.camera.util

import android.content.Context
import android.provider.Settings

/**
 * 动效开关（规范 §7：动效 150–250ms，且尊重系统的「移除动画」设置）。
 *
 * 系统设置里把动画缩放设为 0 时（无障碍 / 开发者选项），应用不再播放位移与缩放动效，
 * 但仍保留瞬时状态变化，保证功能可完成。
 */
object Motion {

    fun enabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }.getOrDefault(true)
}