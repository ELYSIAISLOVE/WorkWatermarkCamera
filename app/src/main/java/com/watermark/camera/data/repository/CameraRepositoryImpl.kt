package com.watermark.camera.data.repository

import kotlinx.coroutines.Dispatchers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.watermark.camera.domain.repository.CameraRepoEvent
import com.watermark.camera.domain.repository.CameraRepoState
import com.watermark.camera.domain.repository.CameraRepository
import com.watermark.camera.domain.repository.FlashMode
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CameraRepository implementation using CameraX.
 *
 * Manages camera lifecycle, preview, capture, zoom, focus, and exposure.
 * Thread-safe with coroutine-based concurrency.
 */
@Singleton
class CameraRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CameraRepository {

    companion object {
        private const val TAG = "CameraRepo"
        private const val FOCUS_TIMEOUT_MS = 3000L
        private const val CAPTURE_TIMEOUT_MS = 5000L
    }

    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var currentAspectRatio: String = "4:3"

    // State flows
    private val _cameraState = MutableStateFlow<CameraRepoState>(CameraRepoState.Idle)
    override val cameraState: StateFlow<CameraRepoState> = _cameraState.asStateFlow()

    private val _cameraEvents = MutableSharedFlow<CameraRepoEvent>(extraBufferCapacity = 1)
    override val cameraEvents: SharedFlow<CameraRepoEvent> = _cameraEvents.asSharedFlow()

    // Threading
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Lock to prevent concurrent capture
    @Volatile
    private var isCapturing = false

    override suspend fun startPreview(
        lifecycleOwner: LifecycleOwner,
        previewSurface: Preview.SurfaceProvider,
        lensFacing: Int
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            _cameraState.value = CameraRepoState.Initializing
            currentLensFacing = lensFacing

            // Get camera provider
            val provider = getCameraProvider()
                ?: return@withContext Result.failure(Exception("Failed to get camera provider"))

            cameraProvider = provider

            // Unbind all use cases before rebinding
            provider.unbindAll()

            // Build use cases
            val cameraSelector = CameraXConfiguration.buildCameraSelector(lensFacing)
            preview = CameraXConfiguration.buildPreview(currentAspectRatio)
            imageCapture = CameraXConfiguration.buildImageCapture(currentAspectRatio)

            // Set preview surface
            preview?.setSurfaceProvider(previewSurface)

            // Bind to lifecycle
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            _cameraState.value = CameraRepoState.Previewing
            Logger.i(TAG, "Camera preview started. Lens facing: $lensFacing")
            Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start preview", e)
            _cameraState.value = CameraRepoState.Error("相机启动失败: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun stopPreview() {
        withContext(Dispatchers.Main) {
            try {
                cameraProvider?.unbindAll()
                camera = null
                preview = null
                imageCapture = null
                _cameraState.value = CameraRepoState.Idle
                Logger.i(TAG, "Camera preview stopped")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to stop preview", e)
            }
        }
    }

    override suspend fun capturePhoto(): Result<ImageProxy> = withContext(Dispatchers.Main) {
        if (isCapturing) {
            return@withContext Result.failure(Exception("正在处理中，请勿重复拍照"))
        }

        val capture = imageCapture
            ?: return@withContext Result.failure(Exception("相机未就绪"))

        if (_cameraState.value != CameraRepoState.Previewing) {
            return@withContext Result.failure(Exception("相机不在预览状态"))
        }

        try {
            isCapturing = true
            _cameraState.value = CameraRepoState.Capturing

            val deferred = CompletableDeferred<ImageProxy>()

            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        isCapturing = false
                        _cameraState.value = CameraRepoState.Previewing
                        deferred.complete(image)
                        Logger.i(TAG, "Photo captured: ${image.width}x${image.height}")
                    }

                    override fun onError(exception: ImageCaptureException) {
                        isCapturing = false
                        _cameraState.value = CameraRepoState.Previewing
                        deferred.completeExceptionally(exception)
                        Logger.e(TAG, "Capture failed", exception)
                    }
                }
            )

            // Timeout handling
            val result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                deferred.await()
            }

            if (result != null) {
                _cameraEvents.tryEmit(CameraRepoEvent.PhotoCaptured(result))
                Result.success(result)
            } else {
                isCapturing = false
                _cameraState.value = CameraRepoState.Previewing
                Result.failure(Exception("拍照超时"))
            }

        } catch (e: Exception) {
            isCapturing = false
            _cameraState.value = CameraRepoState.Previewing
            Logger.e(TAG, "Capture error", e)
            Result.failure(e)
        }
    }

    override suspend fun setZoomRatio(ratio: Float): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val cameraControl = camera?.cameraControl
                ?: return@withContext Result.failure(Exception("相机未初始化"))

            val cameraInfo = camera?.cameraInfo
                ?: return@withContext Result.failure(Exception("相机信息不可用"))

            val minZoom = cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
            val maxZoom = cameraInfo.zoomState.value?.maxZoomRatio ?: 1.0f
            val clampedRatio = ratio.coerceIn(minZoom, maxZoom)

            cameraControl.setZoomRatio(clampedRatio)
            Logger.d(TAG, "Zoom set to $clampedRatio (range: $minZoom - $maxZoom)")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set zoom", e)
            Result.failure(e)
        }
    }

    override fun getCurrentZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
    }

    override fun getMinZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
    }

    override fun getMaxZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1.0f
    }

    override suspend fun setFlashMode(mode: FlashMode) {
        withContext(Dispatchers.Main) {
            val flashMode = CameraXConfiguration.toImageCaptureFlashMode(mode)
            imageCapture?.flashMode = flashMode
            // When switching away from TORCH, ensure torch is off
            if (mode != FlashMode.TORCH) {
                camera?.cameraControl?.enableTorch(false)
            }
            Logger.d(TAG, "Flash mode set to $mode")
        }
    }

    override fun getTorchState(): Boolean {
        return camera?.cameraInfo?.torchState?.value == TorchState.ON
    }

    override fun isLowLight(): Boolean {
        val cameraInfo = camera?.cameraInfo ?: return false
        val exposureState = cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange
        val currentIndex = exposureState.exposureCompensationIndex
        // Detect low light: EV at upper 80% of range or exposureState reports low light
        return currentIndex >= (range.upper * 0.8).toInt()
    }

    override suspend fun setTorchEnabled(enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                camera?.cameraControl?.enableTorch(enabled)
                Logger.d(TAG, "Torch ${if (enabled) "enabled" else "disabled"}")
                Result.success(Unit)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to set torch", e)
                Result.failure(e)
            }
        }

    override suspend fun setExposureCompensation(evValue: Float): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                val cameraControl = camera?.cameraControl
                    ?: return@withContext Result.failure(Exception("相机未初始化"))

                val cameraInfo = camera?.cameraInfo
                    ?: return@withContext Result.failure(Exception("相机信息不可用"))

                val range = cameraInfo.exposureState.exposureCompensationRange
                val index = (evValue * 10).toInt() // Convert EV to index
                val clampedIndex = index.coerceIn(range.lower, range.upper)

                cameraControl.setExposureCompensationIndex(clampedIndex)
                Logger.d(TAG, "EV set to $evValue (index: $clampedIndex)")
                Result.success(Unit)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to set exposure", e)
                Result.failure(e)
            }
        }

    override suspend fun setAspectRatio(ratio: String): Result<Unit> {
        // Only manual switch updates this; never auto-changed elsewhere
        currentAspectRatio = ratio
        Logger.d(TAG, "Aspect ratio set to $ratio (rebind required)")
        return Result.success(Unit)
    }

    fun getCurrentAspectRatio(): String = currentAspectRatio

    override suspend fun tapToFocus(x: Float, y: Float): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                val cameraControl = camera?.cameraControl
                    ?: return@withContext Result.failure(Exception("相机未初始化"))

                val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                    1.0f, 1.0f
                )
                val point = factory.createPoint(x, y)

                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(FOCUS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()

                cameraControl.startFocusAndMetering(action)
                Logger.d(TAG, "Tap to focus at ($x, $y)")
                Result.success(Unit)
            } catch (e: Exception) {
                Logger.e(TAG, "Focus failed", e)
                Result.failure(e)
            }
        }

    override suspend fun lockFocusExposure(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val cameraControl = camera?.cameraControl
                ?: return@withContext Result.failure(Exception("相机未初始化"))

            cameraControl.cancelFocusAndMetering()
            // Note: CameraX doesn't have a direct AE/AF lock API
            // This is simulated by canceling auto-focus
            Logger.d(TAG, "Focus/Exposure locked")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unlockFocusExposure(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val cameraControl = camera?.cameraControl
                ?: return@withContext Result.failure(Exception("相机未初始化"))

            // Re-enable auto-focus by triggering a new focus action at center
            val factory = SurfaceOrientedMeteringPointFactory(1.0f, 1.0f)
            val point = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(FOCUS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

            cameraControl.startFocusAndMetering(action)
            Logger.d(TAG, "Focus/Exposure unlocked")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun release() {
        // Note: Do NOT shutdown cameraExecutor here, as it may be reused
        // during ViewModel lifetime (e.g., screen rotation).
        // Call shutdown() explicitly from ViewModel.onCleared() instead.
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        preview = null
        imageCapture = null
        _cameraState.value = CameraRepoState.Idle
        Logger.i(TAG, "Camera repository released")
    }

    /**
     * Shutdown the camera executor. Call this when the ViewModel is cleared.
     */
    fun shutdown() {
        cameraExecutor.shutdown()
        release()
        Logger.i(TAG, "Camera repository shutdown")
    }

    /**
     * Get ProcessCameraProvider instance.
     */
    private suspend fun getCameraProvider(): ProcessCameraProvider? {
        return try {
            val deferred = CompletableDeferred<ProcessCameraProvider>()
            ProcessCameraProvider.getInstance(context).addListener({
                try {
                    deferred.complete(ProcessCameraProvider.getInstance(context).get())
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                }
            }, ContextCompat.getMainExecutor(context))
            deferred.await()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to get camera provider", e)
            null
        }
    }

    /**
     * Helper for timeout operations.
     */
    private suspend inline fun <T> withTimeoutOrNull(
        timeMillis: Long,
        crossinline block: suspend () -> T
    ): T? {
        return try {
            kotlinx.coroutines.withTimeout(timeMillis) {
                block()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }
    }
}
