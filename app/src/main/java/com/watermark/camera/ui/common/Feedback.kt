package com.watermark.camera.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import com.watermark.camera.R

/**
 * 统一提示出口（依据 `docs/08-交互与界面设计规范.md` §7「提示」）：
 *
 * - [light] 成功/纯信息：短 Toast，不打断操作；
 * - [actionable] 需要用户处理（失败、可重试、需授权）：Snackbar，可带动作按钮，
 *   默认常驻到用户处理或 10 秒后自动消失；
 * - 破坏性操作（删除整批、丢弃编辑）仍用 AlertDialog，见各页面。
 *
 * 约定：**错误一律不用 Toast**。
 */
object Feedback {

    /** 轻量提示：仅用于成功与纯信息。 */
    fun light(view: View, message: CharSequence) {
        Toast.makeText(view.context, message, Toast.LENGTH_SHORT).show()
    }

    /** 需要用户处理的提示，常驻直到用户操作或超时。 */
    fun actionable(
        view: View,
        message: CharSequence,
        @StringRes actionLabel: Int? = null,
        action: (() -> Unit)? = null
    ) {
        val bar = Snackbar.make(view, message, Snackbar.LENGTH_INDEFINITE)
        if (actionLabel != null && action != null) {
            bar.setAction(actionLabel) { action() }
        } else {
            // 没有动作按钮时不要一直占屏，10 秒后自动收起
            bar.duration = 10_000
        }
        bar.show()
    }

    /** 需要用户处理且应引导到系统设置的提示。 */
    fun needsPermission(view: View, message: CharSequence) {
        actionable(view, message, R.string.permission_go_settings) {
            openAppSettings(view.context)
        }
    }

    /** 直接打开本应用的系统设置页。 */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}