package com.watermark.camera.ui.camera

import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.domain.repository.CameraRepository
import com.watermark.camera.domain.usecase.CapturePhotoUseCase
import com.watermark.camera.domain.usecase.GetLocationUseCase
import com.watermark.camera.domain.usecase.GetWatermarkConfigUseCase
import com.watermark.camera.domain.usecase.ProcessPhotoUseCase
import com.watermark.camera.ui.common.BaseViewModel
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the camera screen.
 *
 * Manages camera state, watermark configuration, and photo capture flow.
 * Bridges UI interactions with CameraRepository and UseCases.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraRepository: CameraRepository,
    private val capturePhotoUseCase: CapturePhotoUseCase,
    private val processPhotoUseCase: ProcessPhotoUseCase,
    private val getWatermarkConfigUseCase: GetWatermarkConfigUseCase,
    private val getLocationUseCase: GetLocationUseCase
) : BaseViewModel<CameraState, CameraEvent>() {

    companion object {
        private const val TAG = "CameraVM"
    }

    override val _uiState = MutableStateFlow<CameraState>(CameraState.Idle)

    /** Chinese location for watermark overlay (updated by continuous sampling). */
    private val _locationDisplay = MutableStateFlow("定位中…")
    val locationDisplay: kotlinx.coroutines.flow.StateFlow<String> = _locationDisplay

    private val _watermarkConfigDisplay = MutableStateFlow(WatermarkConfig())
    val watermarkConfigDisplay: kotlinx.coroutines.flow.StateFlow<WatermarkConfig> = _watermarkConfigDisplay

    private var locationSampleJob: Job? = null

    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastPreviewSurface: Preview.SurfaceProvider? = null

    /**
     * Start camera preview.
     *
     * @param lifecycleOwner The fragment's lifecycle owner.
     * @param previewSurface The PreviewView's surface provider.
     */
    fun startPreview(
        lifecycleOwner: LifecycleOwner,
        previewSurface: Preview.SurfaceProvider
    ) {
        lastLifecycleOwner = lifecycleOwner
        lastPreviewSurface = previewSurface
        viewModelScope.launch {
            val result = cameraRepository.startPreview(
                lifecycleOwner = lifecycleOwner,
                previewSurface = previewSurface
            )
            result.onSuccess {
                val flashMode = (_uiState.value as? CameraState.Previewing)?.flashMode
                    ?: com.watermark.camera.domain.repository.FlashMode.AUTO
                cameraRepository.setFlashMode(flashMode)
                startLowLightMonitoring()
                startLocationSampling()
                reloadWatermarkConfig()
                updateState { CameraState.Previewing(flashMode = flashMode) }
            }.onFailure { e ->
                updateState { CameraState.Error("相机启动失败: ${e.message}", recoverable = true) }
            }
        }
    }

    /**
     * Stop camera preview.
     */
    fun stopPreview() {
        viewModelScope.launch {
            cameraRepository.stopPreview()
            updateState { CameraState.Idle }
        }
    }

    /**
     * Initialize camera preview (legacy method for onResume).
     */
    fun initializeCamera() {
        // Called from onResume; actual binding happens in startPreview
        if (_uiState.value is CameraState.Idle) {
            // Will be bound when Fragment calls startPreview
        }
    }

    /**
     * Trigger photo capture.
     * State transition: PREVIEWING -> CAPTURING -> PROCESSING -> SAVING -> PREVIEWING.
     */

    fun capturePhoto() {
        val currentState = _uiState.value
        if (currentState !is CameraState.Previewing) {
            sendEvent(CameraEvent.ShowToast("相机未就绪"))
            return
        }

        if (!capturePhotoUseCase.isCaptureAvailable()) {
            val remaining = capturePhotoUseCase.getRemainingCooldownMs()
            if (remaining > 0) {
                sendEvent(CameraEvent.ShowToast("请稍后再拍 (${remaining}ms)"))
                return
            }
        }

        val flashMode = currentState.flashMode
        updateState { CameraState.Capturing }
        sendEvent(CameraEvent.ShutterFeedback)
        sendEvent(CameraEvent.CaptureHaptic)

        viewModelScope.launch {
            val result = capturePhotoUseCase()
            result.onSuccess { captureResult ->
                Logger.i(TAG, "Photo captured: ${captureResult.width}x${captureResult.height}")
                // Unlock shutter immediately so continuous shooting is not blocked by save pipeline
                updateState { CameraState.Previewing(flashMode = flashMode) }

                // Background: watermark + save (uses cached location; no long GPS wait)
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val config = getWatermarkConfigUseCase().getOrDefault(WatermarkConfig())
                        val locationStr = _locationDisplay.value.takeIf {
                            it.isNotBlank() && it != "定位中…" && it != "定位失败"
                        } ?: "定位中"
                        val locationData: com.watermark.camera.domain.repository.LocationData? = null

                        val processResult = processPhotoUseCase(
                            ProcessPhotoUseCase.Params(
                                captureResult = captureResult,
                                watermarkConfig = config,
                                locationStr = locationStr,
                                locationData = locationData
                            )
                        )
                        processResult.onSuccess {
                            sendEvent(CameraEvent.ShowToast("照片已保存"))
                        }.onFailure { e ->
                            Logger.e(TAG, "Save failed", e)
                            try { captureResult.close() } catch (_: Exception) {}
                            sendEvent(CameraEvent.ShowToast("保存失败: ${e.message ?: "未知错误"}"))
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Process failed", e)
                        try { captureResult.close() } catch (_: Exception) {}
                        sendEvent(CameraEvent.ShowToast("处理失败: ${e.message ?: "未知错误"}"))
                    }
                }
            }.onFailure { e ->
                Logger.e(TAG, "Capture failed", e)
                updateState { CameraState.Previewing(flashMode = flashMode) }
                sendEvent(CameraEvent.ShowToast("拍照失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    /**
     * Set zoom ratio.
     *
     * @param ratio Zoom ratio value.
     */
    fun setZoomRatio(ratio: Float) {
        viewModelScope.launch {
            val result = cameraRepository.setZoomRatio(ratio)
            result.onSuccess {
                val currentState = _uiState.value
                if (currentState is CameraState.Previewing) {
                    updateState { currentState.copy(zoomRatio = cameraRepository.getCurrentZoomRatio()) }
                }
            }
        }
    }

    /**
     * Cycle through flash modes: AUTO -> ON -> OFF -> TORCH -> AUTO.
     */
    fun cycleFlashMode() {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState !is CameraState.Previewing) return@launch

            val nextMode = when (currentState.flashMode) {
                com.watermark.camera.domain.repository.FlashMode.AUTO ->
                    com.watermark.camera.domain.repository.FlashMode.ON
                com.watermark.camera.domain.repository.FlashMode.ON ->
                    com.watermark.camera.domain.repository.FlashMode.OFF
                com.watermark.camera.domain.repository.FlashMode.OFF ->
                    com.watermark.camera.domain.repository.FlashMode.TORCH
                com.watermark.camera.domain.repository.FlashMode.TORCH ->
                    com.watermark.camera.domain.repository.FlashMode.AUTO
            }

            // Handle torch separately
            if (nextMode == com.watermark.camera.domain.repository.FlashMode.TORCH) {
                cameraRepository.setTorchEnabled(true)
            } else {
                cameraRepository.setTorchEnabled(false)
                cameraRepository.setFlashMode(nextMode)
            }

            val modeName = when (nextMode) {
                com.watermark.camera.domain.repository.FlashMode.AUTO -> "自动"
                com.watermark.camera.domain.repository.FlashMode.ON -> "开启"
                com.watermark.camera.domain.repository.FlashMode.OFF -> "关闭"
                com.watermark.camera.domain.repository.FlashMode.TORCH -> "常亮"
            }
            updateState { currentState.copy(flashMode = nextMode, isTorchOn = nextMode == com.watermark.camera.domain.repository.FlashMode.TORCH) }
            sendEvent(CameraEvent.ShowToast("闪光灯: $modeName"))
        }
    }

    /**
     * Turn on flash (used by low-light warning button).
     */
    fun turnOnFlash() {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState !is CameraState.Previewing) return@launch
            cameraRepository.setFlashMode(com.watermark.camera.domain.repository.FlashMode.ON)
            updateState { currentState.copy(flashMode = com.watermark.camera.domain.repository.FlashMode.ON, isLowLight = false) }
            sendEvent(CameraEvent.ShowToast("闪光灯已开启"))
        }
    }

    /**
     * Start periodic low-light monitoring.
     */

    /**
     * Continuous location sampling until success or 10s timeout.
     * Updates [locationDisplay] with Chinese address (not lat/lng).
     */
    fun reloadWatermarkConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = getWatermarkConfigUseCase().getOrDefault(WatermarkConfig())
            _watermarkConfigDisplay.value = cfg
        }
    }

    private fun startLocationSampling() {
        locationSampleJob?.cancel()
        locationSampleJob = viewModelScope.launch(Dispatchers.IO) {
            if (_locationDisplay.value.isBlank() || _locationDisplay.value == "定位失败") {
                _locationDisplay.value = "定位中…"
            }
            val firstDeadline = System.currentTimeMillis() + 10_000L
            var gotFix = false
            // First fix within 10s
            while (isActive && System.currentTimeMillis() < firstDeadline && !gotFix) {
                val text = getLocationUseCase.getLocationString(timeoutMs = 2_000L)
                if (text.isNotBlank() && text != "定位失败" && text != "未获取位置") {
                    _locationDisplay.value = text
                    gotFix = true
                    Logger.i(TAG, "Location display updated: $text")
                } else {
                    delay(400L)
                }
            }
            if (!gotFix && (_locationDisplay.value == "定位中…" || _locationDisplay.value.isBlank())) {
                _locationDisplay.value = "定位失败"
            }
            // Keep refreshing every 20s while preview is active
            while (isActive) {
                delay(20_000L)
                val text = getLocationUseCase.getLocationString(timeoutMs = 2_000L)
                if (text.isNotBlank() && text != "定位失败" && text != "未获取位置") {
                    _locationDisplay.value = text
                }
            }
        }
    }

    private fun startLowLightMonitoring() {
        viewModelScope.launch {
            while (true) {
                val currentState = _uiState.value
                if (currentState !is CameraState.Previewing) break
                val isLowLight = cameraRepository.isLowLight()
                if (isLowLight != currentState.isLowLight) {
                    updateState { currentState.copy(isLowLight = isLowLight) }
                }
                kotlinx.coroutines.delay(2000) // Check every 2 seconds
            }
        }
    }

    /**
     * Get minimum zoom ratio.
     */
    fun getMinZoomRatio(): Float = cameraRepository.getMinZoomRatio()

    /**
     * Get maximum zoom ratio.
     */
    fun getMaxZoomRatio(): Float = cameraRepository.getMaxZoomRatio()

    /**
     * Handle tap to focus.
     *
     * @param x Normalized x coordinate (0.0 to 1.0).
     * @param y Normalized y coordinate (0.0 to 1.0).
     */
    fun tapToFocus(x: Float, y: Float) {
        viewModelScope.launch {
            cameraRepository.tapToFocus(x, y)
        }
    }

    /**
     * Toggle AE/AF lock.
     */
    fun toggleFocusLock() {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState !is CameraState.Previewing) return@launch

            val isLocked = currentState.isFocusLocked
            val result = if (isLocked) {
                cameraRepository.unlockFocusExposure()
            } else {
                cameraRepository.lockFocusExposure()
            }

            result.onSuccess {
                updateState {
                    currentState.copy(isFocusLocked = !isLocked)
                }
                sendEvent(CameraEvent.ShowToast(if (isLocked) "AE/AF 已解锁" else "AE/AF 已锁定"))
            }
        }
    }

    /**
     * Set exposure compensation.
     *
     * @param evValue Exposure value (-2.0 to +2.0).
     */
    fun setExposureCompensation(evValue: Float) {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState !is CameraState.Previewing) return@launch

            cameraRepository.setExposureCompensation(evValue).onSuccess {
                updateState { currentState.copy(evValue = evValue) }
            }
        }
    }

    /**
     * Cycle through aspect ratios: 4:3 -> 16:9 -> 1:1 -> 4:3.
     */
    fun cycleAspectRatio() {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState !is CameraState.Previewing) return@launch

            val nextRatio = when (currentState.aspectRatio) {
                "4:3" -> "16:9"
                "16:9" -> "1:1"
                "1:1" -> "4:3"
                else -> "4:3"
            }

            cameraRepository.setAspectRatio(nextRatio).onSuccess {
                updateState { currentState.copy(aspectRatio = nextRatio) }
                sendEvent(CameraEvent.ShowToast("比例: $nextRatio"))
                sendEvent(CameraEvent.RequestRebind)
            }
        }
    }

    /**
     * Handle camera error.
     */
    fun onCameraError(message: String, recoverable: Boolean = true) {
        updateState { CameraState.Error(message, recoverable) }
    }

    /**
     * Recover from error state back to idle.
     */
    fun recoverFromError() {
        val currentState = _uiState.value
        if (currentState is CameraState.Error && currentState.recoverable) {
            updateState { CameraState.Idle }
            initializeCamera()
        }
    }

    /**
     * Clean up when camera is no longer needed.
     */
    fun releaseCamera() {
        cameraRepository.release()
        updateState { CameraState.Idle }
    }

    /**
     * Called when ViewModel is about to be destroyed.
     * Shuts down the camera executor to prevent memory leaks.
     */
    override fun onCleared() {
        super.onCleared()
        (cameraRepository as? com.watermark.camera.data.repository.CameraRepositoryImpl)?.shutdown()
            ?: cameraRepository.release()
        Logger.i(TAG, "CameraViewModel cleared")
    }
}
