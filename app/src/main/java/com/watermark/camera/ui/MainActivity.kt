package com.watermark.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.watermark.camera.R
import com.watermark.camera.util.Logger
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main activity. Requests required permissions on every resume if missing.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Logger.w(TAG, "Permissions denied: $denied")
        } else {
            Logger.i(TAG, "All requested permissions granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        ensurePermissions()
    }

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        fun check(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p)
            }
        }
        check(Manifest.permission.CAMERA)
        check(Manifest.permission.ACCESS_FINE_LOCATION)
        check(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            check(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            check(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                check(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (need.isNotEmpty()) {
            Logger.i(TAG, "Requesting permissions: $need")
            permissionLauncher.launch(need.toTypedArray())
        }
    }
}
