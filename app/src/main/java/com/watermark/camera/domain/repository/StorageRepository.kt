package com.watermark.camera.domain.repository

import android.graphics.Bitmap
import android.net.Uri

/**
 * Repository interface for photo storage operations.
 *
 * Handles saving to MediaStore (Android 10+) and direct file access (legacy).
 */
interface StorageRepository {

    /**
     * Save a JPEG image to public storage.
     *
     * @param bitmap The processed bitmap to save.
     * @param fileName File name (e.g., WM_20260821_075030.jpg).
     * @param quality JPEG quality (0-100, default: 95).
     * @return Result containing the saved URI, or an error.
     */
    suspend fun savePhoto(
        bitmap: Bitmap,
        fileName: String,
        quality: Int = 95
    ): Result<Uri>

    /**
     * Save a collage image.
     *
     * @param bitmap The collage bitmap.
     * @param fileName File name for the collage.
     * @param quality JPEG quality (default: 90).
     * @return Result containing the saved URI.
     */
    suspend fun saveCollage(
        bitmap: Bitmap,
        fileName: String,
        quality: Int = 90
    ): Result<Uri>

    /**
     * Delete a photo by URI.
     *
     * @param uri The photo URI.
     * @return Result indicating success or failure.
     */
    suspend fun deletePhoto(uri: Uri): Result<Unit>

    /**
     * Generate a file name based on current timestamp.
     *
     * Format: WM_yyyyMMdd_HHmmss.jpg
     *
     * @return Generated file name.
     */
    fun generateFileName(): String

    /**
     * Generate a collage file name.
     *
     * Format: COLLAGE_yyyyMMdd_HHmmss.jpg
     *
     * @return Generated file name.
     */
    fun generateCollageFileName(): String

    /**
     * Check if storage permission is granted (for Android 9 and below).
     */
    fun hasStoragePermission(): Boolean

    /**
     * Check if there is enough free storage space.
     *
     * @param requiredBytes Minimum required bytes.
     * @return True if enough space is available.
     */
    fun hasEnoughSpace(requiredBytes: Long = 50 * 1024 * 1024): Boolean

    /**
     * Clean up temporary files.
     */
    suspend fun cleanupTempFiles()
}
