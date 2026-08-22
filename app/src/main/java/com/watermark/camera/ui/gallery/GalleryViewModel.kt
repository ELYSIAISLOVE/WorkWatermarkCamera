package com.watermark.camera.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.domain.repository.DatabaseRepository
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the gallery screen.
 *
 * Manages:
 * - Photo list from Room database (via DatabaseRepository)
 * - Multi-selection mode for collage integration (Step 18)
 * - Photo count and empty state
 * - Navigation to detail screen
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val databaseRepository: DatabaseRepository
) : ViewModel() {

    companion object {
        private const val TAG = "GalleryVM"
    }

    // region State

    /** All photos from database. */
    private val _photos = MutableStateFlow<List<PhotoEntity>>(emptyList())
    val photos: StateFlow<List<PhotoEntity>> = _photos.asStateFlow()

    /** Whether in multi-select mode (for Step 18 collage). */
    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    /** Selected photo URIs in multi-select mode. */
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    /** Loading state. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** One-time events (navigation, toasts). */
    private val _event = MutableStateFlow<GalleryEvent?>(null)
    val event: StateFlow<GalleryEvent?> = _event.asStateFlow()

    // endregion

    // region Lifecycle

    init {
        loadPhotos()
    }

    /**
     * Load all photos from the local database index.
     */
    private fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                databaseRepository.getAllPhotos().collect { list ->
                    _photos.value = list
                    Logger.i(TAG, "Loaded ${list.size} photos")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load photos", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // endregion

    // region User Actions

    /**
     * Toggle multi-select mode.
     * When enabled, user can select multiple photos for collage.
     */
    fun toggleMultiSelectMode() {
        val newMode = !_isMultiSelectMode.value
        _isMultiSelectMode.value = newMode
        if (!newMode) {
            _selectedUris.value = emptySet()
        }
        Logger.d(TAG, "Multi-select mode: $newMode")
    }

    /**
     * Toggle selection of a single photo.
     */
    fun toggleSelection(uri: String) {
        val current = _selectedUris.value.toMutableSet()
        if (current.contains(uri)) {
            current.remove(uri)
        } else {
            current.add(uri)
        }
        _selectedUris.value = current
    }

    /**
     * Select all visible photos.
     */
    fun selectAll() {
        _selectedUris.value = _photos.value.map { it.uri }.toSet()
    }

    /**
     * Clear all selections.
     */
    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    /**
     * Navigate to photo detail screen.
     */
    fun openPhotoDetail(uri: String) {
        _event.value = GalleryEvent.NavigateToDetail(uri)
    }

    /**
     * Confirm multi-selection and return paths for collage.
     * (Called by Step 18 collage integration)
     */
    fun confirmSelectionForCollage() {
        val selected = _selectedUris.value.toList()
        if (selected.isEmpty()) return
        _event.value = GalleryEvent.SendToCollage(selected)
    }

    /**
     * Consume one-time event.
     */
    fun consumeEvent() {
        _event.value = null
    }

    // endregion
}

/**
 * One-time events from GalleryViewModel.
 */
sealed class GalleryEvent {
    /** Navigate to photo detail with URI. */
    data class NavigateToDetail(val uri: String) : GalleryEvent()

    /** Send selected URIs to collage screen (Step 18). */
    data class SendToCollage(val uris: List<String>) : GalleryEvent()
}
