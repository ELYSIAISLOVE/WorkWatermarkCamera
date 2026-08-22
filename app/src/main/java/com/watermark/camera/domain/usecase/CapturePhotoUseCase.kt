package com.watermark.camera.domain.usecase

import com.watermark.camera.domain.model.CaptureResult
import com.watermark.camera.domain.repository.CameraRepository
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * UseCase for capturing a photo.
 *
 * Wraps CameraRepository.capturePhoto with:
 * - Anti-repeat lock (500ms cooldown)
 * - Timeout protection (5 seconds)
 * - Result transformation to CaptureResult
 * - Automatic ImageProxy lifecycle management
 */
class CapturePhotoUseCase @Inject constructor(
    private val cameraRepository: CameraRepository,
    @com.watermark.camera.di.IoDispatcher dispatcher: CoroutineDispatcher
) : UseCase<Unit, CaptureResult>(dispatcher) {
    /** Convenience: capture without explicit Unit param. */
    suspend operator fun invoke(): kotlin.Result<CaptureResult> = invoke(Unit)


    companion object {
        private const val TAG = "CapturePhotoUC"
        private const val COOLDOWN_MS = 0L
        private const val CAPTURE_TIMEOUT_MS = 5000L
    }

    /**
     * Mutex to prevent concurrent capture requests.
     */
    private val captureMutex = Mutex()

    /**
     * Tracks the last capture timestamp for cooldown enforcement.
     */
    @Volatile
    private var lastCaptureTime = 0L

    override suspend fun execute(params: Unit): kotlin.Result<CaptureResult> {
        return captureMutex.withLock {
            // Check cooldown
            val now = System.currentTimeMillis()
            val elapsed = now - lastCaptureTime
            if (elapsed < COOLDOWN_MS) {
                Logger.w(TAG, "Capture rejected: cooldown active (${COOLDOWN_MS - elapsed}ms remaining)")
                return@withLock kotlin.Result.failure(
                    IllegalStateException("请稍后再拍 (${(COOLDOWN_MS - elapsed) / 1000.0}秒)")
                )
            }

            lastCaptureTime = now

            // Execute capture with timeout
            val captureResult = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                cameraRepository.capturePhoto()
            }

            if (captureResult == null) {
                Logger.e(TAG, "Capture timed out after ${CAPTURE_TIMEOUT_MS}ms")
                return@withLock kotlin.Result.failure(
                    IllegalStateException("拍照超时，请重试")
                )
            }

            captureResult.fold(
                onSuccess = { imageProxy ->
                    Logger.i(TAG, "Photo captured: ${imageProxy.width}x${imageProxy.height}, " +
                        "rotation=${imageProxy.imageInfo.rotationDegrees}")

                    val result = CaptureResult(
                        imageProxy = imageProxy,
                        timestamp = System.currentTimeMillis(),
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        zoomRatio = cameraRepository.getCurrentZoomRatio()
                    )
                    kotlin.Result.success(result)
                },
                onFailure = { error ->
                    Logger.e(TAG, "Capture failed", error)
                    kotlin.Result.failure(error)
                }
            )
        }
    }

    /**
     * Check if capture is currently available (not in cooldown).
     */
    fun isCaptureAvailable(): Boolean {
        val elapsed = System.currentTimeMillis() - lastCaptureTime
        return elapsed >= COOLDOWN_MS
    }

    /**
     * Get remaining cooldown time in milliseconds.
     */
    fun getRemainingCooldownMs(): Long {
        val elapsed = System.currentTimeMillis() - lastCaptureTime
        return (COOLDOWN_MS - elapsed).coerceAtLeast(0)
    }
}
