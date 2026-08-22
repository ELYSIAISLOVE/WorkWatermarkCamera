package com.watermark.camera.domain.usecase

import com.watermark.camera.domain.repository.LocationData
import com.watermark.camera.domain.repository.LocationRepository
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Location for watermark. Prefers last-known; retries current fix up to maxAttempts.
 * Display string uses repository.formatForWatermark (Chinese address when Geocoder works).
 */
class GetLocationUseCase @Inject constructor(
    private val locationRepository: LocationRepository,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : UseCase<GetLocationUseCase.Params, LocationData>(dispatcher) {

    companion object {
        private const val TAG = "GetLocationUC"
        private const val DEFAULT_TIMEOUT_MS = 2000L
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 300L
    }

    data class Params(
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val formatForDisplay: Boolean = true,
        val maxAttempts: Int = MAX_ATTEMPTS
    )

    override suspend fun execute(params: Params): Result<LocationData> {
        if (!locationRepository.hasLocationPermission()) {
            return Result.failure(SecurityException("定位权限未授予"))
        }

        try {
            val last = locationRepository.getLastKnownLocation()
            if (last != null) {
                Logger.i(TAG, "lastKnown OK")
                return Result.success(last)
            }
        } catch (_: Exception) {
        }

        val attempts = params.maxAttempts.coerceIn(1, 5)
        var lastError: Throwable = IllegalStateException("定位失败")
        for (attempt in 1..attempts) {
            val result = locationRepository.getCurrentLocation(params.timeoutMs)
            if (result.isSuccess) return Result.success(result.getOrThrow())
            lastError = result.exceptionOrNull() ?: lastError
            if (attempt < attempts) delay(RETRY_DELAY_MS)
        }
        return Result.failure(IllegalStateException("定位失败: ${lastError.message}"))
    }

    suspend fun getLocationString(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        return execute(Params(timeoutMs = timeoutMs)).fold(
            onSuccess = { locationRepository.formatForWatermark(it) },
            onFailure = { "定位失败" }
        )
    }
}
