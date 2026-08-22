package com.watermark.camera.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.watermark.camera.domain.repository.LocationData
import com.watermark.camera.domain.repository.LocationRepository
import com.watermark.camera.domain.repository.LocationState
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of LocationRepository using FusedLocationProviderClient.
 *
 * Provides:
 * - GPS + Network + Passive fused location
 * - Automatic fallback chain: GPS -> Network -> Last Known -> None
 * - Address reverse geocoding for watermark display
 * - Location state management with Flow
 *
 * @param context Application context for system services.
 */
@Singleton
class LocationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationRepository {

    companion object {
        private const val TAG = "LocationRepo"
        private const val FASTEST_UPDATE_INTERVAL_MS = 10000L
        /** Continuous location read interval (ms). */
        private const val DEFAULT_UPDATE_INTERVAL_MS = 20000L
        private const val SMALLEST_DISPLACEMENT_M = 5f
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _locationState = MutableStateFlow<LocationState>(LocationState.Idle)
    override val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private val beidouInView = AtomicBoolean(false)
    private val gpsInView = AtomicBoolean(false)

    override fun isBeidouInUse(): Boolean = beidouInView.get()

    override fun getConstellationLabel(): String {
        val parts = mutableListOf<String>()
        if (beidouInView.get()) parts.add("北斗")
        if (gpsInView.get()) parts.add("GPS")
        return if (parts.isEmpty()) "卫星定位" else parts.joinToString("+")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGnssMonitoring() {
        if (!hasLocationPermission()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (gnssCallback != null) return
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var bds = 0
                var gps = 0
                for (i in 0 until status.satelliteCount) {
                    when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_BEIDOU -> bds++
                        GnssStatus.CONSTELLATION_GPS -> gps++
                    }
                }
                beidouInView.set(bds > 0)
                gpsInView.set(gps > 0)
            }
        }
        gnssCallback = cb
        try {
            locationManager.registerGnssStatusCallback(cb, Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) {
            gnssCallback = null
        }
    }


    // region Location Updates

    override suspend fun startLocationUpdates(updateIntervalMs: Long) {
        if (!hasLocationPermission()) {
            _locationState.value = LocationState.PermissionDenied
            Logger.w(TAG, "Location permission not granted")
            return
        }

        if (!isGpsEnabled() && !isNetworkEnabled()) {
            _locationState.value = LocationState.Error("GPS和网络定位均已关闭")
            Logger.w(TAG, "GPS and network location disabled")
            return
        }

        _locationState.value = LocationState.Searching
        startGnssMonitoring()
        Logger.i(TAG, "Starting location updates, interval=${updateIntervalMs}ms")

        val request = LocationRequest.Builder(updateIntervalMs).apply {
            setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            setMinUpdateDistanceMeters(SMALLEST_DISPLACEMENT_M)
            setWaitForAccurateLocation(true)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val data = location.toLocationData()
                    _locationState.value = LocationState.Available(data)
                    Logger.d(TAG, "Location update: ${data.latitude}, ${data.longitude} " +
                        "(accuracy=${data.accuracy}m, provider=${data.provider})")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Logger.w(TAG, "Location not available")
                }
            }
        }

        locationCallback = callback

        try {
            fusedClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            ).await()
        } catch (e: Exception) {
            _locationState.value = LocationState.Error("启动定位失败: ${e.message}")
            Logger.e(TAG, "Failed to start location updates", e)
        }
    }

    override fun stopLocationUpdates() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
            Logger.i(TAG, "Location updates stopped")
        }
        locationCallback = null
        if (_locationState.value is LocationState.Available) {
            _locationState.value = LocationState.Idle
        }
    }

    // endregion

    // region One-shot Location

    override suspend fun getLastKnownLocation(): LocationData? {
        if (!hasLocationPermission()) return null

        return try {
            val location = fusedClient.lastLocation.await()
            location?.toLocationData()?.also {
                Logger.i(TAG, "Last known location: ${it.latitude}, ${it.longitude}")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to get last known location", e)
            null
        }
    }

    override suspend fun getCurrentLocation(timeoutMs: Long): Result<LocationData> {
        if (!hasLocationPermission()) {
            return Result.failure(SecurityException("Location permission not granted"))
        }

        return try {
            _locationState.value = LocationState.Searching
            startGnssMonitoring()

            val location = withTimeout(timeoutMs) {
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                ).await()
            }

            if (location != null) {
                val data = location.toLocationData()
                _locationState.value = LocationState.Available(data)
                Logger.i(TAG, "Current location obtained: ${data.latitude}, ${data.longitude}")
                Result.success(data)
            } else {
                // Fallback to last known
                val lastKnown = getLastKnownLocation()
                if (lastKnown != null) {
                    _locationState.value = LocationState.Available(lastKnown)
                    Logger.i(TAG, "Using last known location as fallback")
                    Result.success(lastKnown)
                } else {
                    _locationState.value = LocationState.Error("无法获取位置信息")
                    Result.failure(IllegalStateException("Location unavailable"))
                }
            }
        } catch (e: TimeoutCancellationException) {
            _locationState.value = LocationState.Error("定位超时")
            Logger.w(TAG, "Location request timed out")
            Result.failure(e)
        } catch (e: Exception) {
            _locationState.value = LocationState.Error("定位失败: ${e.message}")
            Logger.e(TAG, "Location request failed", e)
            Result.failure(e)
        }
    }

    // endregion

    // region Permission & Status

    override fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun isNetworkEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // endregion

    // region Formatting

    override fun formatForWatermark(location: LocationData?): String {
        if (location == null) return "未获取位置"

        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(
                location.latitude, location.longitude, 1
            )

            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val sb = StringBuilder()
                address.adminArea?.let { sb.append(it) }
                address.locality?.let { sb.append(it) }
                address.subLocality?.let { sb.append(it) }
                address.thoroughfare?.let { sb.append(it) }

                val addressStr = sb.toString()
                if (addressStr.isNotBlank()) {
                    return if (location.beidouUsed) "[北斗] $addressStr" else addressStr
                }
            }

            // Fallback to coordinates
            formatCoordinates(location.latitude, location.longitude)
        } catch (e: Exception) {
            Logger.w(TAG, "Geocoding failed", e)
            formatCoordinates(location.latitude, location.longitude)
        }
    }

    /**
     * Format coordinates as human-readable string.
     */
    private fun formatCoordinates(lat: Double, lon: Double): String {
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return String.format(Locale.getDefault(), "%.4f°%s, %.4f°%s",
            kotlin.math.abs(lat), latDir, kotlin.math.abs(lon), lonDir)
    }

    // endregion

    // region Extensions

    /**
     * Convert Android Location to domain LocationData.
     */
    private fun Location.toLocationData(): LocationData {
        val bds = beidouInView.get()
        return LocationData(
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy else null,
            provider = if (bds) "beidou" else (provider ?: "fused"),
            timestamp = time,
            constellations = getConstellationLabel(),
            beidouUsed = bds
        )
    }

    // endregion
}
