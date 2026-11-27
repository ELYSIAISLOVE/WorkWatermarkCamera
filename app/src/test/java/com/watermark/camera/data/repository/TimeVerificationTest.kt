package com.watermark.camera.data.repository

import com.watermark.camera.domain.repository.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the UserComment parsing / verification rules.
 *
 * The v1 format binds the timestamp to its provenance ("WC1|app|ts|offset|source|hash");
 * the v0 format written by older builds must keep working, otherwise every existing photo
 * would suddenly report as tampered.
 */
class TimeVerificationTest {

    private val timestamp = 1_800_000_000_000L

    // region parseUserComment

    @Test
    fun `parses v1 comment with provenance`() {
        val parsed = TimeVerification.parseUserComment(
            "WC1|WatermarkCamera|1800000000000|2500|NETWORK|abcdef0123456789"
        )

        assertEquals("WatermarkCamera", parsed?.appName)
        assertEquals(timestamp, parsed?.timestamp)
        assertEquals("abcdef0123456789", parsed?.hash)
        assertEquals("NETWORK", parsed?.timeSource)
        assertEquals(2500L, parsed?.clockOffsetMs)
        assertTrue(parsed!!.isV1)
    }

    @Test
    fun `parses legacy v0 comment without provenance`() {
        val parsed = TimeVerification.parseUserComment(
            "WatermarkCamera|1800000000000|abcdef0123456789"
        )

        assertEquals("WatermarkCamera", parsed?.appName)
        assertEquals(timestamp, parsed?.timestamp)
        assertEquals("", parsed?.timeSource)
        assertEquals(0L, parsed?.clockOffsetMs)
        assertFalse(parsed!!.isV1)
    }

    @Test
    fun `rejects malformed comments`() {
        assertNull(TimeVerification.parseUserComment(null))
        assertNull(TimeVerification.parseUserComment(""))
        assertNull(TimeVerification.parseUserComment("WatermarkCamera"))
        assertNull(TimeVerification.parseUserComment("WatermarkCamera|notANumber|hash"))
        // v1 marker but truncated
        assertNull(TimeVerification.parseUserComment("WC1|WatermarkCamera|1800000000000|0"))
    }

    // endregion

    // region verify

    @Test
    fun `accepts matching hash and consistent times`() {
        val result = TimeVerification.verify(
            fileTime = timestamp,
            exifTime = timestamp + 1_000L,
            userCommentTime = timestamp,
            expectedHash = "abcdef0123456789",
            actualHash = "abcdef0123456789"
        )

        assertTrue(result is VerificationResult.Authentic)
    }

    @Test
    fun `accepts an alternative (legacy binding) hash`() {
        val result = TimeVerification.verify(
            fileTime = timestamp,
            exifTime = timestamp,
            userCommentTime = timestamp,
            expectedHash = "newbinding0000000",
            actualHash = "legacybinding0000",
            alternatives = listOf("legacybinding0000")
        )

        assertTrue(result is VerificationResult.Authentic)
    }

    @Test
    fun `flags a hash mismatch as tampered`() {
        val result = TimeVerification.verify(
            fileTime = timestamp,
            exifTime = timestamp,
            userCommentTime = timestamp,
            expectedHash = "expected00000000",
            actualHash = "actual0000000000"
        )

        assertTrue(result is VerificationResult.Tampered)
    }

    @Test
    fun `flags time sources spread beyond tolerance as tampered`() {
        val result = TimeVerification.verify(
            fileTime = timestamp,
            exifTime = timestamp,
            userCommentTime = timestamp + 60_000L, // 编辑过的时间
            expectedHash = "h",
            actualHash = "h"
        )

        assertTrue(result is VerificationResult.Tampered)
    }

    @Test
    fun `fails when fewer than two time sources are available`() {
        val result = TimeVerification.verify(
            fileTime = 0L,
            exifTime = 0L,
            userCommentTime = timestamp,
            expectedHash = "h",
            actualHash = "h"
        )

        assertTrue(result is VerificationResult.Failed)
    }

    // endregion
}
