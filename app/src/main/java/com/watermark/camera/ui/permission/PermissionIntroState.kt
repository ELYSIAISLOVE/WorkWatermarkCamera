package com.watermark.camera.ui.permission

import android.content.Context

/**
 * 首启授权说明页的状态（对应规范 §3「权限情境化」）。
 *
 * 首次进入先用一屏说明用途，再依次请求相机与定位；看过一次后不再重复拦截，
 * 缺权限改为在界面上常驻提示条 + 「去设置」。
 */
object PermissionIntroState {

    private const val PREFS = "app_permission_prefs"
    private const val KEY_DONE = "permission_intro_done"

    fun isDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}