package com.watermark.camera.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermark.camera.domain.repository.MetadataRepository
import com.watermark.camera.domain.repository.PhotoMetadata
import com.watermark.camera.domain.repository.VerificationResult
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the photo detail screen.
 *
 * Manages:
 * - EXIF metadata display
 * - Photo verification (integrity check)
 * - Photo deletion
 * - Loading states
 */
@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val metadataRepository: MetadataRepository
) : ViewModel() {

    companion object {
        private const val TAG = "PhotoDetailVM"
    }

    // region State

    /** Current photo URI. */
    private val _photoUri = MutableStateFlow<Uri?>(null)

    /** EXIF metadata. */
    private val _metadata = MutableStateFlow<PhotoMetadata?>(null)
    val metadata: StateFlow<PhotoMetadata?> = _metadata.asStateFlow()

    /** Verification result. */
    private val _verificationResult = MutableStateFlow<VerificationResult?>(null)
    val verificationResult: StateFlow<VerificationResult?> = _verificationResult.asStateFlow()

    /** Loading state for EXIF reading. */
    private val _isLoadingExif = MutableStateFlow(false)
    val isLoadingExif: StateFlow<Boolean> = _isLoadingExif.asStateFlow()

    /** Loading state for verification. */
    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying.asStateFlow()

    /** Share event (uri + useOriginal). */
    private val _shareEvent = MutableStateFlow<Pair<Uri, Boolean>?>(null)
    val shareEvent: StateFlow<Pair<Uri, Boolean>?> = _shareEvent.asStateFlow()

    /** Delete success event. */
    private val _deleteSuccess = MutableStateFlow(false)
    val deleteSuccess: StateFlow<Boolean> = _deleteSuccess.asStateFlow()

    /** Error message. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // endregion

    // region Actions

    /**
     * Set the photo URI and load its EXIF data.
     */
    fun setPhotoUri(uri: Uri) {
        _photoUri.value = uri
        loadExif(uri)
    }

    /**
     * Read EXIF metadata from the photo.
     */
    private fun loadExif(uri: Uri) {
        viewModelScope.launch {
            _isLoadingExif.value = true
            _errorMessage.value = null

            val result = metadataRepository.readExif(uri)
            result.fold(
                onSuccess = { data ->
                    _metadata.value = data
                    Logger.i(TAG, "EXIF loaded: ${data.width}x${data.height}")
                },
                onFailure = { error ->
                    _errorMessage.value = "读取EXIF失败: ${error.message}"
                    Logger.e(TAG, "Failed to read EXIF", error)
                }
            )
            _isLoadingExif.value = false
        }
    }

    /**
     * Verify photo integrity (triple-time + hash check).
     */
    fun verifyPhoto() {
        val uri = _photoUri.value ?: return

        viewModelScope.launch {
            _isVerifying.value = true
            _verificationResult.value = null
            _errorMessage.value = null

            val result = metadataRepository.verifyIntegrity(uri)
            result.fold(
                onSuccess = { verification ->
                    _verificationResult.value = verification
                    Logger.i(TAG, "Verification result: $verification")
                },
                onFailure = { error ->
                    _errorMessage.value = "验真失败: ${error.message}"
                    Logger.e(TAG, "Verification failed", error)
                }
            )
            _isVerifying.value = false
        }
    }

    /**
     * Trigger share event.
     * @param useOriginal true for original, false for compressed.
     */
    fun sharePhoto(useOriginal: Boolean) {
        val uri = _photoUri.value ?: return
        _shareEvent.value = Pair(uri, useOriginal)
    }

    /**
     * Consume share event after handling.
     */
    fun consumeShareEvent() {
        _shareEvent.value = null
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }

    // endregion
}
