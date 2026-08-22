package com.watermark.camera

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class WatermarkCameraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.watermark.camera.data.processing.BitmapDecoder.appContext = applicationContext
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Work Camera initialized ${BuildConfig.VERSION_NAME}")
    }
}
