package com.watermark.camera.ui.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermark.camera.domain.repository.DatabaseRepository
import com.watermark.camera.domain.repository.MetadataRepository
import com.watermark.camera.domain.repository.PhotoMetadata
import com.watermark.camera.domain.repository.VerificationResult
import com.watermark.camera.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataRepository: MetadataRepository,
    private val databaseRepository: DatabaseRepository
) : ViewModel() {

    companion object {
        private const val TAG = "PhotoDetailVM"
    }

    private val _photoUri = MutableStateFlow<Uri?>(null)
    val photoUri: StateFlow<Uri?> = _photoUri.asStateFlow()

    private val _uriList = MutableStateFlow<List<String>>(emptyList())
    val uriList: StateFlow<List<String>> = _uriList.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _metadata = MutableStateFlow<PhotoMetadata?>(null)
    val metadata: StateFlow<PhotoMetadata?> = _metadata.asStateFlow()

    private val _verificationResult = MutableStateFlow<VerificationResult?>(null)
    val verificationResult: StateFlow<VerificationResult?> = _verificationResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying.asStateFlow()

    private val _shareEvent = MutableStateFlow<Uri?>(null)
    val shareEvent: StateFlow<Uri?> = _shareEvent.asStateFlow()

    private val _deleteSuccess = MutableStateFlow(false)
    val deleteSuccess: StateFlow<Boolean> = _deleteSuccess.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setPhotoList(startUri: String, all: List<String>) {
        val list = if (all.isEmpty()) listOf(startUri) else all
        _uriList.value = list
        val i = list.indexOf(startUri).coerceAtLeast(0)
        _index.value = i
        showAt(i)
    }

    fun next() {
        val list = _uriList.value
        if (list.isEmpty()) return
        val i = (_index.value + 1) % list.size
        _index.value = i
        showAt(i)
    }

    fun prev() {
        val list = _uriList.value
        if (list.isEmpty()) return
        val i = if (_index.value <= 0) list.lastIndex else _index.value - 1
        _index.value = i
        showAt(i)
    }

    private fun showAt(i: Int) {
        val list = _uriList.value
        if (i !in list.indices) return
        val uri = Uri.parse(list[i])
        _photoUri.value = uri
        _verificationResult.value = null
        loadExif(uri)
    }

    private fun loadExif(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = metadataRepository.readExif(uri)
            result.fold(
                onSuccess = { _metadata.value = it },
                onFailure = { e ->
                    _metadata.value = null
                    _errorMessage.value = "读取信息失败: ${e.message}"
                    Logger.e(TAG, "EXIF failed", e)
                }
            )
            _isLoading.value = false
        }
    }

    fun verifyPhoto() {
        val uri = _photoUri.value ?: return
        viewModelScope.launch {
            _isVerifying.value = true
            _verificationResult.value = null
            val result = metadataRepository.verifyIntegrity(uri)
            result.fold(
                onSuccess = { _verificationResult.value = it },
                onFailure = { e ->
                    _errorMessage.value = "验真失败: ${e.message}"
                    Logger.e(TAG, "verify failed", e)
                }
            )
            _isVerifying.value = false
        }
    }

    fun sharePhoto() {
        _shareEvent.value = _photoUri.value
    }

    fun consumeShareEvent() {
        _shareEvent.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun deleteCurrent() {
        val uri = _photoUri.value ?: return
        val uriStr = uri.toString()
        viewModelScope.launch {
            _isLoading.value = true
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.delete(uri, null, null)
                    databaseRepository.deletePhoto(uriStr)
                    true
                } catch (e: Exception) {
                    Logger.e(TAG, "delete failed", e)
                    false
                }
            }
            _isLoading.value = false
            if (ok) {
                val list = _uriList.value.toMutableList()
                list.remove(uriStr)
                if (list.isEmpty()) {
                    _deleteSuccess.value = true
                } else {
                    _uriList.value = list
                    val i = _index.value.coerceAtMost(list.lastIndex)
                    _index.value = i
                    showAt(i)
                    _errorMessage.value = "已删除"
                }
            } else {
                _errorMessage.value = "删除失败"
            }
        }
    }
}
