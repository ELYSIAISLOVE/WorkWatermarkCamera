package com.watermark.camera.ui.detail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.watermark.camera.databinding.FragmentPhotoDetailBinding
import com.watermark.camera.domain.repository.PhotoMetadata
import com.watermark.camera.domain.repository.VerificationResult
import com.watermark.camera.ui.common.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Photo detail screen.
 *
 * Features:
 * - Large photo display with zoom gesture support (PhotoView reserved)
 * - EXIF metadata information panel
 * - Photo integrity verification (triple-time + hash check)
 * - Verification result display (Authentic / Tampered / Failed)
 *
 * Arguments:
 * - "photo_uri": String - The photo URI to display.
 */
@AndroidEntryPoint
class PhotoDetailFragment : BaseFragment<FragmentPhotoDetailBinding>() {

    private val viewModel: PhotoDetailViewModel by viewModels()

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentPhotoDetailBinding = FragmentPhotoDetailBinding.inflate(inflater, container, false)

    override fun initViews() {
        // Get photo URI from arguments
        val uriString = arguments?.getString(ARG_PHOTO_URI)
        if (uriString == null) {
            Toast.makeText(requireContext(), "照片URI无效", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = Uri.parse(uriString)
        binding.ivPhoto.setImageURI(uri)
        viewModel.setPhotoUri(uri)

        setupButtons()
    }

    override fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeMetadata() }
                launch { observeVerification() }
                launch { observeLoading() }
                launch { observeError() }
                launch { observeShareEvent() }
            }
        }
    }

    // region Setup

    private fun setupButtons() {
        binding.btnVerify.setOnClickListener {
            viewModel.verifyPhoto()
        }

        binding.btnToggleExif.setOnClickListener {
            binding.layoutExif.isVisible = !binding.layoutExif.isVisible
            binding.btnToggleExif.text =
                if (binding.layoutExif.isVisible) "隐藏EXIF信息" else "显示EXIF信息"
        }

        binding.btnShare.setOnClickListener {
            showShareDialog()
        }

        binding.btnDelete.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    private fun showShareDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("分享照片")
            .setItems(arrayOf("原图", "压缩图 (1080px宽, 80%质量)")) { _, which ->
                viewModel.sharePhoto(useOriginal = which == 0)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除这张照片吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                Toast.makeText(requireContext(), "删除功能待实现", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // endregion

    // region State Observation

    private suspend fun observeMetadata() {
        viewModel.metadata.collect { metadata ->
            metadata?.let { displayExif(it) }
        }
    }

    private suspend fun observeVerification() {
        viewModel.verificationResult.collect { result ->
            result?.let { displayVerificationResult(it) }
        }
    }

    private suspend fun observeLoading() {
        viewModel.isLoadingExif.collect { isLoading ->
            binding.progressBarExif.isVisible = isLoading
        }
        viewModel.isVerifying.collect { isVerifying ->
            binding.progressBarVerify.isVisible = isVerifying
            binding.btnVerify.isEnabled = !isVerifying
        }
    }

    private suspend fun observeError() {
        viewModel.errorMessage.collect { message ->
            message?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    private suspend fun observeShareEvent() {
        viewModel.shareEvent.collect { event ->
            event?.let { (uri, useOriginal) ->
                launchShareIntent(uri, useOriginal)
                viewModel.consumeShareEvent()
            }
        }
    }

    private fun launchShareIntent(uri: Uri, useOriginal: Boolean) {
        val shareUri = if (useOriginal) {
            uri
        } else {
            // TODO: Compress image to 1080px width, JPEG 80%
            // For now, share original as compression requires ImageProcessingPipeline integration
            uri
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享照片"))
    }

    // endregion

    // region Display

    private fun displayExif(metadata: PhotoMetadata) {
        binding.tvExifInfo.text = buildString {
            appendLine("拍摄时间: ${metadata.dateTimeString.ifBlank { "未知" }}")
            appendLine("设备: ${metadata.make} ${metadata.model}")
            appendLine("软件: ${metadata.software}")
            appendLine("尺寸: ${metadata.width} x ${metadata.height}")
            appendLine("数码变焦: ${metadata.digitalZoomRatio}x")
            metadata.iso?.let { appendLine("ISO: $it") }
            metadata.exposureTime?.let { appendLine("曝光时间: $it") }
            metadata.aperture?.let { appendLine("光圈: f/$it") }
            metadata.latitude?.let { lat ->
                metadata.longitude?.let { lon ->
                    appendLine("GPS: $lat, $lon")
                }
            }
            appendLine("描述: ${metadata.imageDescription}")
        }
        binding.btnToggleExif.isEnabled = true
    }

    private fun displayVerificationResult(result: VerificationResult) {
        when (result) {
            is VerificationResult.Authentic -> {
                binding.tvVerifyResult.text = "验真结果: 照片真实可信"
                binding.tvVerifyResult.setTextColor(
                    requireContext().getColor(android.R.color.holo_green_dark)
                )
                binding.tvVerifyDetails.text = "所有时间戳和校验Hash均匹配，照片未被篡改。"
            }
            is VerificationResult.Tampered -> {
                binding.tvVerifyResult.text = "验真结果: 照片可能被篡改"
                binding.tvVerifyResult.setTextColor(
                    requireContext().getColor(android.R.color.holo_red_dark)
                )
                binding.tvVerifyDetails.text = "原因: ${result.reason}\n${result.details}"
            }
            is VerificationResult.Failed -> {
                binding.tvVerifyResult.text = "验真结果: 无法完成验真"
                binding.tvVerifyResult.setTextColor(
                    requireContext().getColor(android.R.color.darker_gray)
                )
                binding.tvVerifyDetails.text = result.reason
            }
        }
        binding.tvVerifyResult.isVisible = true
        binding.tvVerifyDetails.isVisible = true
    }

    // endregion

    companion object {
        private const val ARG_PHOTO_URI = "photo_uri"

        /**
         * Create a new instance with the photo URI.
         */
        fun newInstance(uri: String): PhotoDetailFragment {
            return PhotoDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PHOTO_URI, uri)
                }
            }
        }
    }
}
