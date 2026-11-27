package com.watermark.camera.data.repository

import com.watermark.camera.domain.repository.VerificationData
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
     * @param fileTime File system / MediaStore time (ms)
     * @param exifTime EXIF DateTime parsed timestamp (ms)
     * @param userCommentTime Timestamp embedded in UserComment (ms)
     * @param expectedHash Expected hash recomputed from the parsed fields
     * @param actualHash Actual hash from UserComment
     * @param alternatives Other acceptable hashes (older bindings / legacy formats)
     * @return VerificationResult indicating Authentic, Tampered, or Failed
     */
    fun verify(
        fileTime: Long,
        exifTime: Long,
        userCommentTime: Long,
        expectedHash: String,
        actualHash: String,
        alternatives: List<String> = emptyList()
    ): VerificationResult {

        // 1. Hash verification
        if (expectedHash != actualHash && actualHash !in alternatives) {
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
     *
     * v1 (current): "WC1|WatermarkCamera|timestamp|clockOffsetMs|timeSource|hash"
     * v0 (legacy):  "WatermarkCamera|timestamp|hash"
     */
    fun parseUserComment(userComment: String?): VerificationData? {
        if (userComment.isNullOrBlank()) return null
        val parts = userComment.trim().split("|")

        if (parts[0] == FORMAT_V1) {
            if (parts.size < 6) return null
            val timestamp = parts[2].toLongOrNull() ?: return null
            return VerificationData(
                appName = parts[1],
                timestamp = timestamp,
                hash = parts[5],
                rawString = userComment,
                timeSource = parts[4],
                clockOffsetMs = parts[3].toLongOrNull() ?: 0L,
                isV1 = true
            )
        }

        if (parts.size < 3) return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        return VerificationData(
            appName = parts[0],
            timestamp = timestamp,
            hash = parts[2],
            rawString = userComment
        )
    }

    /** Marker that identifies the v1 UserComment layout. */
    const val FORMAT_V1 = "WC1"
}
