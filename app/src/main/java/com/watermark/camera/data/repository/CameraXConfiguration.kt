package com.watermark.camera.data.repository

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import com.watermark.camera.domain.repository.FlashMode

/**
 * CameraX configuration factory.
 * Provides builders for camera components with optimized settings.
 */
object CameraXConfiguration {

    /**
     * Build a camera selector for the specified lens facing direction.
     */
    fun buildCameraSelector(lensFacing: Int = CameraSelector.LENS_FACING_BACK): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    /**
     * Build preview use case.
     */
    fun buildPreview(): Preview {
        return Preview.Builder()
            .build()
    }

    /**
     * Build image capture use case with optimized settings.
     */
    fun buildImageCapture(): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .setTargetRotation(android.view.Surface.ROTATION_0)
            .build()
    }

    /**
     * Convert domain FlashMode to CameraX ImageCapture.FlashMode.
     */
    fun toImageCaptureFlashMode(mode: FlashMode): Int {
        return when (mode) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF // Torch is handled separately
        }
    }
}
