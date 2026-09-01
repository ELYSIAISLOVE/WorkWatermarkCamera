package com.watermark.camera.data.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.OrientationHelper
import com.watermark.camera.domain.model.CaptureResult
import com.watermark.camera.domain.model.ProcessedPhoto
import com.watermark.camera.domain.repository.LocationData
import com.watermark.camera.domain.repository.PhotoMetadata
import com.watermark.camera.domain.repository.MetadataRepository
import com.watermark.camera.domain.repository.StorageRepository
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core image processing pipeline.
 *
 * Orchestrates the complete flow:
 * 1. Decode ImageProxy -> Bitmap (with rotation correction)
 * 2. Apply watermark overlay
 * 3. Build metadata (EXIF, verification hash)
 * 4. Return ProcessedPhoto for MediaStore save + EXIF write
 *
 * Memory control is enforced at each stage via MemoryManager.
 * Bitmap lifecycle is strictly managed to prevent OOM.
 */
@Singleton
class ImageProcessingPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryManager: MemoryManager,
    private val storageRepository: StorageRepository,
    private val metadataRepository: MetadataRepository
) {

    companion object {
        private const val TAG = "ImagePipeline"
        private const val TEMP_DIR = "processing_temp"
        private const val MAX_PROCESSING_DIMENSION = 3072
    }

    private val decoder = BitmapDecoder()

    /**
     * Execute the full processing pipeline.
     *
     * @param captureResult Raw capture from CameraX.
     * @param watermarkConfig Watermark configuration.
     * @param locationStr Current location string for watermark display.
     * @param locationData GPS location data for EXIF (nullable).
     * @return ProcessedPhoto containing final bitmap and metadata.
     */
    suspend fun process(
        captureResult: CaptureResult,
        watermarkConfig: WatermarkConfig,
        locationStr: String = "",
        locationData: LocationData? = null,
        deviceOrientation: OrientationHelper.DeviceOrientation =
            OrientationHelper.DeviceOrientation.PORTRAIT
    ): Result<ProcessedPhoto> = withContext(Dispatchers.Default) {
        val pipelineStart = System.currentTimeMillis()
        val operationId = "proc_${captureResult.timestamp}"

        var sourceBitmap: Bitmap? = null
        var watermarkedBitmap: Bitmap? = null

        try {
            // Stage 1: Memory check
            memoryManager.checkMemory(operationId)

            // Stage 2: Decode
            Logger.i(TAG, "[$operationId] Stage 1/5: Decoding...")
            sourceBitmap = decoder.decode(captureResult.imageProxy, MAX_PROCESSING_DIMENSION)
            memoryManager.checkMemory("${operationId}_decoded")

            // Stage 3: Watermark
            Logger.i(TAG, "[$operationId] Stage 2/5: Applying watermark...")
            val watermarkCanvas = com.watermark.camera.data.watermark.WatermarkCanvas()
            // CameraX 只按竖屏 Activity 转正；横拍需再按快门朝向转到重力正立。
            val decoded = sourceBitmap
                ?: throw IllegalStateException("decode returned null bitmap")
            val extra = extraDegreesForGravity(deviceOrientation)
            val upright: Bitmap = if (extra != 0) {
                val rotated = rotateBitmap(decoded, extra)
                if (rotated !== decoded) {
                    memoryManager.recycle(decoded)
                }
                Logger.i(TAG, "[$operationId] gravity rotate extra=$extra -> ${rotated.width}x${rotated.height}")
                rotated
            } else {
                decoded
            }
            sourceBitmap = upright
            val saveConfig = if (watermarkConfig.useGyroscope) {
                watermarkConfig.copy(
                    position = com.watermark.camera.data.model.WatermarkPosition.BOTTOM_LEFT,
                    customX = null,
                    customY = null
                )
            } else watermarkConfig
            watermarkedBitmap = watermarkCanvas.drawWatermark(
                sourceBitmap = upright,
                config = saveConfig,
                locationStr = locationStr,
                deviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT,
                capturedAtMs = captureResult.timestamp
            )
            // Explicitly recycle source bitmap to free native memory
            memoryManager.recycle(sourceBitmap)
            sourceBitmap = null
            memoryManager.checkMemory("${operationId}_watermarked")

            // Stage 4: Build metadata
            Logger.i(TAG, "[$operationId] Stage 4/5: Building metadata...")
            val metadata = buildMetadata(
                captureResult = captureResult,
                width = watermarkedBitmap.width,
                height = watermarkedBitmap.height,
                watermarkConfig = watermarkConfig,
                locationData = locationData
            )

            val processingTime = System.currentTimeMillis() - pipelineStart
            Logger.perf(TAG, "Full pipeline", processingTime)
            Logger.i(TAG, "[$operationId] Pipeline complete in ${processingTime}ms, " +
                "peakMemory=${memoryManager.getPeakMemory() / 1024 / 1024}MB")

            Result.success(
                ProcessedPhoto(
                    bitmap = watermarkedBitmap,
                    fileName = storageRepository.generateFileName(),
                    metadata = metadata
                )
            )

        } catch (e: Exception) {
            Logger.e(TAG, "[$operationId] Pipeline failed", e)

            // Emergency cleanup
            memoryManager.recycle(sourceBitmap)
            memoryManager.recycle(watermarkedBitmap)
            memoryManager.clearPool()

            Result.failure(e)
        }
    }

    /**
     * Save processed photo to permanent storage and write EXIF.
     *
     * @param processedPhoto The processed photo.
     * @return URI of the saved photo.
     */
    suspend fun save(
        processedPhoto: ProcessedPhoto
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            // Save to MediaStore
            val uriResult = storageRepository.savePhoto(
                bitmap = processedPhoto.bitmap,
                fileName = processedPhoto.fileName,
                quality = 82
            )

            uriResult.fold(
                onSuccess = { uri ->
                    // Write EXIF metadata
                    val exifResult = metadataRepository.writeExif(uri, processedPhoto.metadata)
                    exifResult.fold(
                        onSuccess = {
                            Logger.i(TAG, "Photo saved with EXIF: $uri")
                            Result.success(uri)
                        },
                        onFailure = { exifError ->
                            Logger.w(TAG, "EXIF write failed, photo saved without metadata", exifError)
                            // Photo is saved even if EXIF fails - return success with warning
                            Result.success(uri)
                        }
                    )
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Save failed", e)
            Result.failure(e)
        } finally {
            // Clean up bitmap after save
            memoryManager.recycle(processedPhoto.bitmap)
        }
    }

    /**
     * Clean up all temporary files.
     */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(context.cacheDir, TEMP_DIR)
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { it.delete() }
            }
            memoryManager.clearPool()
            Logger.i(TAG, "Cleanup completed")
        } catch (e: Exception) {
            Logger.w(TAG, "Cleanup error", e)
        }
    }

    /**
     * Build PhotoMetadata from capture result.
     */
    private fun buildMetadata(
        captureResult: CaptureResult,
        width: Int,
        height: Int,
        watermarkConfig: WatermarkConfig,
        locationData: LocationData? = null
    ): PhotoMetadata {
        val timestamp = captureResult.timestamp
        val dateTimeStr = java.text.SimpleDateFormat(
            "yyyy:MM:dd HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date(timestamp))

        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        return PhotoMetadata(
            timestamp = timestamp,
            dateTimeString = dateTimeStr,
            make = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            software = "WatermarkCamera/1.0",
            width = width,
            height = height,
            digitalZoomRatio = captureResult.zoomRatio,
            latitude = locationData?.latitude,
            longitude = locationData?.longitude,
            altitude = locationData?.altitude,
            verificationHash = metadataRepository.generateVerificationHash(timestamp, deviceId),
            imageDescription = "Watermark: ${watermarkConfig.template.displayName}",
            watermarkConfigJson = "{\"template\":\"${watermarkConfig.template.displayName}\"}"
        )
    }

    /**
     * Activity 已锁定竖屏时，CameraX 转正后的图仍是「竖持正立」。
     * 再按陀螺仪朝向补转，使成片与马克水印相机一样：横拍得到横图且内容重力正立。
     */
    private fun extraDegreesForGravity(
        orientation: OrientationHelper.DeviceOrientation
    ): Int = when (orientation) {
        OrientationHelper.DeviceOrientation.LANDSCAPE_LEFT -> 270
        OrientationHelper.DeviceOrientation.LANDSCAPE_RIGHT -> 90
        OrientationHelper.DeviceOrientation.UPSIDE_DOWN -> 180
        else -> 0
    }

    private fun rotateBitmap(source: android.graphics.Bitmap, degrees: Int): android.graphics.Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = android.graphics.Matrix().apply { postRotate((degrees % 360).toFloat()) }
        return android.graphics.Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
    }

}
