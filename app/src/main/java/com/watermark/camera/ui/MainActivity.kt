package com.watermark.camera.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.watermark.camera.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main entry activity of the application.
 *
 * Hosts the camera fragment and serves as the navigation container.
 * Uses a single-activity architecture with fragment-based navigation.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
