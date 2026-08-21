package com.watermark.camera.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.domain.usecase.GetWatermarkConfigUseCase
import com.watermark.camera.domain.usecase.SaveWatermarkConfigUseCase
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun selectTemplate(template: WatermarkTemplate) {
        _config.value = _config.value.copy(template = template)
    }

    fun setName(name: String) {
        _config.value = _config.value.copy(name = name)
    }

    fun setProjectName(project: String) {
        _config.value = _config.value.copy(projectName = project)
    }

    fun setRemark(remark: String) {
        _config.value = _config.value.copy(remark = remark)
    }

    fun setPosition(position: WatermarkPosition) {
        _config.value = _config.value.copy(position = position)
    }

    fun setTransparency(value: Float) {
        _config.value = _config.value.copy(
            transparency = value.coerceIn(
                WatermarkConfig.MIN_TRANSPARENCY,
                WatermarkConfig.MAX_TRANSPARENCY
            )
        )
    }

    fun setFontScale(value: Float) {
        _config.value = _config.value.copy(
            fontScale = value.coerceIn(
                WatermarkConfig.MIN_FONT_SCALE,
                WatermarkConfig.MAX_FONT_SCALE
            )
        )
    }

    fun setShowLocation(show: Boolean) {
        _config.value = _config.value.copy(showLocation = show)
    }

    // endregion

    // region Actions

    /**
     * Save current configuration to persistent storage.
     */
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
