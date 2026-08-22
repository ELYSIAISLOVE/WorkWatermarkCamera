package com.watermark.camera.domain.usecase

import com.watermark.camera.domain.repository.LocationData
import com.watermark.camera.domain.repository.LocationRepository
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Location for watermark/EXIF.
 * Prefer last-known (fast), then current location with up to 3 retries.
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
            Logger.w(TAG, "Location permission not granted")
            return Result.failure(SecurityException("定位权限未授予"))
        }

        // Fast path: last known
        try {
            val last = locationRepository.getLastKnownLocation()
            if (last != null) {
                Logger.i(TAG, "Using last known location")
                return Result.success(last)
            }
        } catch (_: Exception) {
        }

        val attempts = params.maxAttempts.coerceIn(1, 5)
        var lastError: Throwable = IllegalStateException("定位失败")

        for (attempt in 1..attempts) {
            Logger.i(TAG, "Location attempt $attempt/$attempts")
            val result = locationRepository.getCurrentLocation(params.timeoutMs)
            if (result.isSuccess) {
                return Result.success(result.getOrThrow())
            }
            lastError = result.exceptionOrNull() ?: lastError
            if (attempt < attempts) delay(RETRY_DELAY_MS)
        }

        return Result.failure(
            IllegalStateException("定位失败（已重试${attempts}次）: ${lastError.message}")
        )
    }

    suspend fun getLocationString(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        return execute(Params(timeoutMs = timeoutMs)).fold(
            onSuccess = { locationRepository.formatForWatermark(it) },
            onFailure = { "定位失败" }
        )
    }
}
