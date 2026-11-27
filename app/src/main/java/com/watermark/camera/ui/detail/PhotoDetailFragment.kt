package com.watermark.camera.ui.detail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.watermark.camera.databinding.FragmentPhotoDetailBinding
import com.watermark.camera.R
import com.watermark.camera.domain.repository.VerificationResult
import com.watermark.camera.ui.common.BaseFragment
import com.watermark.camera.ui.common.Feedback
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
            Feedback.light(binding.root, getString(R.string.feedback_invalid_photo))
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
                        uri?.let {
                            binding.ivPhoto.setImageURI(it)
                            // 切图后复位缩放，避免上一张的缩放状态残留
                            photoScale = 1f
                            applyPhotoTransform()
                        }
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
                        showVerificationCard(v)
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
                            Feedback.actionable(binding.root, it)
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
                            Feedback.light(binding.root, getString(R.string.feedback_photo_deleted))
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
        // 捏合缩放 + 双击放大（规范 §6）；放大后拖动平移
        val scaleDetector = android.view.ScaleGestureDetector(
            requireContext(),
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    photoScale = (photoScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                    applyPhotoTransform()
                    return true
                }
            }
        )
        val detector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    photoScale = if (photoScale > 1.05f) 1f else 2.5f
                    applyPhotoTransform()
                    return true
                }
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    // 放大状态下不切图，交给拖动平移处理
                    if (photoScale > 1.05f) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) && abs(dx) > 80 && abs(velocityX) > 200) {
                        if (dx < 0) viewModel.next() else viewModel.prev()
                        return true
                    }
                    return false
                }
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (photoScale <= 1.05f || e1 == null) return false
                    photoOffsetX -= distanceX
                    photoOffsetY -= distanceY
                    applyPhotoTransform()
                    return true
                }
            }
        )
        binding.ivPhoto.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            detector.onTouchEvent(event)
            true
        }
    }

    private var photoScale = 1f
    private var photoOffsetX = 0f
    private var photoOffsetY = 0f

    private fun applyPhotoTransform() {
        if (photoScale <= 1.05f) {
            photoOffsetX = 0f
            photoOffsetY = 0f
        }
        binding.ivPhoto.scaleX = photoScale
        binding.ivPhoto.scaleY = photoScale
        binding.ivPhoto.translationX = photoOffsetX
        binding.ivPhoto.translationY = photoOffsetY
    }

    /** 验真结果用大号状态卡 + 白话解释（规范 §6）。 */
    private fun showVerificationCard(v: VerificationResult?) {
        val card = binding.tvVerificationCard
        when (v) {
            is VerificationResult.Authentic -> {
                card.visibility = View.VISIBLE
                card.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.success))
                card.text = "验真通过\n时间、位置与校验摘要一致，未被改动。"
            }
            is VerificationResult.Tampered -> {
                card.visibility = View.VISIBLE
                card.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.warning))
                card.text = "可能被篡改\n校验摘要与照片内记录不一致，请谨慎采信。"
            }
            is VerificationResult.Failed -> {
                card.visibility = View.VISIBLE
                card.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.error))
                card.text = "验真失败\n${v.reason}"
            }
            null -> card.visibility = View.GONE
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
            // 时间来源（R2）：让查看者能判断这个时间的可信度
            timeSourceLabel(meta.timeSource, meta.clockOffsetMs)?.let {
                sb.appendLine("时间来源: $it")
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

    /**
     * Human-readable time provenance.
     *
     * Legacy photos (written before the v1 UserComment) have no recorded source, so null is
     * returned and the line is omitted instead of claiming "设备时间".
     */
    private fun timeSourceLabel(timeSource: String, clockOffsetMs: Long): String? = when (timeSource) {
        com.watermark.camera.domain.model.TimeSource.NETWORK.name -> "可信时间（联网授时）"
        com.watermark.camera.domain.model.TimeSource.OFFLINE_ANCHORED.name ->
            "离线推算（未联网，按上次校准推进）"
        com.watermark.camera.domain.model.TimeSource.DEVICE.name -> {
            val drift = if (clockOffsetMs != 0L) {
                "，校正 ${clockOffsetMs / 1000} 秒"
            } else ""
            "设备时间（未联网校准$drift）"
        }
        else -> null
    }

    private fun share(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.detail_share_title)
            .setItems(
                arrayOf(
                    getString(R.string.detail_share_original),
                    getString(R.string.detail_share_with_report)
                )
            ) { _, which ->
                if (which == 0) shareImage(uri, null) else shareImage(uri, buildReport())
            }
            .show()
    }

    /** 「附带验真说明」分享时附加的可读证据摘要。 */
    private fun buildReport(): String {
        val meta = viewModel.metadata.value
        val verify = viewModel.verificationResult.value
        val body = buildInfo(meta, verify)
        return "工作留证说明\n$body\n\n（由水印相机生成，照片本身未做任何修改）"
    }

    private fun shareImage(uri: Uri, extraText: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            extraText?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享照片"))
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
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
