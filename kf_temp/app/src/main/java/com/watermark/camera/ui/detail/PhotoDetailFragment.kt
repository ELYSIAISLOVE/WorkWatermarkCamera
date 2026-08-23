package com.watermark.camera.ui.detail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.watermark.camera.domain.repository.VerificationResult
import com.watermark.camera.ui.common.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class PhotoDetailFragment : BaseFragment<FragmentPhotoDetailBinding>() {

    private val viewModel: PhotoDetailViewModel by viewModels()
    private var infoExpanded = true

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentPhotoDetailBinding =
        FragmentPhotoDetailBinding.inflate(inflater, container, false)

    override fun initViews() {
        val start = arguments?.getString(ARG_PHOTO_URI)
        if (start.isNullOrBlank()) {
            Toast.makeText(requireContext(), "无效照片", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }
        val all = arguments?.getStringArrayList(ARG_ALL_URIS) ?: arrayListOf(start)
        viewModel.setPhotoList(start, all)
        setupGestures()
        setupButtons()
    }

    override fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.photoUri.collect { uri ->
                        uri?.let { binding.ivPhoto.setImageURI(it) }
                    }
                }
                launch {
                    viewModel.index.collect { i ->
                        val total = viewModel.uriList.value.size.coerceAtLeast(1)
                        binding.tvIndex.text = "${i + 1} / $total"
                    }
                }
                launch {
                    viewModel.metadata.collect { meta ->
                        if (meta != null && infoExpanded) {
                            binding.tvInfo.text = buildInfo(meta, viewModel.verificationResult.value)
                        }
                    }
                }
                launch {
                    viewModel.verificationResult.collect { v ->
                        val meta = viewModel.metadata.value
                        if (infoExpanded) {
                            binding.tvInfo.text = buildInfo(meta, v)
                        }
                    }
                }
                launch {
                    viewModel.isLoading.collect {
                        binding.progressBar.isVisible = it
                    }
                }
                launch {
                    viewModel.isVerifying.collect {
                        if (it) binding.progressBar.isVisible = true
                    }
                }
                launch {
                    viewModel.errorMessage.collect { msg ->
                        msg?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                            viewModel.clearError()
                        }
                    }
                }
                launch {
                    viewModel.shareEvent.collect { uri ->
                        uri?.let {
                            share(it)
                            viewModel.consumeShareEvent()
                        }
                    }
                }
                launch {
                    viewModel.deleteSuccess.collect { done ->
                        if (done) {
                            Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                            parentFragmentManager.popBackStack()
                        }
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnInfo.setOnClickListener {
            infoExpanded = !infoExpanded
            binding.tvInfo.isVisible = infoExpanded
            val meta = viewModel.metadata.value
            if (infoExpanded) {
                binding.tvInfo.text = buildInfo(meta, viewModel.verificationResult.value)
            }
        }
        binding.btnVerify.setOnClickListener { viewModel.verifyPhoto() }
        binding.btnShare.setOnClickListener { viewModel.sharePhoto() }
        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("删除照片")
                .setMessage("确定删除当前照片？")
                .setPositiveButton("删除") { _, _ -> viewModel.deleteCurrent() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupGestures() {
        val detector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) && abs(dx) > 80 && abs(velocityX) > 200) {
                        if (dx < 0) viewModel.next() else viewModel.prev()
                        return true
                    }
                    return false
                }
            }
        )
        binding.ivPhoto.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun buildInfo(
        meta: com.watermark.camera.domain.repository.PhotoMetadata?,
        verify: VerificationResult?
    ): String {
        val sb = StringBuilder()
        if (meta != null) {
            sb.appendLine("尺寸: ${meta.width}×${meta.height}")
            if (!meta.dateTimeString.isNullOrBlank()) {
                sb.appendLine("时间: ${meta.dateTimeString}")
            }
            if (meta.latitude != null && meta.longitude != null) {
                sb.appendLine("位置: ${meta.latitude}, ${meta.longitude}")
            }
            if (meta.make.isNotBlank() || meta.model.isNotBlank()) {
                sb.appendLine("设备: ${meta.make} ${meta.model}".trim())
            }
        } else {
            sb.appendLine("左右滑动切换 · 点信息查看详情")
        }
        when (verify) {
            is VerificationResult.Authentic -> sb.appendLine("验真: 通过 ✓")
            is VerificationResult.Tampered -> sb.appendLine("验真: 可能被篡改")
            is VerificationResult.Failed -> sb.appendLine("验真: 失败 ${verify.reason}")
            null -> {}
        }
        return sb.toString().trim()
    }

    private fun share(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享照片"))
    }

    companion object {
        private const val ARG_PHOTO_URI = "photo_uri"
        private const val ARG_ALL_URIS = "all_uris"

        fun newInstance(uri: String, allUris: ArrayList<String> = arrayListOf(uri)): PhotoDetailFragment {
            return PhotoDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PHOTO_URI, uri)
                    putStringArrayList(ARG_ALL_URIS, allUris)
                }
            }
        }
    }
}
