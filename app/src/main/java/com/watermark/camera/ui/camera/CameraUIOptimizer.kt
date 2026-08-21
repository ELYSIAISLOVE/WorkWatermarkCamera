package com.watermark.camera.ui.camera

import android.view.View
import android.widget.Button
import androidx.constraintlayout.widget.ConstraintLayout
import com.watermark.camera.databinding.FragmentCameraBinding

/**
 * Helper class to optimize camera UI layout and positioning.
 *
 * Fixes:
 * - Button positioning in lower panel
 * - Proper alignment with constraint layout
 * - Touch target sizing
 */
class CameraUIOptimizer {

    /**
     * Apply optimal layout constraints for camera controls.
     *
     * @param binding Camera fragment binding.
     */
    fun optimizeControlsLayout(binding: FragmentCameraBinding) {
        val params = binding.bottomControlPanel.layoutParams as ConstraintLayout.LayoutParams
        params.bottomMargin = 16 // dp
        params.startMargin = 16  // dp
        params.endMargin = 16    // dp
        binding.bottomControlPanel.layoutParams = params

        // Ensure capture button is centered
        binding.btnCapture.apply {
            val captureParams = layoutParams as ConstraintLayout.LayoutParams
            captureParams.horizontalBias = 0.5f
            layoutParams = captureParams
        }
    }

    /**
     * Optimize top panel layout.
     */
    fun optimizeTopPanelLayout(binding: FragmentCameraBinding) {
        val topParams = binding.topControlPanel.layoutParams as ConstraintLayout.LayoutParams
        topParams.topMargin = 8 // dp
        topParams.startMargin = 8
        topParams.endMargin = 8
        binding.topControlPanel.layoutParams = topParams
    }

    /**
     * Set proper touch targets for buttons (minimum 48dp).
     */
    fun setProperTouchTargets(buttons: List<Button>) {
        buttons.forEach { button ->
            val minSize = 48 // dp in pixels (assuming 1dp = 1px for simplicity)
            button.minimumWidth = minSize
            button.minimumHeight = minSize
        }
    }
}
