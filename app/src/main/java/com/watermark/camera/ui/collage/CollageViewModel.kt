package com.watermark.camera.ui.collage

import java.io.FileOutputStream

import java.io.File

import kotlinx.coroutines.withContext

import kotlinx.coroutines.Dispatchers

import android.content.Context

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermark.camera.data.collage.CollageEngine
import com.watermark.camera.data.collage.CollageTemplate
import com.watermark.camera.domain.usecase.CreateCollageUseCase
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the collage creation screen.
 *
 * Manages:
 * - Selected photos list (reserved for Step 12 multi-select integration)
 * - Template selection state
 * - Collage generation progress
 * - Result state (success / error)
 */
@HiltViewModel
class CollageViewModel @Inject constructor(
    private val createCollageUseCase: CreateCollageUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "CollageVM"
    }

    // region State

    /** Currently selected template. */
    private val _selectedTemplate = MutableStateFlow<CollageTemplate>(CollageTemplate.Grid4)
    val selectedTemplate: StateFlow<CollageTemplate> = _selectedTemplate

    /** Selected photo file paths (populated by Step 12 photo picker). */
    private val _selectedPhotos = MutableStateFlow<List<String>>(emptyList())
    val selectedPhotos: StateFlow<List<String>> = _selectedPhotos

    /** Whether a collage is being generated. */
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    /** Generated collage URI on success. */
    private val _collageResult = MutableStateFlow<Uri?>(null)
    val collageResult: StateFlow<Uri?> = _collageResult

    /** Error message on failure. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /** 用户已经看到提示后清空，避免旋转屏幕时重复弹出。 */
    fun clearError() {
        _errorMessage.value = null
    }

    // endregion

    /** 当前拼图外观参数（间距 / 圆角 / 背景），2026-11 新增。 */
    private val _style = MutableStateFlow(CollageEngine.CollageStyle())
    val style: StateFlow<CollageEngine.CollageStyle> = _style.asStateFlow()

    /** 生成成功后可用于分享的 uri。 */
    private val _savedUri = MutableStateFlow<Uri?>(null)
    val savedUri: StateFlow<Uri?> = _savedUri.asStateFlow()

    // region 撤销（规范 §6）

    private data class Snapshot(
        val photos: List<String>,
        val style: CollageEngine.CollageStyle,
        val template: CollageTemplate
    )

    private val history = ArrayDeque<Snapshot>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    /** 结构性改动（选图/换模板/背景/排序/删除）前留快照，最多保留 20 步。 */
    private fun pushHistory() {
        history.addLast(Snapshot(_selectedPhotos.value, _style.value, _selectedTemplate.value))
        while (history.size > 20) history.removeFirst()
        _canUndo.value = true
    }

    fun undo() {
        val snap = history.removeLastOrNull() ?: return
        _selectedPhotos.value = snap.photos
        _style.value = snap.style
        _selectedTemplate.value = snap.template
        _canUndo.value = history.isNotEmpty()
    }

    // endregion

    // 报告栏文字（拼图底部信息条）
    private val _reportTitle = MutableStateFlow("工作汇报")
    val reportTitle: StateFlow<String> = _reportTitle.asStateFlow()

    private val _reportSubtitle = MutableStateFlow("工作现场照片汇总整理")
    val reportSubtitle: StateFlow<String> = _reportSubtitle.asStateFlow()

    private val _reporterName = MutableStateFlow("—")
    val reporterName: StateFlow<String> = _reporterName.asStateFlow()

    fun setReportTitle(v: String) {
        _reportTitle.value = v.ifBlank { "工作汇报" }
    }

    fun setReportSubtitle(v: String) {
        _reportSubtitle.value = v
    }

    fun setReporterName(v: String) {
        _reporterName.value = v.ifBlank { "—" }
    }

    // endregion

    // region 外观

    fun setSpacingPx(px: Int) {
        _style.value = _style.value.copy(spacingPx = px.coerceIn(0, 60))
    }

    fun setCornerRadiusPx(px: Float) {
        _style.value = _style.value.copy(cornerRadiusPx = px.coerceIn(0f, 64f))
    }

    fun setBackgroundColor(@androidx.annotation.ColorInt color: Int) {
        pushHistory()
        _style.value = _style.value.copy(backgroundColor = color)
    }

    fun movePhoto(from: Int, to: Int) {
        val cur = _selectedPhotos.value.toMutableList()
        if (from !in cur.indices) return
        val target = to.coerceIn(0, cur.size - 1)
        if (from == target) return
        pushHistory()
        val item = cur.removeAt(from)
        cur.add(target, item)
        _selectedPhotos.value = cur
    }

    fun clearResult() {
        _savedUri.value = null
    }

    // region User Actions

    /**
     * Select a collage template.
     */
    fun selectTemplate(template: CollageTemplate) {
        if (_selectedTemplate.value == template) return
        pushHistory()
        _selectedTemplate.value = template
        Logger.d(TAG, "Template selected: ${template.displayName}")
    }

    /**
     * Set the list of selected photo paths.
     *
     * Called by Step 12 photo picker after multi-selection.
     *
     * @param paths Absolute file paths of selected photos.
     */
    fun setSelectedPhotos(paths: List<String>) {
        _selectedPhotos.value = paths
        Logger.i(TAG, "Photos selected: ${paths.size}")
    }

    /**
     * Copy Photo Picker content:// URIs into app cache, then use local paths for decode/save.
     * Temporary picker grants often fail when opened via Application ContentResolver later.
     */
    fun importFromPicker(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            _errorMessage.value = null
            val local = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "collage_src").apply { mkdirs() }
                dir.listFiles()?.forEach { f ->
                    if (System.currentTimeMillis() - f.lastModified() > 24L * 3600_000L) {
                        f.delete()
                    }
                }
                uris.mapIndexedNotNull { index, uri ->
                    try {
                        val out = File(dir, "src_${System.currentTimeMillis()}_$index.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(out).use { output -> input.copyTo(output) }
                        } ?: return@mapIndexedNotNull null
                        if (out.length() < 32L) {
                            out.delete()
                            return@mapIndexedNotNull null
                        }
                        out.absolutePath
                    } catch (e: Exception) {
                        Logger.e(TAG, "Copy picker uri failed: $uri", e)
                        null
                    }
                }
            }
            if (local.isEmpty()) {
                _errorMessage.value = "无法读取所选照片（权限或格式）"
                _selectedPhotos.value = emptyList()
            } else {
                _selectedPhotos.value = local
                Logger.i(TAG, "Imported ${local.size} photos to cache for collage")
            }
        }
    }

    /**
     * Add a single photo path.
     */
    fun addPhoto(path: String) {
        val current = _selectedPhotos.value.toMutableList()
        if (!current.contains(path)) {
            pushHistory()
            current.add(path)
            _selectedPhotos.value = current
        }
    }

    /**
     * Remove a photo by index.
     */
    fun removePhotoAt(index: Int) {
        val current = _selectedPhotos.value.toMutableList()
        if (index in current.indices) {
            pushHistory()
            current.removeAt(index)
            _selectedPhotos.value = current
        }
    }

    /**
     * Clear all selections.
     */
    fun clearSelections() {
        if (_selectedPhotos.value.isNotEmpty()) pushHistory()
        _selectedPhotos.value = emptyList()
        _collageResult.value = null
        _errorMessage.value = null
    }

    /**
     * Generate the collage with current selections.
     *
     * @param projectText Optional project name for report bar.
     * @param locationText Optional location (reserved for Step 15 GPS).
     */
    fun generateCollage(
        projectText: String = "",
        locationText: String = ""
    ) {
        // 未显式传入时使用报告栏当前文字
        val project = projectText.ifBlank { "${_reportTitle.value} · ${_reportSubtitle.value}" }
        val paths = _selectedPhotos.value
        val template = _selectedTemplate.value

        if (paths.isEmpty()) {
            _errorMessage.value = "请先选择照片"
            return
        }
        if (paths.size > template.maxPhotos) {
            _errorMessage.value = "${template.displayName}最多支持${template.maxPhotos}张照片"
            return
        }

        _isGenerating.value = true
        _errorMessage.value = null
        _collageResult.value = null

        viewModelScope.launch {
            val result = createCollageUseCase(
                CreateCollageUseCase.Params(
                    photoPaths = paths,
                    template = template,
                    projectText = project,
                    locationText = locationText,
                    style = _style.value
                )
            )

            result.fold(
                onSuccess = { uri ->
                    _collageResult.value = uri
                    _savedUri.value = uri
                    Logger.i(TAG, "Collage created: $uri")
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "拼图生成或保存失败"
                    Logger.e(TAG, "Collage failed", error)
                }
            )
            _isGenerating.value = false
        }
    }

    // endregion
}
