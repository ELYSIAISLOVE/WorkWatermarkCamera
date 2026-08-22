package com.watermark.camera.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for location services.
 *
 * Provides GPS + network fused location with automatic fallback.
 */
interface LocationRepository {

    /**
     * Current location state.
     */
    val locationState: StateFlow<LocationState>

    /**
     * Start location updates.
     * Priority: GPS > Network > Last Known > None.
     *
     * @param updateIntervalMs Update interval in milliseconds (default: 5000ms).
     */
    suspend fun startLocationUpdates(updateIntervalMs: Long = 20000L)

    /**
     * Stop location updates.
     */
    fun stopLocationUpdates()

    /**
     * Get the last known location (may be null).
     *
     * @return Last known location, or null if unavailable.
     */
    suspend fun getLastKnownLocation(): LocationData?

    /**
     * Get current location as a one-shot request.
     *
     * @param timeoutMs Timeout in milliseconds (default: 10000ms).
     * @return Result containing location data, or timeout error.
     */
    suspend fun getCurrentLocation(timeoutMs: Long = 10000L): Result<LocationData>

    /**
     * Check if location permission is granted.
     */
    fun hasLocationPermission(): Boolean

    /**
     * Check if GPS is enabled.
     */
    fun isGpsEnabled(): Boolean

    /**
     * Format location for watermark display.
     *
     * @param location The location data.
     * @return Formatted string (e.g., "北京市朝阳区..." or "39.9042°N, 116.4074°E").
     */
    fun formatForWatermark(location: LocationData?): String
    fun isBeidouInUse(): Boolean = false
    fun getConstellationLabel(): String = "卫星定位"
}

/**
 * Location data model.
 *
 * @param latitude Latitude in degrees.
 * @param longitude Longitude in degrees.
 * @param altitude Altitude in meters (null if unavailable).
 * @param accuracy Accuracy radius in meters (null if unavailable).
 * @param provider Provider name ("gps", "network", "fused", "last_known").
 * @param timestamp Timestamp when location was obtained.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val provider: String,
    val timestamp: Long,
    val constellations: String = "",
    val beidouUsed: Boolean = false
)

/**
 * Location state.
 */
sealed class LocationState {
    data object Idle : LocationState()
    data object Searching : LocationState()
    data class Available(val location: LocationData) : LocationState()
    data class Error(val message: String) : LocationState()
    data object PermissionDenied : LocationState()
}
