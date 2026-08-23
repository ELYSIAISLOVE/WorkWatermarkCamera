package com.watermark.camera.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.domain.usecase.GetWatermarkConfigUseCase
import com.watermark.camera.domain.usecase.SaveWatermarkConfigUseCase
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the watermark settings BottomSheet.
 *
 * Manages:
 * - Current watermark configuration state
 * - Template selection
 * - Custom field editing (name, project, remark)
 * - Position, transparency, font scale adjustments
 * - Save / reset actions
 */
@HiltViewModel
class WatermarkSettingsViewModel @Inject constructor(
    private val getConfigUseCase: GetWatermarkConfigUseCase,
    private val saveConfigUseCase: SaveWatermarkConfigUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "WatermarkSettingsVM"
    }

    // region State

    /** Current configuration being edited. */
    private val _config = MutableStateFlow(WatermarkConfig())
    val config: StateFlow<WatermarkConfig> = _config.asStateFlow()

    /** Whether config is being saved. */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /** Save success event. */
    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    /** Error message. */
    private val _errorMessage = MutableStateFlow<String?>(null)

    private var autoSaveJob: Job? = null
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // endregion

    // region Lifecycle

    init {
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val result = getConfigUseCase(Unit)
            result.fold(
                onSuccess = { config ->
                    _config.value = config
                    Logger.i(TAG, "Config loaded: ${config.template.displayName}")
                },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to load config", error)
                    _errorMessage.value = "加载配置失败: ${error.message}"
                }
            )
        }
    }

    // endregion

    // region Field Updates

    fun selectTimeStyle(style: TimeStyle) {
        val current = _config.value
        updateConfig(current.copy(timeStyle = style))
    }

    fun selectTemplate(template: WatermarkTemplate) {
        _config.value = _config.value.copy(template = template)
        scheduleAutoSave()
    }

    fun setName(name: String) {
        _config.value = _config.value.copy(name = name)
        scheduleAutoSave()
    }

    fun setProjectName(project: String) {
        _config.value = _config.value.copy(projectName = project)
        scheduleAutoSave()
    }

    fun setRemark(remark: String) {
        _config.value = _config.value.copy(remark = remark)
        scheduleAutoSave()
    }

    fun setPosition(position: WatermarkPosition) {
        _config.value = _config.value.copy(position = position)
        scheduleAutoSave()
    }

    fun setTransparency(value: Float) {
        _config.value = _config.value.copy(
            transparency = value.coerceIn(
                WatermarkConfig.MIN_TRANSPARENCY,
                WatermarkConfig.MAX_TRANSPARENCY
            )
        )
        scheduleAutoSave()
    }

    fun setFontScale(value: Float) {
        _config.value = _config.value.copy(
            fontScale = value.coerceIn(
                WatermarkConfig.MIN_FONT_SCALE,
                WatermarkConfig.MAX_FONT_SCALE
            )
        )
        scheduleAutoSave()
    }

    fun setShowLocation(show: Boolean) {
        _config.value = _config.value.copy(showLocation = show)
        scheduleAutoSave()
    }

    // endregion

    // region Actions

    /**
     * Save current configuration to persistent storage.
     */

    /** Debounced silent auto-save (no toast). */
    fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(500L)
            saveConfigSilent()
        }
    }

    private suspend fun saveConfigSilent() {
        try {
            val result = saveConfigUseCase(_config.value)
            result.onSuccess { ok ->
                if (ok) Logger.i(TAG, "Auto-saved watermark config")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Auto-save failed", e)
        }
    }

    fun saveConfig() {
        viewModelScope.launch {
            _isSaving.value = true
            _saveSuccess.value = false
            _errorMessage.value = null

            val result = saveConfigUseCase(_config.value)
            result.fold(
                onSuccess = { success ->
                    if (success) {
                        _saveSuccess.value = true
                        Logger.i(TAG, "Config saved successfully")
                        // Reload so UI matches disk
                        val reloaded = getConfigUseCase(Unit).getOrNull()
                        if (reloaded != null) _config.value = reloaded
                    } else {
                        _errorMessage.value = "保存失败"
                    }
                },
                onFailure = { error ->
                    _errorMessage.value = "保存失败: ${error.message}"
                    Logger.e(TAG, "Failed to save config", error)
                }
            )
            _isSaving.value = false
        }
    }

    /**
     * Reset to default configuration.
     */
    fun resetToDefault() {
        _config.value = WatermarkConfig()
        Logger.i(TAG, "Config reset to default")
    }

    fun consumeSaveSuccess() {
        _saveSuccess.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // endregion
}
