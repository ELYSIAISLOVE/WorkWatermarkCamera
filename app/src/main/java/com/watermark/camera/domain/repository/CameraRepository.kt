package com.watermark.camera.domain.repository

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for camera operations.
 *
 * Encapsulates CameraX interactions and provides a clean API for the UI layer.
 */
interface CameraRepository {

    /**
     * Current camera state as a StateFlow.
     */
    val cameraState: StateFlow<CameraRepoState>

    /**
     * One-time events from the camera (errors, capture complete, etc.).
     */
    val cameraEvents: Flow<CameraRepoEvent>

    /**
     * Initialize and start camera preview.
     *
     * @param lifecycleOwner The lifecycle owner to bind the camera to.
     * @param previewSurface The preview surface provider (PreviewView).
     * @param lensFacing Front or back camera (default: back).
     * @return Result indicating success or failure.
     */
    suspend fun startPreview(
        lifecycleOwner: LifecycleOwner,
        previewSurface: Preview.SurfaceProvider,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK
    ): Result<Unit>

    /**
     * Stop the camera preview and release resources.
     */
    suspend fun stopPreview()

    /**
     * Capture a photo.
     *
     * @return Result containing the captured ImageProxy, or an error.
     */
    suspend fun capturePhoto(): Result<ImageProxy>

    /**
     * Set zoom ratio.
     *
     * @param ratio Zoom ratio (e.g., 1.0f, 2.5f).
     * @return Result indicating success or failure.
     */
    suspend fun setZoomRatio(ratio: Float): Result<Unit>

    /**
     * Get the current zoom ratio.
     */
    fun getCurrentZoomRatio(): Float

    /**
     * Get the minimum zoom ratio supported by the device.
     */
    fun getMinZoomRatio(): Float

    /**
     * Get the maximum zoom ratio supported by the device.
     */
    fun getMaxZoomRatio(): Float

    /**
     * Set flash mode.
     *
     * @param mode Flash mode (auto, on, off, torch).
     */
    suspend fun setFlashMode(mode: FlashMode)

    /**
     * Get current torch (flashlight) state.
     * @return true if torch is on.
     */
    fun getTorchState(): Boolean

    /**
     * Check if current environment is low light.
     * Uses CameraX exposure state to estimate.
     * @return true if low light detected.
     */
    fun isLowLight(): Boolean

    /**
     * Enable or disable torch (flashlight) mode.
     * @param enabled true to turn on torch.
     */
    suspend fun setTorchEnabled(enabled: Boolean): Result<Unit>

    /**
     * Set exposure compensation.
     *
     * @param evValue Exposure value (-2.0 to +2.0).
     */
    suspend fun setExposureCompensation(evValue: Float): Result<Unit>

    /**
     * Set photo aspect ratio.
     *
     * @param ratio Aspect ratio ("4:3", "16:9", "1:1").
     */
    suspend fun setAspectRatio(ratio: String): Result<Unit>

    /**
     * Perform tap-to-focus at the specified point.
     *
     * @param x Normalized x coordinate (0.0 to 1.0).
     * @param y Normalized y coordinate (0.0 to 1.0).
     * @return Result indicating focus success or failure.
     */
    suspend fun tapToFocus(x: Float, y: Float): Result<Unit>

    /**
     * Lock focus and exposure (AE/AF Lock).
     */
    suspend fun lockFocusExposure(): Result<Unit>

    /**
     * Unlock focus and exposure.
     */
    suspend fun unlockFocusExposure(): Result<Unit>

    /**
     * Check if camera permission is granted.
     */
    fun hasCameraPermission(): Boolean

    /**
     * Release all camera resources.
     */
    fun release()
}

/**
 * Camera repository state.
 */
sealed class CameraRepoState {
    data object Idle : CameraRepoState()
    data object Initializing : CameraRepoState()
    data object Previewing : CameraRepoState()
    data object Capturing : CameraRepoState()
    data class Error(val message: String) : CameraRepoState()
}

/**
 * Camera repository events.
 */
sealed class CameraRepoEvent {
    data class PhotoCaptured(val imageProxy: ImageProxy) : CameraRepoEvent()
    data class FocusCompleted(val success: Boolean) : CameraRepoEvent()
    data class Error(val message: String) : CameraRepoEvent()
}

/**
 * Flash mode enumeration.
 */
enum class FlashMode {
    AUTO, ON, OFF, TORCH
}
