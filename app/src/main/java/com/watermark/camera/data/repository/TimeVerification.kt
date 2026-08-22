package com.watermark.camera.data.repository

import com.watermark.camera.domain.repository.VerificationResult
import com.watermark.camera.util.Logger

/**
 * Triple-time verification system for photo authenticity.
 *
 * Validates photos by comparing three independent time sources:
 * 1. File system modification time
 * 2. EXIF DateTimeOriginal / DateTime
 * 3. UserComment embedded timestamp
 *
 * Plus SHA-256 hash verification in UserComment.
 */
object TimeVerification {

    private const val TAG = "TimeVerification"

    /** Time tolerance between sources: 5 seconds */
    private const val TIME_TOLERANCE_MS = 5000L

    /**
     * Verify photo integrity using triple-time check + hash verification.
     *
     * @param fileTime File system last-modified time (ms)
     * @param exifTime EXIF DateTime parsed timestamp (ms)
     * @param userCommentTime Timestamp embedded in UserComment (ms)
     * @param expectedHash Expected SHA-256 hash from metadata
     * @param actualHash Actual hash from UserComment
     * @return VerificationResult indicating Authentic, Tampered, or Failed
     */
    fun verify(
        fileTime: Long,
        exifTime: Long,
        userCommentTime: Long,
        expectedHash: String,
        actualHash: String
    ): VerificationResult {

        // 1. Hash verification
        if (expectedHash != actualHash) {
            Logger.w(TAG, "Hash mismatch: expected=$expectedHash, actual=$actualHash")
            return VerificationResult.Tampered(
                reason = "校验Hash不匹配，照片可能被篡改",
                details = "Expected: $expectedHash, Actual: $actualHash"
            )
        }

        // 2. Triple time check
        val times = listOf(fileTime, exifTime, userCommentTime).filter { it > 0 }
        if (times.size < 2) {
            return VerificationResult.Failed(
                "时间数据不足，无法完成验真（仅 ${times.size} 个有效时间源）"
            )
        }

        val maxTime = times.maxOrNull()!!
        val minTime = times.minOrNull()!!
        val maxDiff = maxTime - minTime

        if (maxDiff > TIME_TOLERANCE_MS) {
            Logger.w(TAG, "Time mismatch: maxDiff=${maxDiff}ms, sources=$times")
            return VerificationResult.Tampered(
                reason = "时间戳不一致，照片可能被修改",
                details = "最大时间差: ${maxDiff}ms（阈值: ${TIME_TOLERANCE_MS}ms），" +
                    "文件时间=${fileTime}, EXIF时间=${exifTime}, UserComment时间=${userCommentTime}"
            )
        }

        Logger.i(TAG, "Verification passed: ${times.size} time sources match within ${maxDiff}ms")
        return VerificationResult.Authentic
    }

    /**
     * Parse UserComment string.
     * Format: "WatermarkCamera|timestamp|hash"
     */
    fun parseUserComment(userComment: String?): Triple<String, Long, String>? {
        if (userComment.isNullOrBlank()) return null
        val parts = userComment.split("|")
        if (parts.size < 3) return null

        val appName = parts[0]
        val timestamp = parts[1].toLongOrNull() ?: return null
        val hash = parts[2]
        return Triple(appName, timestamp, hash)
    }
}
