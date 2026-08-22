package com.watermark.camera.ui.gallery

import android.content.ContentUris
import android.content.Context
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

/**
 * Gallery loads from system MediaStore (Pictures/WatermarkCamera + common images),
 * with Room index as optional supplement. Avoids infinite loading from never-ending Flow.collect.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseRepository: DatabaseRepository
) : ViewModel() {

    companion object {
        private const val TAG = "GalleryVM"
        private const val RELATIVE_DIR = "Pictures/WatermarkCamera"
    }

    private val _photos = MutableStateFlow<List<PhotoEntity>>(emptyList())
    val photos: StateFlow<List<PhotoEntity>> = _photos.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _event = MutableStateFlow<GalleryEvent?>(null)
    val event: StateFlow<GalleryEvent?> = _event.asStateFlow()

    init {
        loadPhotos()
    }

    fun refresh() = loadPhotos()

    private fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val fromStore = withContext(Dispatchers.IO) { queryMediaStore() }
                if (fromStore.isNotEmpty()) {
                    _photos.value = fromStore
                    Logger.i(TAG, "MediaStore loaded ${fromStore.size} photos")
                } else {
                    // One-shot Room read (do not hang on infinite collect)
                    val fromDb = try {
                        databaseRepository.getAllPhotos().first()
                    } catch (e: Exception) {
                        Logger.w(TAG, "Room gallery empty/fail: ${e.message}")
                        emptyList()
                    }
                    _photos.value = fromDb
                    Logger.i(TAG, "Room loaded ${fromDb.size} photos")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load photos", e)
                _photos.value = emptyList()
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

        // Prefer app folder; if none, still show recent images (so gallery is never stuck empty-loading)
        val selection: String?
        val selectionArgs: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("%WatermarkCamera%")
        } else {
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%WatermarkCamera%")
        }

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
                while (c.moveToNext()) {
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
                            id = id,
                            uri = uri,
                            filePath = path,
                            fileName = name,
                            timestamp = ts,
                            width = w,
                            height = h,
                            hasWatermark = name.startsWith("WM_") || name.startsWith("WorkCamera"),
                            hasExifVerification = true
                        )
                    )
                }
            }
        }

        read(selection, selectionArgs)
        if (list.isEmpty()) {
            // Fallback: latest 100 images from system gallery
            read(null, null)
            return list.take(100)
        }
        return list
    }

    fun toggleMultiSelectMode() {
        val newMode = !_isMultiSelectMode.value
        _isMultiSelectMode.value = newMode
        if (!newMode) _selectedUris.value = emptySet()
    }

    fun toggleSelection(uri: String) {
        val current = _selectedUris.value.toMutableSet()
        if (!current.add(uri)) current.remove(uri)
        _selectedUris.value = current
    }

    fun selectAll() {
        _selectedUris.value = _photos.value.map { it.uri }.toSet()
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    fun openPhotoDetail(uri: String) {
        _event.value = GalleryEvent.NavigateToDetail(uri)
    }

    fun confirmSelectionForCollage() {
        val selected = _selectedUris.value.toList()
        if (selected.isEmpty()) return
        _event.value = GalleryEvent.SendToCollage(selected)
    }

    fun consumeEvent() {
        _event.value = null
    }
}

sealed class GalleryEvent : com.watermark.camera.ui.common.UiEvent {
    data class NavigateToDetail(val uri: String) : GalleryEvent()
    data class SendToCollage(val uris: List<String>) : GalleryEvent()
}
