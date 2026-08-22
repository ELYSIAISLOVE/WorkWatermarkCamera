package com.watermark.camera.ui.camera

import com.watermark.camera.ui.common.UiState

/**
 * Camera operation state machine.
 *
 * States:
 * - IDLE: Initial state, camera not active.
 * - PREVIEWING: Camera preview is active, ready to capture.
 * - CAPTURING: Photo capture in progress, UI locked.
 * - PROCESSING: Image processing (watermark, EXIF) in progress.
 * - SAVING: Writing to disk in progress.
 * - ERROR: An error occurred, waiting for user acknowledgment.
 */
sealed class CameraState : UiState {

    /**
     * Initial state. Camera is not bound to any lifecycle.
     */
    data object Idle : CameraState()

    /**
     * Camera preview is active and ready for capture.
     *
     * @param zoomRatio Current zoom ratio (e.g., 1.0x, 2.5x).
     * @param flashMode Current flash mode (AUTO, ON, OFF, TORCH).
     * @param isTorchOn Whether torch (flashlight) is currently on.
     * @param isLowLight Whether environment is detected as low light.
     * @param aspectRatio Current photo aspect ratio ("4:3", "16:9", "1:1").
     * @param evValue Current exposure compensation value.
     * @param isFocusLocked Whether AE/AF is locked.
     */
    data class Previewing(
        val zoomRatio: Float = 1.0f,
        val flashMode: com.watermark.camera.domain.repository.FlashMode = com.watermark.camera.domain.repository.FlashMode.AUTO,
        val isTorchOn: Boolean = false,
        val isLowLight: Boolean = false,
        val aspectRatio: String = "4:3",
        val evValue: Float = 0.0f,
        val isFocusLocked: Boolean = false
    ) : CameraState()

    /**
     * Photo capture triggered, waiting for image to be received.
     * UI should be locked during this state.
     */
    data object Capturing : CameraState()

    /**
     * Image received, applying watermark and processing.
     *
     * @param progress Processing progress from 0 to 100.
     */
    data class Processing(
        val progress: Int = 0
    ) : CameraState()

    /**
     * Processed image is being saved to storage.
     */
    data object Saving : CameraState()

    /**
     * An error occurred during camera operation.
     *
     * @param message User-friendly error message.
     * @param recoverable Whether the error can be recovered from (return to IDLE).
     */
    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : CameraState()
}

/**
 * Camera UI events (one-time events).
 */
sealed class CameraEvent : com.watermark.camera.ui.common.UiEvent {

    /**
     * Show a toast message.
     */
    data class ShowToast(val message: String) : CameraEvent()

    /**
     * Navigate to photo detail screen.
     */
    data class NavigateToDetail(val photoUri: String) : CameraEvent()

    /**
     * Request camera permission.
     */
    data object RequestCameraPermission : CameraEvent()

    /**
     * Request location permission.
     */
    data object RequestLocationPermission : CameraEvent()

    /**
     * Play shutter sound or visual feedback.
     */
    data object ShutterFeedback : CameraEvent()

    /** Short haptic on capture (10ms). */
    data object CaptureHaptic : CameraEvent()

    /** Blink gallery button once — save succeeded (no toast). */
    data object GalleryFlash : CameraEvent()

    data object RequestRebind : CameraEvent()
}
