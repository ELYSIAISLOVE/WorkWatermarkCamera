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
     * The hash binds the timestamp to the app instance and to the time provenance, so editing
     * the burned-in/EXIF timestamp (or its source label) invalidates the hash.
     *
     * @param timestamp The capture timestamp (trusted time when available).
     * @param timeSource Provenance label, e.g. NETWORK / OFFLINE_ANCHORED / DEVICE.
     * @param clockOffsetMs serverClock - deviceClock at capture.
     * @return The verification hash string.
     */
    fun generateVerificationHash(
        timestamp: Long,
        timeSource: String = "DEVICE",
        clockOffsetMs: Long = 0L
    ): String

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
    val watermarkConfigJson: String? = null,
    // ---- Evidence fields (requirement R2 / decision D2) ----
    /** Provenance name of [timestamp]: NETWORK / OFFLINE_ANCHORED / DEVICE. */
    val timeSource: String = "DEVICE",
    /** serverClock - deviceClock measured at the last successful time sync. */
    val clockOffsetMs: Long = 0L,
    /** Half RTT of the last sync; the timestamp is only this precise. */
    val timeUncertaintyMs: Long = 0L,
    /** Wall clock of the last successful sync (0 when never synced). */
    val lastSyncAtMs: Long = 0L,
    /** True when the device clock disagreed with the anchored projection at capture time. */
    val deviceClockTampered: Boolean = false
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
    val rawString: String,
    /** NETWORK / OFFLINE_ANCHORED / DEVICE (empty for legacy v0 comments). */
    val timeSource: String = "",
    /** serverClock - deviceClock recorded at capture (0 for legacy v0 comments). */
    val clockOffsetMs: Long = 0L,
    /** True when the comment used the v1 format ("WC1|..."). */
    val isV1: Boolean = false
)
