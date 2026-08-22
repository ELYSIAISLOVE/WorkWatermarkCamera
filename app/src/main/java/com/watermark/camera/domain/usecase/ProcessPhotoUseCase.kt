package com.watermark.camera.domain.usecase

import android.net.Uri
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.util.OrientationHelper
import com.watermark.camera.data.processing.ImageProcessingPipeline
import com.watermark.camera.data.processing.MemoryManager
import com.watermark.camera.domain.model.CaptureResult
import com.watermark.camera.domain.repository.DatabaseRepository
import com.watermark.camera.domain.repository.LocationData
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * UseCase for the complete photo processing workflow.
 *
 * Encapsulates:
 * - Image decoding + rotation
 * - Watermark application
 * - JPEG encoding
 * - MediaStore saving
 * - EXIF metadata writing
 * - Database indexing
 * - Memory cleanup
 *
 * This is the primary entry point for photo processing after capture.
 */
class ProcessPhotoUseCase @Inject constructor(
    private val pipeline: ImageProcessingPipeline,
    private val memoryManager: MemoryManager,
    private val databaseRepository: DatabaseRepository,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : UseCase<ProcessPhotoUseCase.Params, Uri>(dispatcher) {

    companion object {
        private const val TAG = "ProcessPhotoUC"
    }

    /**
     * Input parameters.
     */
    data class Params(
        val captureResult: CaptureResult,
        val watermarkConfig: WatermarkConfig,
        val locationStr: String = "",
        val deviceOrientation: OrientationHelper.DeviceOrientation = OrientationHelper.DeviceOrientation.PORTRAIT,
        val locationData: LocationData? = null
    )

    private val _processingState = MutableStateFlow<com.watermark.camera.domain.model.ProcessingState>(
        com.watermark.camera.domain.model.ProcessingState.Idle
    )
    val processingState: Flow<com.watermark.camera.domain.model.ProcessingState> = _processingState.asStateFlow()

    override suspend fun execute(params: Params): Result<Uri> {
        val startTime = System.currentTimeMillis()
        memoryManager.resetPeak()

        return try {
            // Stage 1: Process image (decode -> watermark -> encode)
            _processingState.value = com.watermark.camera.domain.model.ProcessingState.Decoding

            val processResult = pipeline.process(
                captureResult = params.captureResult,
                watermarkConfig = params.watermarkConfig,
                locationStr = params.locationStr,
                locationData = params.locationData,
                deviceOrientation = params.deviceOrientation
            )

            val processedPhoto = processResult.getOrElse { error ->
                _processingState.value = com.watermark.camera.domain.model.ProcessingState.Error(
                    stage = "process",
                    exception = error
                )
                return Result.failure(error)
            }

            // Stage 2: Save to storage + EXIF
            _processingState.value = com.watermark.camera.domain.model.ProcessingState.Saving

            val saveResult = pipeline.save(processedPhoto)

            val uri = saveResult.getOrElse { error ->
                _processingState.value = com.watermark.camera.domain.model.ProcessingState.Error(
                    stage = "save",
                    exception = error
                )
                // Ensure bitmap is cleaned up even on save failure
                memoryManager.recycle(processedPhoto.bitmap)
                return Result.failure(error)
            }

            // Stage 3: Index in local database
            _processingState.value = com.watermark.camera.domain.model.ProcessingState.Indexing
            val photoEntity = PhotoEntity(
                uri = uri.toString(),
                filePath = null, // MediaStore URI, path resolved on demand
                fileName = processedPhoto.fileName,
                timestamp = processedPhoto.metadata.timestamp,
                width = processedPhoto.metadata.width,
                height = processedPhoto.metadata.height,
                hasWatermark = true,
                hasExifVerification = true,
                latitude = processedPhoto.metadata.latitude,
                longitude = processedPhoto.metadata.longitude,
                watermarkConfigJson = processedPhoto.metadata.watermarkConfigJson
            )
            databaseRepository.insertPhoto(photoEntity)
                .onFailure { error ->
                    Logger.w(TAG, "Database indexing failed (non-critical): ${error.message}")
                }

            // Success
            val totalTime = System.currentTimeMillis() - startTime
            _processingState.value = com.watermark.camera.domain.model.ProcessingState.Success(
                uri = uri,
                fileName = processedPhoto.fileName,
                width = processedPhoto.metadata.width,
                height = processedPhoto.metadata.height,
                processingTimeMs = totalTime
            )

            Logger.i(TAG, "Photo processed, saved, and indexed: $uri in ${totalTime}ms")
            Result.success(uri)

        } catch (e: Exception) {
            Logger.e(TAG, "Processing failed", e)
            _processingState.value = com.watermark.camera.domain.model.ProcessingState.Error(
                stage = "unknown",
                exception = e
            )
            Result.failure(e)
        } finally {
            // Final cleanup
            pipeline.cleanup()
        }
    }

    /**
     * Reset state to idle.
     */
    fun resetState() {
        _processingState.value = com.watermark.camera.domain.model.ProcessingState.Idle
    }
}
