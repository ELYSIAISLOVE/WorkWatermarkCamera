package com.watermark.camera.domain.model

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.watermark.camera.domain.repository.PhotoMetadata

/**
 * Result data class for a photo capture operation.
 *
 * Holds the captured image data and metadata before processing.
 *
 * @param imageProxy The raw ImageProxy from CameraX (must be closed after use).
 * @param timestamp Capture timestamp in milliseconds (System.currentTimeMillis).
 * @param rotationDegrees Image rotation in degrees (0, 90, 180, 270).
 * @param width Image width in pixels.
 * @param height Image height in pixels.
 * @param zoomRatio Zoom ratio at capture time.
 * @param evValue Exposure compensation value at capture time.
 * @param isFlashOn Whether flash was enabled.
 */
data class CaptureResult(
    val imageProxy: ImageProxy,
    val timestamp: Long,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
    val zoomRatio: Float = 1.0f,
    val evValue: Float = 0.0f,
    val isFlashOn: Boolean = false
) {
    /**
     * Check if the image needs rotation correction.
     */
    fun needsRotation(): Boolean = rotationDegrees != 0

    /**
     * Get the corrected dimensions after rotation.
     */
    fun getCorrectedDimensions(): Pair<Int, Int> {
        return if (rotationDegrees == 90 || rotationDegrees == 270) {
            height to width
        } else {
            width to height
        }
    }

    /**
     * Safely close the ImageProxy to release native memory.
     */
    fun close() {
        imageProxy.close()
    }
}

/**
 * Processed photo result after watermark and EXIF application.
 *
 * @param bitmap The final processed bitmap.
 * @param fileName Generated file name (WM_yyyyMMdd_HHmmss.jpg).
 * @param metadata Complete EXIF metadata.
 */
data class ProcessedPhoto(
    val bitmap: Bitmap,
    val fileName: String,
    val metadata: PhotoMetadata
)
