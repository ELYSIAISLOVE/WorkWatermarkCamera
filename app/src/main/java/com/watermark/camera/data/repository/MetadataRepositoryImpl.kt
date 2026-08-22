package com.watermark.camera.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.watermark.camera.domain.repository.MetadataRepository
import com.watermark.camera.domain.repository.PhotoMetadata
import com.watermark.camera.domain.repository.VerificationData
import com.watermark.camera.domain.repository.VerificationResult
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of MetadataRepository for EXIF operations.
 *
 * Provides:
 * - Complete EXIF metadata read/write
 * - Triple-time verification system
 * - UserComment hash generation and validation
 */
@Singleton
class MetadataRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MetadataRepository {

    companion object {
        private const val TAG = "MetadataRepo"
        private const val HASH_ALGORITHM = "SHA-256"
        private const val APP_NAME = "WatermarkCamera"
        private const val APP_VERSION = "1.0"
    }

    // region Write EXIF

    override suspend fun writeExif(
        uri: Uri,
        metadata: PhotoMetadata
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor).apply {
                    // Basic info
                    setAttribute(ExifInterface.TAG_DATETIME, metadata.dateTimeString)
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, metadata.dateTimeString)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, metadata.dateTimeString)
                    setAttribute(ExifInterface.TAG_MAKE, metadata.make)
                    setAttribute(ExifInterface.TAG_MODEL, metadata.model)
                    setAttribute(ExifInterface.TAG_SOFTWARE, "${metadata.software} v${APP_VERSION}")
                    setAttribute(ExifInterface.TAG_IMAGE_WIDTH, metadata.width.toString())
                    setAttribute(ExifInterface.TAG_IMAGE_LENGTH, metadata.height.toString())

                    // Image description (watermark info)
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, metadata.imageDescription)

                    // Digital zoom ratio
                    setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, metadata.digitalZoomRatio.toString())

                    // Camera parameters (if available)
                    metadata.iso?.let {
                        setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
                    }
                    metadata.exposureTime?.let {
                        setAttribute(ExifInterface.TAG_EXPOSURE_TIME, it)
                    }
                    metadata.aperture?.let {
                        setAttribute(ExifInterface.TAG_F_NUMBER, it)
                    }

                    // GPS (if available)
                    metadata.latitude?.let { lat ->
                        metadata.longitude?.let { lon ->
                            setLatLong(lat, lon)
                        }
                    }
                    metadata.altitude?.let {
                        setAltitude(it)
                    }

                    // UserComment with verification hash
                    setAttribute(ExifInterface.TAG_USER_COMMENT, buildUserComment(metadata))

                    saveAttributes()
                }
            }
            Logger.i(TAG, "EXIF written to $uri")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to write EXIF", e)
            Result.failure(e)
        }
    }

    // endregion

    // region Read EXIF

    override suspend fun readExif(uri: Uri): Result<PhotoMetadata> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                val metadata = PhotoMetadata(
                    timestamp = 0L, // Will be parsed from UserComment or DateTime
                    dateTimeString = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "",
                    make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "",
                    model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "",
                    software = exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: "",
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                    latitude = exif.latLong?.get(0)?.toDouble(),
                    longitude = exif.latLong?.get(1)?.toDouble(),
                    altitude = exif.getAltitude(0.0).takeIf { it != 0.0 },
                    digitalZoomRatio = exif.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, 1.0).toFloat(),
                    iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                    exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                    aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                    verificationHash = "",
                    imageDescription = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: "",
                    watermarkConfigJson = null
                )

                Logger.i(TAG, "EXIF read from $uri")
                Result.success(metadata)
            } ?: Result.failure(IllegalStateException("Cannot open file descriptor for $uri"))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read EXIF", e)
            Result.failure(e)
        }
    }

    // endregion

    // region Verify Integrity

    override suspend fun verifyIntegrity(uri: Uri): Result<VerificationResult> = withContext(Dispatchers.IO) {
        try {
            // Get file path from URI
            val filePath = getFilePathFromUri(uri)
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext Result.success(
                    VerificationResult.Failed("文件不存在: $uri")
                )
            }

            // Read EXIF
            val exifResult = readExif(uri)
            val metadata = exifResult.getOrElse { error ->
                return@withContext Result.success(
                    VerificationResult.Failed("读取EXIF失败: ${error.message}")
                )
            }

            // Parse UserComment
            val userComment = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor).getAttribute(ExifInterface.TAG_USER_COMMENT)
            }

            val parsed = TimeVerification.parseUserComment(userComment)
            if (parsed == null) {
                return@withContext Result.success(
                    VerificationResult.Failed("无法解析UserComment验真数据")
                )
            }

            val (appName, userCommentTime, actualHash) = parsed

            // Verify app name
            if (appName != APP_NAME) {
                return@withContext Result.success(
                    VerificationResult.Tampered(
                        reason = "应用标识不匹配",
                        details = "Expected $APP_NAME, got $appName"
                    )
                )
            }

            // Re-generate expected hash
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val expectedHash = generateVerificationHash(userCommentTime, deviceId)

            // Parse EXIF time
            val exifTime = parseExifDateTime(metadata.dateTimeString)

            // Triple-time + hash verification
            val result = TimeVerification.verify(
                fileTime = file.lastModified(),
                exifTime = exifTime,
                userCommentTime = userCommentTime,
                expectedHash = expectedHash,
                actualHash = actualHash
            )

            Result.success(result)
        } catch (e: Exception) {
            Logger.e(TAG, "Verification failed", e)
            Result.success(VerificationResult.Failed("验真过程异常: ${e.message}"))
        }
    }

    // endregion

    // region Hash Generation

    override fun generateVerificationHash(timestamp: Long, deviceId: String): String {
        val input = "$APP_NAME|$timestamp|$deviceId"
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    // endregion

    // region Parse Verification Data

    override fun parseVerificationData(userComment: String?): VerificationData? {
        val parsed = TimeVerification.parseUserComment(userComment) ?: return null
        val (appName, timestamp, hash) = parsed
        return VerificationData(
            appName = appName,
            timestamp = timestamp,
            hash = hash,
            rawString = userComment ?: ""
        )
    }

    // endregion

    // region Private Helpers

    /**
     * Build UserComment string for EXIF.
     * Format: "WatermarkCamera|timestamp|hash"
     */
    private fun buildUserComment(metadata: PhotoMetadata): String {
        return "$APP_NAME|${metadata.timestamp}|${metadata.verificationHash}"
    }

    /**
     * Parse EXIF DateTime string to milliseconds.
     * Format: "yyyy:MM:dd HH:mm:ss"
     */
    private fun parseExifDateTime(dateTimeStr: String): Long {
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                .parse(dateTimeStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get real file path from content URI.
     */
    private fun getFilePathFromUri(uri: Uri): String {
        return when (uri.scheme) {
            "file" -> uri.path ?: ""
            "content" -> {
                // Try to query MediaStore for the file path
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.DATA),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                        cursor.getString(idx)
                    } else null
                } ?: uri.toString()
            }
            else -> uri.toString()
        }
    }

    // endregion
}
