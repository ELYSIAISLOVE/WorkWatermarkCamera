package com.watermark.camera.ui.camera

import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.util.OrientationHelper
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.domain.repository.CameraRepository
import com.watermark.camera.domain.usecase.CapturePhotoUseCase
import com.watermark.camera.domain.usecase.GetLocationUseCase
import com.watermark.camera.domain.usecase.GetWatermarkConfigUseCase
import com.watermark.camera.domain.usecase.SaveWatermarkConfigUseCase
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
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
    private val saveWatermarkConfigUseCase: SaveWatermarkConfigUseCase,
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

    private val saveQueueCount = AtomicInteger(0)
    /** Occupied slots 0..MAX_SAVE_QUEUE for left-side meter (accepted+processing). */
    private val _saveQueueDepth = MutableStateFlow(0)
    val saveQueueDepth: kotlinx.coroutines.flow.StateFlow<Int> = _saveQueueDepth
    /** Max concurrent watermark/encode workers. */
    private val processSemaphore = Semaphore(5)
    /** Serialize CameraX capture (hardware is single-shot). */
    private val captureMutex = Mutex()
    /** Min interval between accepted shutter presses (ms). */
    private val MIN_SHUTTER_INTERVAL_MS = 300L
    @Volatile private var lastAcceptedShutterMs = 0L

    @Volatile
    private var currentDeviceOrientation: OrientationHelper.DeviceOrientation =
        OrientationHelper.DeviceOrientation.PORTRAIT

    fun setDeviceOrientation(orientation: OrientationHelper.DeviceOrientation) {
        currentDeviceOrientation = orientation
    }
    private val MAX_SAVE_QUEUE = 10

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
                cameraRepository.setAspectRatio("4:3")
                cameraRepository.setFlashMode(flashMode)
                startLowLightMonitoring()
                startLocationSampling()
                reloadWatermarkConfig()
                updateState { CameraState.Previewing(flashMode = flashMode, aspectRatio = "4:3") }
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


    private val _torchOn = MutableStateFlow(false)
    val torchOn: kotlinx.coroutines.flow.StateFlow<Boolean> = _torchOn

    fun toggleTorch() {
        viewModelScope.launch {
            val next = !_torchOn.value
            val result = cameraRepository.setTorchEnabled(next)
            result.onSuccess {
                _torchOn.value = next
                // Keep flash mode in sync for state
                if (next) {
                    cameraRepository.setFlashMode(com.watermark.camera.domain.repository.FlashMode.TORCH)
                } else {
                    cameraRepository.setFlashMode(com.watermark.camera.domain.repository.FlashMode.OFF)
                }
                sendEvent(CameraEvent.ShowToast(if (next) "补光灯已开启" else "补光灯已关闭"))
            }.onFailure { e ->
                Logger.e(TAG, "torch failed", e)
                sendEvent(CameraEvent.ShowToast("补光灯不可用"))
            }
        }
    }

    fun setTorch(on: Boolean) {
        viewModelScope.launch {
            cameraRepository.setTorchEnabled(on).onSuccess {
                _torchOn.value = on
            }
        }
    }

    fun capturePhoto() {
        val now = System.currentTimeMillis()
        // 0.1s min interval between accepted presses
        if (now - lastAcceptedShutterMs < MIN_SHUTTER_INTERVAL_MS) {
            return
        }
        // Queue full → silent no-op
        if (saveQueueCount.get() >= MAX_SAVE_QUEUE) {
            _saveQueueDepth.value = MAX_SAVE_QUEUE
            return
        }
        lastAcceptedShutterMs = now
        // Reserve slot immediately so meter fills on each press
        val depth = saveQueueCount.incrementAndGet()
        if (depth > MAX_SAVE_QUEUE) {
            saveQueueCount.decrementAndGet()
            _saveQueueDepth.value = MAX_SAVE_QUEUE
            return
        }
        _saveQueueDepth.value = depth.coerceIn(0, MAX_SAVE_QUEUE)

        val frozenConfig = _watermarkConfigDisplay.value
        val frozenLocation = _locationDisplay.value
        val frozenOrientation = currentDeviceOrientation

        viewModelScope.launch {
            // CameraX is sequential
            captureMutex.withLock {
                try {
                    val result = capturePhotoUseCase()
                    result.onSuccess { captureResult ->
                        // Process with up to 5 workers (slot already counted)
                        viewModelScope.launch(Dispatchers.Default) {
                            processSemaphore.acquireUninterruptibly()
                            try {
                                val processResult = processPhotoUseCase(
                                    ProcessPhotoUseCase.Params(
                                        captureResult = captureResult,
                                        watermarkConfig = frozenConfig,
                                        locationStr = frozenLocation,
                                        locationData = null,
                                        deviceOrientation = frozenOrientation
                                    )
                                )
                                processResult.onSuccess {
                                    sendEvent(CameraEvent.GalleryFlash)
                                }.onFailure { e ->
                                    Logger.e(TAG, "Save failed", e)
                                    try { captureResult.close() } catch (_: Exception) {}
                                    sendEvent(CameraEvent.ShowToast("保存失败: ${e.message ?: "未知错误"}"))
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Process failed", e)
                                try { captureResult.close() } catch (_: Exception) {}
                                sendEvent(CameraEvent.ShowToast("处理失败: ${e.message ?: "未知错误"}"))
                            } finally {
                                processSemaphore.release()
                                val left = saveQueueCount.decrementAndGet().coerceAtLeast(0)
                                _saveQueueDepth.value = left.coerceIn(0, MAX_SAVE_QUEUE)
                            }
                        }
                    }.onFailure { e ->
                        Logger.e(TAG, "Capture failed", e)
                        // Release reserved slot
                        val left = saveQueueCount.decrementAndGet().coerceAtLeast(0)
                        _saveQueueDepth.value = left.coerceIn(0, MAX_SAVE_QUEUE)
                        sendEvent(CameraEvent.ShowToast("拍照失败: ${e.message ?: "未知错误"}"))
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Capture exception", e)
                    val left = saveQueueCount.decrementAndGet().coerceAtLeast(0)
                    _saveQueueDepth.value = left.coerceIn(0, MAX_SAVE_QUEUE)
                }
            }
        }
    }


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
    fun setFlashMode(mode: com.watermark.camera.domain.repository.FlashMode) {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState !is CameraState.Previewing) return@launch
            cameraRepository.setFlashMode(mode)
            updateState {
                currentState.copy(
                    flashMode = mode,
                    isTorchOn = mode == com.watermark.camera.domain.repository.FlashMode.TORCH
                )
            }
        }
    }

    fun setImageSizePreset(preset: Int) {
        // 0=small 1=medium 2=large — stored for pipeline; applied next process
        imageSizePreset = preset.coerceIn(0, 2)
    }

    fun setAntiFakeWatermark(enabled: Boolean) {
        antiFakeEnabled = enabled
    }

    @Volatile var imageSizePreset: Int = 1
        private set
    @Volatile var antiFakeEnabled: Boolean = false
        private set

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

    fun updateWatermarkDrag(normX: Float, normY: Float, position: WatermarkPosition) {
        // Update display flow immediately so shutter freeze sees latest drag
        val live = _watermarkConfigDisplay.value.copy(
            position = position,
            customX = normX.coerceIn(0f, 1f),
            customY = normY.coerceIn(0f, 1f),
            showLocation = true,
            
        )
        _watermarkConfigDisplay.value = live
        viewModelScope.launch(Dispatchers.IO) {
            val current = getWatermarkConfigUseCase().getOrDefault(WatermarkConfig())
            val updated = current.copy(
                position = position,
                customX = normX.coerceIn(0f, 1f),
                customY = normY.coerceIn(0f, 1f)
            )
            saveWatermarkConfigUseCase(updated)
            _watermarkConfigDisplay.value = updated
        }
    }

    fun updateWatermarkPosition(position: WatermarkPosition) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = getWatermarkConfigUseCase().getOrDefault(WatermarkConfig())
            val updated = current.copy(position = position)
            saveWatermarkConfigUseCase(updated)
            _watermarkConfigDisplay.value = updated
        }
    }


    /**
     * Apply template from camera bottom strip; persist and refresh preview flow.
     */

    fun applyTimeStyle(style: TimeStyle) {
        viewModelScope.launch {
            val current = getWatermarkConfigUseCase().getOrDefault(WatermarkConfig())
            val updated = current.copy(timeStyle = style, showLocation = true)
            saveWatermarkConfigUseCase(updated)
            _watermarkConfigDisplay.value = updated
        }
    }

    fun applyConfigFromPicker(config: WatermarkConfig) {
        val updated = config.copy(showLocation = true)
        // Sync UI immediately so preview/shutter match selection (don't wait for disk)
        _watermarkConfigDisplay.value = updated
        viewModelScope.launch {
            saveWatermarkConfigUseCase(updated)
        }
    }

    fun applyTemplate(template: WatermarkTemplate) {
        viewModelScope.launch {
            val current = getWatermarkConfigUseCase().getOrDefault(WatermarkConfig())
            val updated = current.copy(
                template = template,
                showLocation = true,
                
            )
            saveWatermarkConfigUseCase(updated)
            _watermarkConfigDisplay.value = updated
        }
    }

    /**
     * Push live overlay config into display flow (used during drag / before shutter).
     */
    fun applyLiveOverlayConfig(config: WatermarkConfig) {
        _watermarkConfigDisplay.value = config.copy(showLocation = true)
    }

    fun reloadWatermarkConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            var cfg = getWatermarkConfigUseCase().getOrDefault(WatermarkConfig())
            // Ensure location line is enabled for preview/save consistency
            if (!cfg.showLocation) {
                cfg = cfg.copy(showLocation = true)
                saveWatermarkConfigUseCase(cfg)
            }
            _watermarkConfigDisplay.value = cfg.copy(showLocation = true)
        }
    }

    /** Re-apply settings + kick location when returning to camera. */
    fun onReturnToCamera() {
        reloadWatermarkConfig()
        startLocationSampling()
    }

    private fun startLocationSampling() {
        locationSampleJob?.cancel()
        locationSampleJob = viewModelScope.launch(Dispatchers.IO) {
            // Always re-fetch on open / return
            _locationDisplay.value = "定位中…"
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
        // Product: fixed 4:3 only — ignore manual ratio switching
        /* suppressed */ Unit
        val currentState = _uiState.value
        if (currentState is CameraState.Previewing && currentState.aspectRatio != "4:3") {
            updateState { currentState.copy(aspectRatio = "4:3") }
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
