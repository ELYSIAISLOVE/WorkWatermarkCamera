package com.watermark.camera.ui.gallery

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermark.camera.data.gallery.GalleryImporter
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.domain.repository.DatabaseRepository
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 相册 ViewModel。
 *
 * 相比旧实现（审计 C1/C2/C3/C4/C7）的关键变化：
 * - **数据源改为 Room**：只显示本应用拍摄的照片，MediaStore 仅用于首次补齐索引，
 *   不再有「查不到本应用目录就查全机照片」的兜底（那会让别家 App 的照片混进来）。
 * - **去掉 200 张硬上限**：改为分页，滚动到底继续加载。
 * - **删除结果如实回报**：旧实现 `if (rows > 0 || true) ok++` 恒真，失败也说成功。
 * - **支持搜索与全选**。
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseRepository: DatabaseRepository,
    private val galleryImporter: GalleryImporter
) : ViewModel() {

    companion object {
        private const val TAG = "GalleryVM"

        /** 每页条数。 */
        private const val PAGE_SIZE = 60

        private const val PREF = "gallery_ui"
        private const val KEY_COLUMNS = "grid_columns"
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 5
    }

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前已加载的照片（按时间倒序）。 */
    private val _photos = MutableStateFlow<List<PhotoEntity>>(emptyList())
    val photos: StateFlow<List<PhotoEntity>> = _photos.asStateFlow()

    /** 分组后的列表项（日期头 + 照片），UI 直接消费。 */
    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    /** 选中顺序即角标顺序，用 List 而非 Set 保留点击次序。 */
    private val _selectedUris = MutableStateFlow<Set<String>>(linkedSetOf())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 搜索关键字，空串表示不过滤。 */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 网格列数（2–5），持久化。 */
    private val _columns = MutableStateFlow(
        prefs.getInt(KEY_COLUMNS, 3).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    )
    val columns: StateFlow<Int> = _columns.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _event = MutableStateFlow<GalleryEvent?>(null)
    val event: StateFlow<GalleryEvent?> = _event.asStateFlow()

    init {
        refresh(initial = true)
    }

    fun refresh() = refresh(initial = false)

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeEvent() {
        _event.value = null
    }

    // region 加载

    private fun refresh(initial: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (initial) {
                    // 首次进入把本应用已有照片补进索引（幂等）
                    galleryImporter.importOwnPhotos()
                }
                loadFirstPage()
            } catch (e: Exception) {
                Logger.e(TAG, "刷新失败", e)
                _message.value = "相册加载失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadFirstPage() = withContext(Dispatchers.IO) {
        val q = _query.value
        val page = if (q.isBlank()) {
            databaseRepository.getPage(PAGE_SIZE, 0)
        } else {
            databaseRepository.searchPhotos(q).first().take(PAGE_SIZE)
        }
        _photos.value = page
        _hasMore.value = q.isBlank() && page.size == PAGE_SIZE
        rebuildItems()
    }

    /** 滚动到底部时继续加载下一页。 */
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return
        if (_query.value.isNotBlank()) return // 搜索结果一次给全，不做分页
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val offset = _photos.value.size
                val page = withContext(Dispatchers.IO) {
                    databaseRepository.getPage(PAGE_SIZE, offset)
                }
                if (page.isEmpty()) {
                    _hasMore.value = false
                } else {
                    _photos.value = _photos.value + page
                    _hasMore.value = page.size == PAGE_SIZE
                    rebuildItems()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "加载更多失败", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private fun rebuildItems() {
        _items.value = GalleryItem.build(_photos.value, _selectedUris.value)
    }

    // endregion

    // region 搜索 / 列数

    fun setQuery(text: String) {
        val q = text.trim()
        if (q == _query.value) return
        _query.value = q
        _selectedUris.value = linkedSetOf()
        viewModelScope.launch {
            _isLoading.value = true
            try {
                loadFirstPage()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setColumns(count: Int) {
        val c = count.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        _columns.value = c
        prefs.edit().putInt(KEY_COLUMNS, c).apply()
    }

    // endregion

    // region 选择

    fun toggleMultiSelectMode() {
        val next = !_isMultiSelectMode.value
        _isMultiSelectMode.value = next
        if (!next) {
            _selectedUris.value = linkedSetOf()
            rebuildItems()
        }
    }

    fun toggleSelection(uri: String) {
        val cur = LinkedHashSet(_selectedUris.value)
        if (!cur.add(uri)) cur.remove(uri)
        _selectedUris.value = cur
        rebuildItems()
    }

    /** 全选（当前已加载的全部照片）。 */
    fun selectAll() {
        _selectedUris.value = LinkedHashSet(_photos.value.map { it.uri })
        if (_selectedUris.value.isNotEmpty()) _isMultiSelectMode.value = true
        rebuildItems()
    }

    /** 反选。 */
    fun invertSelection() {
        val all = _photos.value.map { it.uri }
        val cur = _selectedUris.value
        _selectedUris.value = LinkedHashSet(all.filterNot { cur.contains(it) })
        if (_selectedUris.value.isNotEmpty()) _isMultiSelectMode.value = true
        rebuildItems()
    }

    fun clearSelection() {
        _selectedUris.value = linkedSetOf()
        rebuildItems()
    }

    // endregion

    // region 删除

    /**
     * 删除选中照片。
     *
     * 只有 MediaStore 真删成功（或文件已不存在）才计入成功；失败会如实告知用户，
     * 不再像旧实现那样无条件报告成功。
     */
    fun deleteSelected() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            var deleted = 0
            val failed = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (u in uris) {
                    val ok = try {
                        val rows = context.contentResolver.delete(Uri.parse(u), null, null)
                        // rows == 0 说明文件本来就不在了（可能被系统或其他应用删掉），
                        // 这种情况索引也该清掉，视为成功。
                        rows > 0 || !existsInMediaStore(u)
                    } catch (e: SecurityException) {
                        Logger.e(TAG, "没有删除权限 $u", e)
                        false
                    } catch (e: Exception) {
                        Logger.e(TAG, "删除失败 $u", e)
                        false
                    }
                    if (ok) deleted++ else failed.add(u)
                }
                val removed = uris - failed.toSet()
                databaseRepository.deletePhotos(removed)
            }
            _selectedUris.value = linkedSetOf()
            _isMultiSelectMode.value = false
            _message.value = when {
                failed.isEmpty() -> "已删除 $deleted 张"
                deleted == 0 -> "删除失败（${failed.size} 张），请检查相册权限"
                else -> "已删除 $deleted 张，${failed.size} 张失败"
            }
            refresh()
        }
    }

    private fun existsInMediaStore(uriString: String): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    // endregion

    // region 导航

    fun openPhotoDetail(uri: String) {
        val all = _photos.value.map { it.uri }
        _event.value = GalleryEvent.NavigateToDetail(uri, all)
    }

    fun sendSelectionToCollage() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        _event.value = GalleryEvent.SendToCollage(uris)
        _isMultiSelectMode.value = false
        _selectedUris.value = linkedSetOf()
        rebuildItems()
    }

    // endregion
}

sealed class GalleryEvent {
    data class NavigateToDetail(val uri: String, val allUris: List<String>) : GalleryEvent()
    data class SendToCollage(val uris: List<String>) : GalleryEvent()
}