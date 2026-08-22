package com.watermark.camera.domain.repository

import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * Repository interface for EXIF metadata operations.
 *
 * Handles reading and writing EXIF data to JPEG files,
 * including the triple-time verification system.
 */
interface MetadataRepository {

    /**
     * Write complete EXIF metadata to a photo.
     *
     * @param uri The photo URI.
     * @param metadata The metadata to write.
     * @return Result indicating success or failure.
     */
    suspend fun writeExif(
        uri: android.net.Uri,
        metadata: PhotoMetadata
    ): Result<Unit>

    /**
     * Read EXIF metadata from a photo.
     *
     * @param uri The photo URI.
     * @return Result containing the metadata, or an error.
     */
    suspend fun readExif(uri: android.net.Uri): Result<PhotoMetadata>

    /**
     * Verify photo integrity using the triple-time check.
     *
     * @param uri The photo URI.
     * @return Result containing verification result.
     */
    suspend fun verifyIntegrity(uri: android.net.Uri): Result<VerificationResult>

    /**
     * Generate the hidden verification hash for UserComment.
     *
     * @param timestamp The capture timestamp.
     * @param deviceId A unique device identifier.
     * @return The verification hash string.
     */
    fun generateVerificationHash(timestamp: Long, deviceId: String): String

    /**
     * Parse verification data from UserComment.
     *
     * @param userComment The UserComment EXIF string.
     * @return Parsed verification data, or null if invalid.
     */
    fun parseVerificationData(userComment: String?): VerificationData?
}

/**
 * Complete photo metadata for EXIF writing.
 */
data class PhotoMetadata(
    val timestamp: Long,
    val dateTimeString: String,           // Format: "2026:08:21 07:50:30"
    val make: String,                     // Device manufacturer
    val model: String,                    // Device model
    val software: String = "WatermarkCamera",
    val width: Int,
    val height: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val digitalZoomRatio: Float = 1.0f,
    val iso: Int? = null,
    val exposureTime: String? = null,
    val aperture: String? = null,
    val verificationHash: String,
    val imageDescription: String,
    val watermarkConfigJson: String? = null
)

/**
 * Verification result.
 */
sealed class VerificationResult {
    /**
     * Photo is authentic — all three time sources match.
     */
    data object Authentic : VerificationResult()

    /**
     * Photo may have been tampered with.
     *
     * @param reason Human-readable reason.
     * @param details Technical details for debugging.
     */
    data class Tampered(
        val reason: String,
        val details: String
    ) : VerificationResult()

    /**
     * Verification could not be performed.
     *
     * @param reason Reason for failure.
     */
    data class Failed(val reason: String) : VerificationResult()
}

/**
 * Parsed verification data from UserComment.
 */
data class VerificationData(
    val appName: String,
    val timestamp: Long,
    val hash: String,
    val rawString: String
)
