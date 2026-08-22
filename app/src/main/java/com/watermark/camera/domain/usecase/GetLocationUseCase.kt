package com.watermark.camera.domain.usecase

import com.watermark.camera.domain.repository.LocationData
import com.watermark.camera.domain.repository.LocationRepository
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * UseCase for obtaining location data for watermark and EXIF.
 *
 * On failure, retries up to [MAX_ATTEMPTS] times, then returns failure.
 */
class GetLocationUseCase @Inject constructor(
    private val locationRepository: LocationRepository,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : UseCase<GetLocationUseCase.Params, LocationData>(dispatcher) {

    companion object {
        private const val TAG = "GetLocationUC"
        private const val DEFAULT_TIMEOUT_MS = 3000L
        /** Max attempts including the first try (fail 3 times → give up). */
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 400L
    }

    data class Params(
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val formatForDisplay: Boolean = true,
        val maxAttempts: Int = MAX_ATTEMPTS
    )

    data class LocationResult(
        val data: LocationData,
        val displayString: String
    )

    override suspend fun execute(params: Params): Result<LocationData> {
        if (!locationRepository.hasLocationPermission()) {
            Logger.w(TAG, "Location permission not granted")
            return Result.failure(SecurityException("定位权限未授予"))
        }

        val attempts = params.maxAttempts.coerceIn(1, 5)
        var lastError: Throwable = IllegalStateException("定位失败")

        for (attempt in 1..attempts) {
            Logger.i(TAG, "Location attempt $attempt/$attempts, timeout=${params.timeoutMs}ms")
            val result = locationRepository.getCurrentLocation(params.timeoutMs)
            if (result.isSuccess) {
                val data = result.getOrThrow()
                Logger.i(TAG, "Location OK on attempt $attempt: ${data.latitude}, ${data.longitude}")
                return Result.success(data)
            }
            lastError = result.exceptionOrNull() ?: lastError
            Logger.w(TAG, "Location attempt $attempt failed: ${lastError.message}")
            if (attempt < attempts) {
                delay(RETRY_DELAY_MS)
            }
        }

        Logger.e(TAG, "Location failed after $attempts attempts")
        return Result.failure(
            lastError.message?.let { IllegalStateException("定位失败（已重试${attempts}次）: $it") }
                ?: IllegalStateException("定位失败（已重试${attempts}次）")
        )
    }

    /**
     * Formatted string for watermark; after max retries returns failure text.
     */
    suspend fun getLocationString(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        val result = execute(Params(timeoutMs = timeoutMs))
        return result.fold(
            onSuccess = { locationRepository.formatForWatermark(it) },
            onFailure = { "定位失败" }
        )
    }
}
