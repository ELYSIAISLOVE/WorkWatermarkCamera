package com.watermark.camera.domain.usecase

import android.graphics.Bitmap
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.watermark.WatermarkCanvas
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * UseCase for applying watermark to a bitmap.
 *
 * Encapsulates the watermark drawing logic and provides a clean interface
 * for the image processing pipeline.
 */
class ApplyWatermarkUseCase @Inject constructor(
    @com.watermark.camera.di.DefaultDispatcher dispatcher: CoroutineDispatcher
) : UseCase<ApplyWatermarkUseCase.Params, Bitmap>(dispatcher) {

    companion object {
        private const val TAG = "ApplyWatermarkUC"
    }

    /**
     * Input parameters for watermark application.
     *
     * @param sourceBitmap The original photo bitmap.
     * @param config Watermark configuration.
     * @param locationStr Current location string (may be empty).
     */
    data class Params(
        val sourceBitmap: Bitmap,
        val config: WatermarkConfig,
        val locationStr: String = ""
    )

    private val watermarkCanvas = WatermarkCanvas()

    override suspend fun execute(params: Params): kotlin.Result<Bitmap> {
        return try {
            Logger.i(TAG, "Applying watermark: config=${params.config.template}, " +
                "bitmap=${params.sourceBitmap.width}x${params.sourceBitmap.height}")

            val startTime = System.currentTimeMillis()

            val result = watermarkCanvas.drawWatermark(
                sourceBitmap = params.sourceBitmap,
                config = params.config,
                locationStr = params.locationStr
            )

            val duration = System.currentTimeMillis() - startTime
            Logger.perf(TAG, "Watermark draw", duration)

            // Check if we need to recycle the source bitmap
            // (only if it's a different instance)
            if (result !== params.sourceBitmap && !params.sourceBitmap.isRecycled) {
                params.sourceBitmap.recycle()
            }

            kotlin.Result.success(result)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply watermark", e)
            kotlin.Result.failure(e)
        }
    }

    /**
     * Estimate watermark dimensions for preview layout.
     */
    fun estimateDimensions(
        photoWidth: Int,
        config: WatermarkConfig,
        locationStr: String = ""
    ): Pair<Int, Int> {
        return watermarkCanvas.estimateDimensions(photoWidth, config, locationStr)
    }
}
