package com.watermark.camera.data.repository

import android.content.ContentResolver
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
        private const val PREFS_NAME = "watermark_evidence"
        private const val KEY_INSTANCE_ID = "app_instance_id"
        /** Characters of the hex digest kept in the UserComment (64 bits). */
        private const val HASH_LENGTH = 16
    }

    /**
     * Random id created on first launch and kept for the install lifetime.
     *
     * ANDROID_ID was used before, but it is unstable (per-app-signing on Android 8+, reset by
     * factory reset / package reinstall by some OEMs), which made old photos fail verification
     * for reasons unrelated to tampering.
     */
    private val instanceId: String by lazy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTANCE_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTANCE_ID, it).apply()
        }
    }

    /** Legacy device binding; only used to accept photos taken by older builds. */
    private val legacyAndroidId: String
        get() = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

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
                val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                val parsed = TimeVerification.parseUserComment(userComment)
                val dateTimeString = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: ""

                val metadata = PhotoMetadata(
                    // The UserComment carries the burned-in (trusted) timestamp; fall back to
                    // the EXIF DateTime for photos written by other tools.
                    timestamp = parsed?.timestamp ?: parseExifDateTime(dateTimeString),
                    dateTimeString = dateTimeString,
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
                    verificationHash = parsed?.hash ?: "",
                    imageDescription = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: "",
                    watermarkConfigJson = null,
                    timeSource = parsed?.timeSource ?: "",
                    clockOffsetMs = parsed?.clockOffsetMs ?: 0L
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
            // Read EXIF straight from the content stream. MediaStore.DATA used to be resolved
            // here and is empty under scoped storage, which produced bogus "文件不存在" results.
            val metadata = readExif(uri).getOrElse { error ->
                return@withContext Result.success(
                    VerificationResult.Failed("读取EXIF失败: ${error.message}")
                )
            }

            // Parse UserComment
            val userComment = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor).getAttribute(ExifInterface.TAG_USER_COMMENT)
            } ?: return@withContext Result.success(
                VerificationResult.Failed("无法打开照片: $uri")
            )

            val parsed = TimeVerification.parseUserComment(userComment)
                ?: return@withContext Result.success(
                    VerificationResult.Failed("无法解析UserComment验真数据")
                )

            // Verify app name
            if (parsed.appName != APP_NAME) {
                return@withContext Result.success(
                    VerificationResult.Tampered(
                        reason = "应用标识不匹配",
                        details = "Expected $APP_NAME, got ${parsed.appName}"
                    )
                )
            }

            // Re-generate the expected hash with the same binding used at capture time.
            val candidates = candidateHashes(parsed.timestamp, parsed.timeSource, parsed.clockOffsetMs)

            // Parse EXIF time
            val exifTime = parseExifDateTime(metadata.dateTimeString)
            val fileTime = queryMediaStoreTime(uri)

            // Triple-time + hash verification
            val result = TimeVerification.verify(
                fileTime = fileTime,
                exifTime = exifTime,
                userCommentTime = parsed.timestamp,
                expectedHash = candidates.first(),
                actualHash = parsed.hash,
                alternatives = candidates.drop(1)
            )

            Result.success(result)
        } catch (e: Exception) {
            Logger.e(TAG, "Verification failed", e)
            Result.success(VerificationResult.Failed("验真过程异常: ${e.message}"))
        }
    }

    // endregion

    // region Hash Generation

    override fun generateVerificationHash(
        timestamp: Long,
        timeSource: String,
        clockOffsetMs: Long
    ): String = hashV1(timestamp, timeSource, clockOffsetMs, instanceId)

    /** v1 binding: timestamp + provenance + app instance. */
    private fun hashV1(
        timestamp: Long,
        timeSource: String,
        clockOffsetMs: Long,
        deviceId: String
    ): String = digest("$APP_NAME|$timestamp|$timeSource|$clockOffsetMs|$deviceId")

    /** v0 binding (older builds): timestamp + device id only. */
    private fun hashV0(timestamp: Long, deviceId: String): String =
        digest("$APP_NAME|$timestamp|$deviceId")

    private fun digest(input: String): String {
        val hashBytes = MessageDigest.getInstance(HASH_ALGORITHM).digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }

    /**
     * All bindings a photo may legitimately carry, so reinstall / legacy photos are not
     * reported as tampered. First entry is the current binding.
     */
    private fun candidateHashes(
        timestamp: Long,
        timeSource: String,
        clockOffsetMs: Long
    ): List<String> = listOf(
        hashV1(timestamp, timeSource, clockOffsetMs, instanceId),
        hashV1(timestamp, timeSource, clockOffsetMs, legacyAndroidId),
        hashV0(timestamp, instanceId),
        hashV0(timestamp, legacyAndroidId)
    ).distinct()

    /**
     * Creation time reported by MediaStore, in ms.
     *
     * DATE_TAKEN is the shutter time (ms); DATE_ADDED is seconds and is only a fallback. Returns
     * 0 when unavailable, and the verifier then simply uses the remaining time sources.
     */
    private fun queryMediaStoreTime(uri: Uri): Long {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return 0L
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val taken = cursor.getLong(0).takeIf { it > 0L }
                val addedMs = cursor.getLong(1).takeIf { it > 0L }?.times(1000L)
                taken ?: addedMs ?: 0L
            } ?: 0L
        } catch (e: Exception) {
            Logger.w(TAG, "MediaStore time unavailable: ${e.message}")
            0L
        }
    }

    // endregion

    // region Parse Verification Data

    override fun parseVerificationData(userComment: String?): VerificationData? =
        TimeVerification.parseUserComment(userComment)

    // endregion

    // region Private Helpers

    /**
     * Build UserComment string for EXIF.
     *
     * v1: "WC1|WatermarkCamera|timestamp|clockOffsetMs|timeSource|hash"
     * (v0 was "WatermarkCamera|timestamp|hash"; parsing still accepts it.)
     */
    private fun buildUserComment(metadata: PhotoMetadata): String = listOf(
        TimeVerification.FORMAT_V1,
        APP_NAME,
        metadata.timestamp.toString(),
        metadata.clockOffsetMs.toString(),
        metadata.timeSource,
        metadata.verificationHash
    ).joinToString("|")

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
     *
     * Kept only for callers that need a filesystem path; verification no longer uses it because
     * [MediaStore.Images.Media.DATA] is empty under scoped storage.
     */
    @Suppress("unused")
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
