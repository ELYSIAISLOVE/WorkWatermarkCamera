package com.watermark.camera.data.time

import com.watermark.camera.domain.model.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Android-free time anchoring math (requirement R2).
 */
class TimeAnchorMathTest {

    private val anchor = TimeAnchorMath.Anchor(
        serverMs = 1_700_000_000_000L,
        elapsedMs = 1_000L,
        offsetMs = 5_000L,
        uncertaintyMs = 1_000L,
        lastSyncAtMs = 1_700_000_000_000L
    )

    @Test
    fun noAnchorFallsBackToDeviceClock() {
        val t = TimeAnchorMath.estimate(null, elapsedNow = 9_999L, wallNow = 123_456L)
        assertEquals(TimeSource.DEVICE, t.source)
        assertEquals(123_456L, t.timeMs)
        assertFalse(t.deviceClockTampered)
    }

    @Test
    fun freshAnchorIsNetworkTime() {
        val t = TimeAnchorMath.estimate(
            anchor,
            elapsedNow = anchor.elapsedMs + 30_000L,
            wallNow = anchor.serverMs + 30_000L
        )
        assertEquals(TimeSource.NETWORK, t.source)
        assertEquals(anchor.serverMs + 30_000L, t.timeMs)
        assertEquals(anchor.offsetMs, t.offsetMs)
    }

    @Test
    fun staleAnchorDegradesToOfflineProjection() {
        val t = TimeAnchorMath.estimate(
            anchor,
            elapsedNow = anchor.elapsedMs + TimeAnchorMath.DEFAULT_FRESH_WINDOW_MS + 1L,
            wallNow = anchor.serverMs + TimeAnchorMath.DEFAULT_FRESH_WINDOW_MS + 1L
        )
        assertEquals(TimeSource.OFFLINE_ANCHORED, t.source)
    }

    @Test
    fun changingWallClockDoesNotMoveProjectedTime() {
        val elapsed = anchor.elapsedMs + 60_000L
        val honest = TimeAnchorMath.estimate(anchor, elapsed, anchor.serverMs + 60_000L)
        // user moved the device clock one day ahead
        val faked = TimeAnchorMath.estimate(anchor, elapsed, anchor.serverMs + 60_000L + 86_400_000L)

        assertEquals(honest.timeMs, faked.timeMs)
        assertFalse(honest.deviceClockTampered)
        assertTrue(faked.deviceClockTampered)
    }

    @Test
    fun anchorFromProbeComputesOffsetAndUncertainty() {
        val a = TimeAnchorMath.anchorFromProbe(
            serverMs = 1_700_000_000_000L,
            localMidMs = 1_700_000_000_500L,
            rttMs = 400L,
            elapsedNow = 7_000L,
            wallNow = 1_700_000_000_700L
        )
        assertEquals(-500L, a.offsetMs)
        // half RTT is 200 ms but the Date header only has 1 s resolution
        assertEquals(1_000L, a.uncertaintyMs)
        assertEquals(7_000L, a.elapsedMs)
        assertEquals(1_700_000_000_000L, a.serverMs)
    }

    @Test
    fun largeRttWidensUncertainty() {
        val a = TimeAnchorMath.anchorFromProbe(
            serverMs = 1_700_000_000_000L,
            localMidMs = 1_700_000_000_000L,
            rttMs = 3_000L,
            elapsedNow = 0L,
            wallNow = 0L
        )
        assertEquals(1_500L, a.uncertaintyMs)
    }
}
