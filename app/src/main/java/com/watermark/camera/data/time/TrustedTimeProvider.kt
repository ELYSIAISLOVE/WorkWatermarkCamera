package com.watermark.camera.data.time

import android.content.Context
import android.os.SystemClock
import com.watermark.camera.domain.model.TrustedTime
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trusted time for evidence photos (requirement R2 / decision D2).
 *
 * Strategy: read the `Date` response header of a few well-known HTTPS endpoints, keep the
 * sample with the smallest RTT, persist an [TimeAnchorMath.Anchor], and afterwards project
 * time from the monotonic clock so that changing the device clock cannot move the burned-in
 * timestamp. Never throws and never blocks capture: on total failure the previous anchor (or
 * the plain device clock) is used and the caller sees it through [TrustedTime.source].
 */
@Singleton
class TrustedTimeProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "TrustedTime"
        private const val PREF = "trusted_time"
        private const val KEY_SERVER = "anchor_server_ms"
        private const val KEY_ELAPSED = "anchor_elapsed_ms"
        private const val KEY_OFFSET = "anchor_offset_ms"
        private const val KEY_UNCERTAINTY = "anchor_uncertainty_ms"
        private const val KEY_LAST_SYNC = "anchor_last_sync_at"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val MIN_SYNC_INTERVAL_MS = 5 * 60_000L
        private const val PERIODIC_INTERVAL_MS = 60 * 60_000L
        private const val DATE_HEADER_RESOLUTION_MS = 1_000L
        private const val MAX_ACCEPTABLE_RTT_MS = 5_000L

        /** Endpoints used only to read the HTTP `Date` header (no payload is used). */
        private val DEFAULT_ENDPOINTS = listOf(
            "https://www.baidu.com",
            "https://www.taobao.com",
            "https://www.qq.com"
        )
    }

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val syncMutex = Mutex()

    @Volatile
    private var periodicJob: Job? = null

    @Volatile
    private var endpoints: List<String> = DEFAULT_ENDPOINTS

    /** Override endpoints (settings screen / tests). */
    fun setEndpoints(list: List<String>) {
        if (list.isNotEmpty()) endpoints = list
    }

    /** Current trusted time, computed locally (no network, never blocks). */
    fun now(): TrustedTime = TimeAnchorMath.estimate(
        anchor = readAnchor(),
        elapsedNow = SystemClock.elapsedRealtime(),
        wallNow = System.currentTimeMillis()
    )

    /**
     * Try to (re)anchor against the network.
     *
     * @param force skip the [MIN_SYNC_INTERVAL_MS] throttle.
     */
    suspend fun syncNow(force: Boolean = false): TrustedTime = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val previous = readAnchor()
            val wallNow = System.currentTimeMillis()
            if (!force && previous != null && wallNow - previous.lastSyncAtMs in 0..MIN_SYNC_INTERVAL_MS) {
                return@withLock TimeAnchorMath.estimate(previous, SystemClock.elapsedRealtime(), wallNow)
            }
            val anchor = probeAll()
            if (anchor == null) {
                Logger.w(TAG, "time sync failed on ${endpoints.size} endpoints; keeping previous anchor")
                return@withLock TimeAnchorMath.estimate(previous, SystemClock.elapsedRealtime(), wallNow)
            }
            saveAnchor(anchor)
            Logger.i(
                TAG,
                "anchored: offset=${anchor.offsetMs}ms uncertainty=${anchor.uncertaintyMs}ms from $endpoints"
            )
            TimeAnchorMath.estimate(anchor, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        }
    }

    /** Start background resync (idempotent). */
    fun startPeriodicSync(scope: CoroutineScope, intervalMs: Long = PERIODIC_INTERVAL_MS) {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { syncNow() }
                delay(intervalMs)
            }
        }
    }

    fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    // region probing

    private fun probeAll(): TimeAnchorMath.Anchor? {
        var best: Pair<TimeAnchorMath.Anchor, Long>? = null // anchor to rtt
        for (endpoint in endpoints) {
            val sample = probe(endpoint) ?: continue
            if (best == null || sample.second < best.second) best = sample
            if (sample.second <= 200L) break // good enough, stop early
        }
        return best?.first
    }

    private fun probe(endpoint: String): Pair<TimeAnchorMath.Anchor, Long>? =
        probeWith(endpoint, "HEAD") ?: probeWith(endpoint, "GET")

    private fun probeWith(endpoint: String, method: String): Pair<TimeAnchorMath.Anchor, Long>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WatermarkCamera time-sync")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val localStart = System.currentTimeMillis()
            conn.connect()
            val serverMs = conn.getHeaderFieldDate("Date", 0L)
            val localEnd = System.currentTimeMillis()
            val rtt = (localEnd - localStart).coerceAtLeast(0L)
            if (serverMs <= 0L) {
                Logger.w(TAG, "no Date header from $endpoint ($method)")
                return null
            }
            if (rtt > MAX_ACCEPTABLE_RTT_MS) {
                Logger.w(TAG, "rtt too high from $endpoint: ${rtt}ms")
                return null
            }
            val anchor = TimeAnchorMath.anchorFromProbe(
                serverMs = serverMs,
                localMidMs = localStart + rtt / 2,
                rttMs = rtt,
                elapsedNow = SystemClock.elapsedRealtime(),
                wallNow = localEnd,
                dateResolutionMs = DATE_HEADER_RESOLUTION_MS
            )
            anchor to rtt
        } catch (e: Exception) {
            Logger.w(TAG, "probe failed $endpoint ($method): ${e.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // endregion

    // region persistence

    private fun readAnchor(): TimeAnchorMath.Anchor? {
        val server = prefs.getLong(KEY_SERVER, 0L)
        if (server <= 0L) return null
        return TimeAnchorMath.Anchor(
            serverMs = server,
            elapsedMs = prefs.getLong(KEY_ELAPSED, 0L),
            offsetMs = prefs.getLong(KEY_OFFSET, 0L),
            uncertaintyMs = prefs.getLong(KEY_UNCERTAINTY, 0L),
            lastSyncAtMs = prefs.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    private fun saveAnchor(anchor: TimeAnchorMath.Anchor) {
        prefs.edit()
            .putLong(KEY_SERVER, anchor.serverMs)
            .putLong(KEY_ELAPSED, anchor.elapsedMs)
            .putLong(KEY_OFFSET, anchor.offsetMs)
            .putLong(KEY_UNCERTAINTY, anchor.uncertaintyMs)
            .putLong(KEY_LAST_SYNC, anchor.lastSyncAtMs)
            .apply()
    }

    // endregion
}
