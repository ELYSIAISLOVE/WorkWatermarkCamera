package com.watermark.camera.domain.usecase

import com.watermark.camera.data.local.WatermarkConfigDataSource
import com.watermark.camera.data.model.WatermarkConfig
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * UseCase for saving watermark configuration.
 *
 * Validates and persists watermark settings to local storage.
 */
class SaveWatermarkConfigUseCase @Inject constructor(
    private val dataSource: WatermarkConfigDataSource,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : UseCase<WatermarkConfig, Boolean>(dispatcher) {

    override suspend fun execute(params: WatermarkConfig): kotlin.Result<Boolean> {
        return try {
            // Validate and clamp values before saving
            val validatedConfig = params.copy(
                transparency = params.clampedTransparency(),
                fontScale = params.clampedFontScale()
            )
            val success = dataSource.saveConfig(validatedConfig)
            kotlin.Result.success(success)
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }
}
