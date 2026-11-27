package com.watermark.camera.domain.usecase

import com.watermark.camera.data.local.WatermarkConfigDataSource
import com.watermark.camera.data.model.WatermarkConfig
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * UseCase for retrieving the current watermark configuration.
 *
 * Returns the stored config, or default values if none exists.
 */
class GetWatermarkConfigUseCase @Inject constructor(
    private val dataSource: WatermarkConfigDataSource,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : NoParamsUseCase<WatermarkConfig>(dispatcher) {

    override suspend fun execute(params: Unit): kotlin.Result<WatermarkConfig> {
        return try {
            val config = dataSource.loadConfig()
            kotlin.Result.success(config)
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }
}
