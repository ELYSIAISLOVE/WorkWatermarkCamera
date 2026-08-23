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
        val list = if (all.isEmpty()) listOf(startUri) else all.distinct()
        _uriList.value = list
        val i = list.indexOf(startUri).coerceAtLeast(0)
        _index.value = i
        showAt(i)
    }

    fun next() = move(+1)

    fun prev() = move(-1)

    private fun move(delta: Int) {
        val list = _uriList.value
        if (list.isEmpty()) return
        var i = _index.value
        repeat(list.size) {
            i = (i + delta + list.size) % list.size
            if (uriExists(list[i])) {
                _index.value = i
                showAt(i)
                return
            }
        }
        _errorMessage.value = "没有可显示的照片"
        _deleteSuccess.value = true
    }

    private fun uriExists(uriStr: String): Boolean {
        return try {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun showAt(i: Int) {
        val list = _uriList.value
        if (i !in list.indices) return
        val uriStr = list[i]
        if (!uriExists(uriStr)) {
            // Drop missing and jump
            val cleaned = list.filter { uriExists(it) }
            _uriList.value = cleaned
            if (cleaned.isEmpty()) {
                _deleteSuccess.value = true
                return
            }
            val ni = i.coerceAtMost(cleaned.lastIndex)
            _index.value = ni
            showAt(ni)
            return
        }
        val uri = Uri.parse(uriStr)
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
                    // Don't toast hard errors for transient; keep soft
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
            if (!uriExists(uri.toString())) {
                _verificationResult.value = VerificationResult.Failed("文件不存在")
                _isVerifying.value = false
                return@launch
            }
            val result = metadataRepository.verifyIntegrity(uri)
            result.fold(
                onSuccess = { _verificationResult.value = it },
                onFailure = { e ->
                    _verificationResult.value =
                        VerificationResult.Failed(e.message ?: "验真失败")
                    Logger.e(TAG, "verify failed", e)
                }
            )
            _isVerifying.value = false
        }
    }

    fun sharePhoto() {
        val uri = _photoUri.value ?: return
        if (!uriExists(uri.toString())) {
            _errorMessage.value = "文件不存在，无法分享"
            return
        }
        _shareEvent.value = uri
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
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                runCatching { databaseRepository.deletePhoto(uriStr) }
            }
            _isLoading.value = false
            val list = _uriList.value.toMutableList()
            list.removeAll { it == uriStr }
            if (list.isEmpty()) {
                _uriList.value = emptyList()
                _deleteSuccess.value = true
            } else {
                _uriList.value = list
                val i = _index.value.coerceAtMost(list.lastIndex)
                _index.value = i
                _errorMessage.value = "已删除"
                showAt(i)
            }
        }
    }
}
