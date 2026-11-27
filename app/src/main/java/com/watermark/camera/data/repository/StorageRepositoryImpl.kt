package com.watermark.camera.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.watermark.camera.domain.repository.StorageRepository
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of StorageRepository using MediaStore (Android 10+) and
 * legacy direct file access for older devices.
 */
@Singleton
class StorageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : StorageRepository {

    companion object {
        private const val TAG = "StorageRepo"
        private const val PHOTO_PREFIX = "WM_"
        private const val COLLAGE_PREFIX = "COLLAGE_"
        private const val DATE_FORMAT = "yyyyMMdd_HHmmss"
        private const val RELATIVE_PATH = "Pictures/WatermarkCamera"
    }

    override suspend fun savePhoto(
        bitmap: Bitmap,
        fileName: String,
        quality: Int
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveUsingMediaStore(bitmap, fileName, quality, "image/jpeg")
            } else {
                saveLegacy(bitmap, fileName, quality)
            }
            Logger.i(TAG, "Photo saved: $uri")
            Result.success(uri)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save photo", e)
            Result.failure(e)
        }
    }

    override suspend fun saveCollage(
        bitmap: Bitmap,
        fileName: String,
        quality: Int
    ): Result<Uri> = savePhoto(bitmap, fileName, quality) // Same logic

    override suspend fun deletePhoto(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(uri, null, null)
            Logger.i(TAG, "Photo deleted: $uri")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete photo", e)
            Result.failure(e)
        }
    }

    override fun generateFileName(): String {
        val timestamp = SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date())
        return "${PHOTO_PREFIX}${timestamp}.jpg"
    }

    override fun generateCollageFileName(): String {
        val timestamp = SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date())
        return "${COLLAGE_PREFIX}${timestamp}.jpg"
    }

    override fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true // Scoped storage, no permission needed
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun hasEnoughSpace(requiredBytes: Long): Boolean {
        val stat = android.os.StatFs(Environment.getDataDirectory().path)
        val available = stat.availableBytes
        return available > requiredBytes
    }

    override suspend fun cleanupTempFiles() {
        // no-op for stable Unit return
    }

    
    private fun saveUsingMediaStore(
        bitmap: Bitmap,
        fileName: String,
        quality: Int,
        mimeType: String
    ): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.flush()
        } ?: throw IllegalStateException("Failed to open output stream")

        // Clear pending flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        return uri
    }

    /**
     * Legacy save for Android 9 and below.
     */
    private fun saveLegacy(bitmap: Bitmap, fileName: String, quality: Int): Uri {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val appDir = File(directory, "WatermarkCamera").apply { mkdirs() }
        val file = File(appDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.flush()
        }

        // Notify media scanner
        val scanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        scanIntent.data = Uri.fromFile(file)
        context.sendBroadcast(scanIntent)

        return Uri.fromFile(file)
    }
}
