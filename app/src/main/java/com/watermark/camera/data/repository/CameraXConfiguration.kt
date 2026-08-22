package com.watermark.camera.data.repository

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * CameraX configuration settings.
 *
 * Centralizes all camera parameter configurations to ensure consistency
 * between preview and capture.
 */
object CameraXConfiguration {

    /**
     * JPEG capture quality (0-100).
     * PRD requires 95% for highest quality.
     */
    const val JPEG_QUALITY = 95

    /**
     * Preview target resolution for smooth 30fps.
     */
    val PREVIEW_TARGET_RESOLUTION = Size(1280, 720)

    /**
     * Supported aspect ratios.
     */
    object AspectRatios {
        const val RATIO_4_3 = "4:3"
        const val RATIO_16_9 = "16:9"
        const val RATIO_1_1 = "1:1"

        val ALL = listOf(RATIO_4_3, RATIO_16_9, RATIO_1_1)
    }

    /**
     * Build Preview use case.
     *
     * @param aspectRatio Current aspect ratio string.
     * @return Configured Preview instance.
     */
    fun buildPreview(aspectRatio: String = AspectRatios.RATIO_4_3): Preview {
        val aspectStrategy = when (aspectRatio) {
            AspectRatios.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }
        return Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectStrategy)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            PREVIEW_TARGET_RESOLUTION,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
    }

    /**
     * Build ImageCapture use case.
     *
     * @param aspectRatio Current aspect ratio string.
     * @param flashMode Current flash mode.
     * @return Configured ImageCapture instance.
     */
    fun buildImageCapture(
        aspectRatio: String = AspectRatios.RATIO_4_3,
        flashMode: Int = ImageCapture.FLASH_MODE_AUTO
    ): ImageCapture {
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setJpegQuality(JPEG_QUALITY)

        // Set resolution selector based on aspect ratio
        val aspectStrategy = when (aspectRatio) {
            AspectRatios.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategy)
            .build()
        captureBuilder.setResolutionSelector(resolutionSelector)

        return captureBuilder.build()
    }

    /**
     * Build CameraSelector for the specified lens facing.
     *
     * @param lensFacing CameraSelector.LENS_FACING_BACK or LENS_FACING_FRONT.
     * @return Configured CameraSelector.
     */
    fun buildCameraSelector(lensFacing: Int): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    /**
     * Convert flash mode enum to ImageCapture flash mode constant.
     *
     * @param mode FlashMode enum value.
     * @return ImageCapture flash mode constant.
     */
    fun toImageCaptureFlashMode(mode: com.watermark.camera.domain.repository.FlashMode): Int {
        return when (mode) {
            com.watermark.camera.domain.repository.FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            com.watermark.camera.domain.repository.FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            com.watermark.camera.domain.repository.FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            com.watermark.camera.domain.repository.FlashMode.TORCH -> ImageCapture.FLASH_MODE_ON
        }
    }

    /**
     * Parse aspect ratio string to camera use case ratio.
     *
     * @param ratio String like "4:3", "16:9", "1:1".
     * @return Aspect ratio for CameraX use cases.
     */
    fun parseAspectRatio(ratio: String): Int {
        return when (ratio) {
            AspectRatios.RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
            AspectRatios.RATIO_16_9 -> androidx.camera.core.AspectRatio.RATIO_16_9
            AspectRatios.RATIO_1_1 -> androidx.camera.core.AspectRatio.RATIO_4_3 // 1:1 handled by crop
            else -> androidx.camera.core.AspectRatio.RATIO_4_3
        }
    }
}
