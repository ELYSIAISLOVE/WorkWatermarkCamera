package com.watermark.camera

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class WatermarkCameraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyStoredTheme()
        com.watermark.camera.data.processing.BitmapDecoder.appContext = applicationContext
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Work Camera initialized ${BuildConfig.VERSION_NAME}")
    }

    private fun applyStoredTheme() {
        val mode = getSharedPreferences("wm_prefs", MODE_PRIVATE)
            .getString("theme_mode", "auto") ?: "auto"
        when (mode) {
            "black" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "white" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
