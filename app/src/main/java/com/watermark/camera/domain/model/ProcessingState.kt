package com.watermark.camera.domain.model

import android.net.Uri

/**
 * Represents the current state of the image processing pipeline.
 */
sealed class ProcessingState {

    /**
     * Idle state -- no processing active.
     */
    data object Idle : ProcessingState()

    /**
     * Decoding raw camera image.
     */
    data object Decoding : ProcessingState()

    /**
     * Applying watermark overlay.
     */
    data object ApplyingWatermark : ProcessingState()

    /**
     * Encoding to JPEG.
     */
    data object Encoding : ProcessingState()

    /**
     * Writing EXIF metadata.
     */
    data object WritingMetadata : ProcessingState()

    /**
     * Saving to MediaStore.
     */
    data object Saving : ProcessingState()

    /**
     * Indexing in local database.
     */
    data object Indexing : ProcessingState()

    /**
     * Processing completed successfully.
     */
    data class Success(
        val uri: Uri,
        val fileName: String,
        val width: Int,
        val height: Int,
        val processingTimeMs: Long
    ) : ProcessingState()

    /**
     * Processing failed.
     */
    data class Error(
        val stage: String,
        val exception: Throwable
    ) : ProcessingState()
}
