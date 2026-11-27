package com.watermark.camera.data.time

import com.watermark.camera.domain.model.TimeSource
import com.watermark.camera.domain.model.TrustedTime
import kotlin.math.abs

/**
 * Pure (Android-free) time anchoring math, so it can be unit-tested on the JVM.
 *
 * The anchor stores a server timestamp together with the monotonic clock reading taken at
 * sync time. Later readings are projected with [SystemClock.elapsedRealtime] semantics,
 * which the user cannot move by changing the wall clock.
 */
object TimeAnchorMath {

    /** Default: treat an anchor younger than this as "fresh" (source = NETWORK). */
    const val DEFAULT_FRESH_WINDOW_MS = 10 * 60_000L

    /** Default: device clock drifting more than this from the projection counts as tampering. */
    const val DEFAULT_TAMPER_THRESHOLD_MS = 60_000L

    data class Anchor(
        /** Server time (epoch ms) captured at [syncedElapsedMs]. */
        val serverMs: Long,
        /** Monotonic clock reading (elapsedRealtime) at sync. */
        val elapsedMs: Long,
        /** serverClock - deviceClock measured during the sync. */
        val offsetMs: Long,
        /** Half RTT, floored at the HTTP Date header resolution. */
        val uncertaintyMs: Long,
        /** Wall clock at sync, for display only. */
        val lastSyncAtMs: Long
    )

    /**
     * Project a trusted timestamp.
     *
     * @param anchor       Last successful sync, or null when never synced.
     * @param elapsedNow   Current monotonic clock reading.
     * @param wallNow      Current system clock reading (used only for tamper detection).
     */
    fun estimate(
        anchor: Anchor?,
        elapsedNow: Long,
        wallNow: Long,
        freshWindowMs: Long = DEFAULT_FRESH_WINDOW_MS,
        tamperThresholdMs: Long = DEFAULT_TAMPER_THRESHOLD_MS
    ): TrustedTime {
        if (anchor == null) {
            return TrustedTime(
                timeMs = wallNow,
                source = TimeSource.DEVICE,
                offsetMs = 0L,
                uncertaintyMs = 0L,
                lastSyncAtMs = 0L,
                deviceClockTampered = false
            )
        }
        val elapsedSinceSync = elapsedNow - anchor.elapsedMs
        val projected = anchor.serverMs + elapsedSinceSync
        val tampered = abs(wallNow - projected) > tamperThresholdMs
        val fresh = elapsedSinceSync in 0..freshWindowMs
        return TrustedTime(
            timeMs = projected,
            source = if (fresh) TimeSource.NETWORK else TimeSource.OFFLINE_ANCHORED,
            offsetMs = anchor.offsetMs,
            uncertaintyMs = anchor.uncertaintyMs,
            lastSyncAtMs = anchor.lastSyncAtMs,
            deviceClockTampered = tampered
        )
    }

    /**
     * Combine one probe result into an anchor.
     *
     * @param serverMs   Server epoch from the `Date` header.
     * @param localMidMs Local wall clock at the middle of the request/response window.
     * @param rttMs      Round trip time of the probe.
     * @param elapsedNow Monotonic clock reading right after the response arrived.
     */
    fun anchorFromProbe(
        serverMs: Long,
        localMidMs: Long,
        rttMs: Long,
        elapsedNow: Long,
        wallNow: Long,
        dateResolutionMs: Long = 1_000L
    ): Anchor = Anchor(
        serverMs = serverMs,
        elapsedMs = elapsedNow,
        offsetMs = serverMs - localMidMs,
        uncertaintyMs = (rttMs / 2).coerceAtLeast(dateResolutionMs),
        lastSyncAtMs = wallNow
    )
}
