package com.watermark.camera.domain.model

/**
 * Where the timestamp burned into the photo came from.
 *
 * - [NETWORK]  a recent network time sync succeeded; the value is anchored to a server clock.
 * - [OFFLINE_ANCHORED] no fresh sync, but we still project from the last anchor using the
 *   monotonic clock, so changing the system clock does not change the photo time.
 * - [DEVICE]   no anchor at all (first launch while offline); the plain system clock is used.
 */
enum class TimeSource(val label: String) {
    NETWORK("可信时间"),
    OFFLINE_ANCHORED("离线推算"),
    DEVICE("设备时间")
}

/**
 * A resolved, evidence-oriented timestamp.
 *
 * @param timeMs            Timestamp to burn into the watermark / write into EXIF.
 * @param source            Provenance of [timeMs].
 * @param offsetMs          serverClock - deviceClock at the last successful sync.
 * @param uncertaintyMs     Half RTT of the sync (>= 1000 ms, the HTTP Date header resolution).
 * @param lastSyncAtMs      Wall-clock time of the last successful sync (0 if never).
 * @param deviceClockTampered True when the device clock disagrees with the anchored projection.
 */
data class TrustedTime(
    val timeMs: Long,
    val source: TimeSource,
    val offsetMs: Long,
    val uncertaintyMs: Long,
    val lastSyncAtMs: Long,
    val deviceClockTampered: Boolean
) {
    /** Short badge for the watermark, e.g. "可信时间" or "离线推算 09:14". */
    fun badge(syncLabel: String = ""): String = when (source) {
        TimeSource.NETWORK -> TimeSource.NETWORK.label
        TimeSource.OFFLINE_ANCHORED ->
            if (syncLabel.isBlank()) TimeSource.OFFLINE_ANCHORED.label
            else "${TimeSource.OFFLINE_ANCHORED.label} $syncLabel"
        TimeSource.DEVICE -> TimeSource.DEVICE.label
    }

    /** Badge with the last-sync clock time filled in automatically. */
    fun badge(): String = badge(
        if (lastSyncAtMs > 0L) {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastSyncAtMs))
        } else ""
    )
}
