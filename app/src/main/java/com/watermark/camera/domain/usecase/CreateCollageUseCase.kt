package com.watermark.camera.domain.usecase

import android.graphics.Bitmap
import android.net.Uri
import com.watermark.camera.data.collage.CollageEngine
import com.watermark.camera.data.collage.CollageTemplate
import com.watermark.camera.domain.repository.StorageRepository
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Inject

/**
 * UseCase for creating and saving photo collages.
 *
 * Encapsulates:
 * - Photo selection (reserved for Step 12 multi-select integration)
 * - Collage generation with memory-safe processing
 * - JPEG saving to MediaStore
 * - Cleanup of intermediate Bitmaps
 *
 * @param collageEngine The collage generation engine.
 * @param storageRepository For saving the final collage image.
 * @param dispatcher IO dispatcher for file operations.
 */
class CreateCollageUseCase @Inject constructor(
    private val storageRepository: StorageRepository,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : UseCase<CreateCollageUseCase.Params, Uri>(dispatcher) {

    private val collageEngine = CollageEngine()

    companion object {
        private const val TAG = "CreateCollageUC"
    }

    /**
     * Input parameters.
     *
     * @param photoPaths Absolute file paths of selected photos.
     * @param template Collage layout template.
     * @param projectText Optional project name for the report bar.
     * @param locationText Optional location text (reserved for Step 15 GPS).
     */
    data class Params(
        val photoPaths: List<String>,
        val template: CollageTemplate = CollageTemplate.Grid4,
        val projectText: String = "",
        val locationText: String = ""
    )

    override suspend fun execute(params: Params): Result<Uri> {
        if (params.photoPaths.isEmpty()) {
            return Result.failure(IllegalArgumentException("请先选择照片"))
        }

        Logger.i(TAG, "Creating collage: template=${params.template.displayName}, " +
            "photos=${params.photoPaths.size}")

        // Build report data
        val reportData = CollageEngine.ReportData(
            timeText = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date()),
            locationText = params.locationText.ifBlank { "未获取位置" },
            projectText = params.projectText
        )

        // For VerticalLong template, need a temp file for streaming
        val tempFile = if (params.template is CollageTemplate.VerticalLong) {
            File.createTempFile("collage_", ".jpg")
        } else null

        // Generate collage
        if (com.watermark.camera.data.processing.BitmapDecoder.appContext == null) {
            Logger.w(TAG, "BitmapDecoder.appContext was null before collage")
        }
        val result = collageEngine.generate(
            photoPaths = params.photoPaths,
            template = params.template,
            reportData = reportData,
            outputFile = tempFile
        )

        return result.fold(
            onSuccess = { collageResult ->
                saveCollage(collageResult, tempFile)
            },
            onFailure = { error ->
                // Clean up temp file on failure
                tempFile?.delete()
                Result.failure(error)
            }
        )
    }

    /**
     * Save generated collage to permanent storage.
     */
    private suspend fun saveCollage(
        collageResult: CollageEngine.CollageResult,
        tempFile: File?
    ): Result<Uri> {
        return try {
            val uri = if (collageResult.isStreamed && tempFile != null) {
                // Streamed output already in temp file, just need to copy/rename
                // For simplicity, re-read and save via StorageRepository
                val bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
                    ?: return Result.failure(IllegalStateException("Failed to decode streamed collage"))
                val saveResult = storageRepository.saveCollage(
                    bitmap = bitmap,
                    fileName = storageRepository.generateCollageFileName(),
                    quality = 90
                )
                bitmap.recycle()
                tempFile.delete()
                saveResult
            } else {
                // Bitmap in memory
                val bitmap = collageResult.bitmap
                    ?: return Result.failure(IllegalStateException("Collage bitmap is null"))
                val saveResult = storageRepository.saveCollage(
                    bitmap = bitmap,
                    fileName = storageRepository.generateCollageFileName(),
                    quality = 90
                )
                bitmap.recycle()
                saveResult
            }

            uri.fold(
                onSuccess = { savedUri ->
                    Logger.i(TAG, "Collage saved: $savedUri")
                    Result.success(savedUri)
                },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to save collage", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Save collage failed", e)
            Result.failure(e)
        }
    }
}
