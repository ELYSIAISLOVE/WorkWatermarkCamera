package com.watermark.camera.domain.usecase

import com.watermark.camera.domain.repository.LocationData
import com.watermark.camera.domain.repository.LocationRepository
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * UseCase for obtaining location data for watermark and EXIF.
 *
 * Encapsulates:
 * - One-shot location request with timeout
 * - Fallback to last known location
 * - Formatting for watermark display
 * - Permission check
 */
class GetLocationUseCase @Inject constructor(
    private val locationRepository: LocationRepository,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : UseCase<GetLocationUseCase.Params, LocationData>(dispatcher) {

    companion object {
        private const val TAG = "GetLocationUC"
        private const val DEFAULT_TIMEOUT_MS = 10000L
    }

    /**
     * Input parameters.
     *
     * @param timeoutMs Maximum time to wait for location (default 10s).
     * @param formatForDisplay Whether to pre-format the location string for watermark.
     */
    data class Params(
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val formatForDisplay: Boolean = true
    )

    /**
     * Result containing both raw location data and formatted string.
     */
    data class LocationResult(
        val data: LocationData,
        val displayString: String
    )

    override suspend fun execute(params: Params): Result<LocationData> {
        if (!locationRepository.hasLocationPermission()) {
            Logger.w(TAG, "Location permission not granted")
            return Result.failure(SecurityException("定位权限未授予"))
        }

        Logger.i(TAG, "Requesting location, timeout=${params.timeoutMs}ms")

        val result = locationRepository.getCurrentLocation(params.timeoutMs)

        return result.fold(
            onSuccess = { locationData ->
                Logger.i(TAG, "Location obtained: ${locationData.latitude}, ${locationData.longitude}")
                Result.success(locationData)
            },
            onFailure = { error ->
                Logger.e(TAG, "Location request failed", error)
                Result.failure(error)
            }
        )
    }

    /**
     * Get formatted location string for watermark display.
     *
     * @return Formatted address string, or "未获取位置" if unavailable.
     */
    suspend fun getLocationString(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        val result = execute(Params(timeoutMs = timeoutMs))
        return result.fold(
            onSuccess = { locationRepository.formatForWatermark(it) },
            onFailure = { "未获取位置" }
        )
    }
}
