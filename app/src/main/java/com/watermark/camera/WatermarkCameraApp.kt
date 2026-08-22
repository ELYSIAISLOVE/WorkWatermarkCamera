package com.watermark.camera

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for Watermark Camera.
 *
 * Initializes Hilt dependency injection and logging framework.
 * This is the entry point of the application.
 */
@HiltAndroidApp
class WatermarkCameraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Assign property directly (do not call setAppContext — clashes with var setter)
        com.watermark.camera.data.processing.BitmapDecoder.appContext = this.applicationContext
        initLogging()
    }

    /**
     * Initialize logging framework.
     * Uses Timber for structured logging.
     * Debug builds show verbose logs; release builds only show warnings and errors.
     */
    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("WatermarkCameraApp initialized. Version: ${BuildConfig.VERSION_NAME}")
    }
}
