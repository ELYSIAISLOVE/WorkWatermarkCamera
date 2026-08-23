package com.watermark.camera.ui.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseRepository: DatabaseRepository
) : ViewModel() {

    companion object {
        private const val TAG = "GalleryVM"
        private const val MAX_PHOTOS = 200
    }

    private val _photos = MutableStateFlow<List<PhotoEntity>>(emptyList())
    val photos: StateFlow<List<PhotoEntity>> = _photos.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _event = MutableStateFlow<GalleryEvent?>(null)
    val event: StateFlow<GalleryEvent?> = _event.asStateFlow()

    init {
        loadPhotos()
    }

    fun refresh() = loadPhotos()

    fun consumeMessage() {
        _message.value = null
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val fromStore = withContext(Dispatchers.IO) { queryMediaStore().take(MAX_PHOTOS) }
                if (fromStore.isNotEmpty()) {
                    _photos.value = fromStore
                    Logger.i(TAG, "MediaStore loaded ${fromStore.size}")
                } else {
                    val fromRoom = withContext(Dispatchers.IO) {
                        try {
                            databaseRepository.getAllPhotos().first().take(MAX_PHOTOS)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    _photos.value = fromRoom
                    Logger.i(TAG, "Room loaded ${fromRoom.size}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "loadPhotos failed", e)
                _photos.value = emptyList()
                _message.value = "相册加载失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun queryMediaStore(): List<PhotoEntity> {
        val list = mutableListOf<PhotoEntity>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        fun read(sel: String?, args: Array<String>?) {
            context.contentResolver.query(collection, projection, sel, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val wCol = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val hCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA)
                while (c.moveToNext() && list.size < MAX_PHOTOS) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val name = c.getString(nameCol) ?: "IMG_$id.jpg"
                    val taken = if (takenCol >= 0) c.getLong(takenCol) else 0L
                    val added = c.getLong(addedCol) * 1000L
                    val ts = if (taken > 0) taken else added
                    val w = if (wCol >= 0) c.getInt(wCol) else 0
                    val h = if (hCol >= 0) c.getInt(hCol) else 0
                    val path = if (dataCol >= 0) c.getString(dataCol) else null
                    list.add(
                        PhotoEntity(
                            uri = uri,
                            filePath = path ?: uri,
                            fileName = name,
                            timestamp = ts,
                            width = w,
                            height = h
                        )
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            read("${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("%WatermarkCamera%"))
        } else {
            read("${MediaStore.Images.Media.DATA} LIKE ?", arrayOf("%WatermarkCamera%"))
        }
        if (list.isEmpty()) {
            read(null, null)
        }
        return list
    }

    fun toggleMultiSelectMode() {
        val next = !_isMultiSelectMode.value
        _isMultiSelectMode.value = next
        if (!next) _selectedUris.value = emptySet()
    }

    fun toggleSelection(uri: String) {
        val cur = _selectedUris.value.toMutableSet()
        if (!cur.add(uri)) cur.remove(uri)
        _selectedUris.value = cur
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    fun openPhotoDetail(uri: String) {
        val all = _photos.value.map { it.uri }
        _event.value = GalleryEvent.NavigateToDetail(uri, all)
    }

    fun sendSelectionToCollage() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        _event.value = GalleryEvent.SendToCollage(uris)
        _isMultiSelectMode.value = false
        _selectedUris.value = emptySet()
    }

    fun deleteSelected() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            var ok = 0
            withContext(Dispatchers.IO) {
                for (u in uris) {
                    try {
                        val rows = context.contentResolver.delete(Uri.parse(u), null, null)
                        databaseRepository.deletePhoto(u)
                        if (rows > 0 || true) ok++
                    } catch (e: Exception) {
                        Logger.e(TAG, "delete failed $u", e)
                    }
                }
            }
            _selectedUris.value = emptySet()
            _isMultiSelectMode.value = false
            _message.value = "已删除 $ok 张"
            loadPhotos()
        }
    }

    fun consumeEvent() {
        _event.value = null
    }
}

sealed class GalleryEvent {
    data class NavigateToDetail(val uri: String, val allUris: List<String>) : GalleryEvent()
    data class SendToCollage(val uris: List<String>) : GalleryEvent()
}
