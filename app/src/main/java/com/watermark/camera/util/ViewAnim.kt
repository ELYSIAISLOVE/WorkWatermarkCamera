package com.watermark.camera.util

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Simple iOS-like press scale animation for buttons.
 */
object ViewAnim {
    private const val PRESS_SCALE = 0.88f
    private const val DURATION = 120L

    fun attachPressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(PRESS_SCALE)
                        .scaleY(PRESS_SCALE)
                        .setDuration(DURATION)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(DURATION + 40)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }
            }
            false // allow click to still fire
        }
    }

    fun attachPressScale(vararg views: View) {
        views.forEach { attachPressScale(it) }
    }
}
