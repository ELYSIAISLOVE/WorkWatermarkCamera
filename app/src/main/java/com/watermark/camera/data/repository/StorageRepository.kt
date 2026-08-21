package com.watermark.camera.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for saving photos and collages to MediaStore and file system.
 */
interface StorageRepository {
    /**
     * Save photo bitmap to MediaStore.
     */
    suspend fun savePhoto(bitmap: Bitmap, fileName: String, quality: Int = 95): Result<Uri>

    /**
     * Save collage bitmap to MediaStore.
     */
    suspend fun saveCollage(bitmap: Bitmap, fileName: String, quality: Int = 90): Result<Uri>

    /**
     * Generate a unique file name for photos.
     */
    fun generateFileName(): String

    /**
     * Generate a unique file name for collages.
     */
    fun generateCollageFileName(): String
}

/**
 * Storage repository implementation.
 */
class StorageRepositoryImpl(
    private val context: Context
) : StorageRepository {

    override suspend fun savePhoto(bitmap: Bitmap, fileName: String, quality: Int): Result<Uri> {
        return try {
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: return Result.failure(Exception("Pictures directory not accessible"))

            picturesDir.mkdirs()

            val file = File(picturesDir, fileName)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveCollage(bitmap: Bitmap, fileName: String, quality: Int): Result<Uri> {
        return try {
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: return Result.failure(Exception("Pictures directory not accessible"))

            picturesDir.mkdirs()

            val file = File(picturesDir, fileName)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun generateFileName(): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "WM_${timeStamp}.jpg"
    }

    override fun generateCollageFileName(): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "COLLAGE_${timeStamp}.jpg"
    }
}
