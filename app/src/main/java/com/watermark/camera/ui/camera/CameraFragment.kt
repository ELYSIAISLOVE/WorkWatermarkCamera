package com.watermark.camera.ui.camera

import com.watermark.camera.util.ViewAnim

import android.Manifest
import android.os.Build
import android.os.VibrationEffect
import androidx.appcompat.app.AppCompatDelegate
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.watermark.camera.databinding.FragmentCameraBinding
import com.watermark.camera.R
import com.watermark.camera.ui.watermark.WatermarkPickerSheet
import com.watermark.camera.ui.common.BaseFragment
import com.watermark.camera.ui.common.Feedback
import com.watermark.camera.ui.permission.PermissionIntroState
import dagger.hilt.android.AndroidEntryPoint

/**
 * Camera preview fragment.
 *
 * Displays camera preview with watermark overlay and capture controls.
 * Handles CameraX preview binding, user interactions (zoom, focus, capture),
 * and volume key capture.
 */
@AndroidEntryPoint
class CameraFragment : BaseFragment<FragmentCameraBinding>() {

    private val viewModel: CameraViewModel by viewModels()

    /** 用于拍照后取最近一张照片的缩略图做「飞入」动效（规范 §4）。 */
    @javax.inject.Inject
    lateinit var databaseRepository: com.watermark.camera.domain.repository.DatabaseRepository

    /**
     * Camera permission launcher.
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraPreview()
            applyRoundedPreviewClip()
            viewModel.onReturnToCamera()
            runCatching { binding.previewRoundedMask.visibility = android.view.View.VISIBLE }
            binding.root.post {
                binding.watermarkOverlay.locationText =
                    viewModel.locationDisplay.value.ifBlank { "定位中…" }
                binding.watermarkOverlay.watermarkConfig =
                    viewModel.watermarkConfigDisplay.value.copy(showLocation = true)
                binding.watermarkOverlay.invalidate()
            }
            binding.root.postDelayed({
                binding.watermarkOverlay.locationText =
                    viewModel.locationDisplay.value.ifBlank { "定位中…" }
                binding.watermarkOverlay.watermarkConfig =
                    viewModel.watermarkConfigDisplay.value.copy(showLocation = true)
                binding.watermarkOverlay.invalidate()
            }, 200L)
        } else {
            // 拒绝后不再反复弹对话框：常驻提示条与「去设置」由 MainActivity 统一负责
            view?.let { Feedback.needsPermission(it, getString(R.string.permission_banner_camera)) }
        }
    }

    /**
     * Scale gesture detector for pinch-to-zoom.
     */
    private var scaleGestureDetector: ScaleGestureDetector? = null

    /**
     * Current zoom ratio for pinch gesture tracking.
     */
    private var currentZoomRatio = 1.0f
    /** 双指捏合进行中：用于让缩放优先于单击对焦（规范 §4）。 */
    private var scaleInProgress = false
    /** 用户可变焦上限（双指/拖动均限制） */
    private val MAX_USER_ZOOM = 10.0f

    /**
     * Flag to prevent duplicate LifecycleObserver registration.
     */
    private var isObserverAdded = false

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCameraBinding {
        return FragmentCameraBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideRedundantTopControls()
        setupScaleGestureDetector()
        requestCameraPermissionIfNeeded()
    }

    override fun initViews() {
        // Capture button click
        binding.btnCapture.setOnClickListener {
            // 成片使用与预览一致的配置（含自定义标题）
            viewModel.applyLiveOverlayConfig(binding.watermarkOverlay.watermarkConfig)
            clearQueueFailure()
            viewModel.capturePhoto()
        }

        // 仅补光灯开关（开/关），不切换闪光灯拍照模式
        binding.btnFlash.setOnClickListener {
            viewModel.toggleTorch()
        }

        // Gallery button click
        binding.btnGallery.setOnClickListener {
            openGallery()
        }

        // Watermark: short click toggles template strip; long click opens full settings
        binding.btnWatermark.setOnClickListener {
            openWatermarkPickerSheet()
        }
        binding.btnWatermark.setOnLongClickListener {
            // Old long-press menu removed; use picker sheet only
            openWatermarkPickerSheet()
            true
        }
        // Legacy horizontal strip kept in layout but hidden; sheet is primary picker
        runCatching { binding.templateStrip.visibility = View.GONE }
        setupSaveQueueIndicator()

        // Collage button click
        binding.btnCollageBottom.setOnClickListener {
            openCollage()
        }

        // Settings button click：进入合并后的单一设置页（规范 §3，取消侧栏）
        binding.btnSettings.setOnClickListener {
            runCatching { findNavController().navigate(R.id.action_camera_to_settings) }
        }

        // 顶栏：拼图（自底栏上移）
        binding.btnCollage.setOnClickListener {
            openCollage()
        }
        binding.btnSettingsTop.setOnClickListener {
            binding.btnSettings.performClick()
        }
        binding.btnVerify.setOnClickListener {
            Feedback.light(binding.root, getString(R.string.feedback_verify_in_gallery))
        }

        // 字体放大到 1.3 倍以上时隐藏文字标签、保留图标（规范 §4）
        if (resources.configuration.fontScale >= 1.3f) {
            runCatching { binding.tvGalleryLabel.visibility = View.GONE }
        }

        // Android 12 以下没有背景模糊，毛玻璃降级为半透明纯色（规范 §8）
        if (!com.watermark.camera.util.UiCapabilities.supportsBlur) {
            runCatching { binding.btnWatermark.setBackgroundResource(R.drawable.bg_top_bar_solid) }
        }

        // iOS-like press scale on main buttons
        runCatching {
            ViewAnim.attachPressScale(
                binding.btnCapture,
                binding.btnFlash,
                binding.btnGallery,
                binding.btnWatermark,
                binding.btnCollageBottom,
                binding.btnSettings,
                binding.btnCollage,
                binding.btnSettingsTop,
                binding.btnVerify,
                binding.btnTurnOnFlash
            )
        }


        // 闪光灯模式已移除：仅保留补光灯（torch）开关
        runCatching {
            binding.btnTurnOnFlash.visibility = View.GONE
            binding.lowLightWarning.visibility = View.GONE
        }

        // Zoom ratio click to reset
        
        setupZoomChips()
        applyLetterboxTheme()

        // tvZoomRatio click 在 setupZoomChips 中绑定（弹出楔形变焦条）

        // Aspect ratio click to cycle
        binding.tvAspectRatio.setOnClickListener {
            // Fixed 4:3
            viewModel.cycleAspectRatio()
        }
        binding.watermarkOverlay.onDragPosition = { _, _, _ ->
            // Keep VM display in sync during drag so shutter freeze is accurate
            viewModel.applyLiveOverlayConfig(binding.watermarkOverlay.watermarkConfig)
        }
        binding.watermarkOverlay.onPositionChanged = { _ ->
            val c = binding.watermarkOverlay.watermarkConfig
            val nx = c.customX ?: 0f
            val ny = c.customY ?: 0f
            viewModel.updateWatermarkDrag(nx, ny, c.position)
        }

        // EV value click to show/hide slider
        binding.tvEvValue.setOnClickListener {
            binding.sliderExposure.visibility =
                if (binding.sliderExposure.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Exposure compensation slider
        binding.sliderExposure.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setExposureCompensation(value)
            }
        }

        // Tap to focus on preview
        binding.previewView.setOnTouchListener { v, event ->
            // Handle scale gesture first
            val scaleHandled = scaleGestureDetector?.onTouchEvent(event) ?: false

            // Handle tap to focus
            if (event.pointerCount == 1 &&
                event.action == android.view.MotionEvent.ACTION_UP &&
                !scaleHandled && !scaleInProgress
            ) {
                val previewView = binding.previewView
                val x = event.x / previewView.width
                val y = event.y / previewView.height
                viewModel.tapToFocus(x, y)
                showFocusFrame(event.x, event.y)
                // 对焦框旁显示曝光滑杆（规范 §4）
                runCatching { binding.sliderExposure.visibility = View.VISIBLE }
            }

            v.performClick()
            true
        }
    }

    override fun observeData() {
        collectStateFlow(viewModel.uiState) { state ->
            renderState(state)
        }
        collectEventFlow(viewModel.uiEvent) { event ->
            handleEvent(event)
        }
        // 时间来源徽标（R2）：可信时间 / 离线推算 / 设备时间
        collectStateFlow(viewModel.timeBadge) { badge ->
            binding.watermarkOverlay.timeBadge = badge
        }
    }

    /**
     * Render UI based on camera state.
     */
    private fun renderState(state: CameraState) {
        when (state) {
            is CameraState.Idle -> {
                binding.btnCapture.isEnabled = false
                binding.tvZoomRatio.visibility = View.GONE
                binding.btnFlash.visibility = View.GONE
            }
            is CameraState.Previewing -> {
                binding.btnCapture.isEnabled = true
                binding.tvZoomRatio.visibility = View.VISIBLE
                binding.tvZoomRatio.text = String.format("%.1fx", state.zoomRatio)
                binding.tvAspectRatio.text = "4:3"
                binding.tvAspectRatio.visibility = View.GONE
                binding.tvEvValue.visibility = View.GONE
                binding.tvEvValue.text = String.format("EV %+.1f", state.evValue)
                currentZoomRatio = state.zoomRatio

                // Fill-light button always visible in preview
                binding.btnFlash.visibility = View.VISIBLE
                // Tint: on = amber-ish, off = white (if drawable supports)
                runCatching {
                    binding.btnFlash.alpha = if (viewModel.torchOn.value) 1f else 0.55f
                }

                // 不再根据闪光灯自动模式提示；补光仅由 btnFlash 控制
                binding.lowLightWarning.visibility = View.GONE
                runCatching { binding.btnTurnOnFlash.visibility = View.GONE }

                // Focus lock: no-op (labels hidden)
            }
            is CameraState.Capturing -> {
                binding.btnCapture.isEnabled = false
            }
            is CameraState.Processing -> {
                binding.btnCapture.isEnabled = false
            }
            is CameraState.Saving -> {
                binding.btnCapture.isEnabled = false
            }
            is CameraState.Error -> {
                binding.btnCapture.isEnabled = state.recoverable
                Feedback.actionable(binding.root, state.message)
            }
        }
    }

    /**
     * Handle one-time UI events.
     */


    private suspend fun observeWatermarkConfigDisplay() {
        viewModel.watermarkConfigDisplay.collect { config ->
            binding.watermarkOverlay.watermarkConfig = config.copy(showLocation = true)
            // 底栏右侧显示当前水印模板名（规范 §4）
            runCatching {
                binding.btnWatermark.text = config.customTitle.ifBlank {
                    config.template.displayName
                }
            }
        }
    }

    private suspend fun observeLocationDisplay() {
        viewModel.locationDisplay.collect { text ->
            binding.watermarkOverlay.locationText = text.ifBlank { "定位中…" }
        }
    }

    private fun handleEvent(event: CameraEvent) {
        when (event) {
            is CameraEvent.ShowToast -> {
                // 审计发现的问题：这里曾把全部提示静默丢弃，导致保存/处理/拍照失败用户完全无感。
                // 成功类用 Toast（轻量、不挡取景），失败类用 Snackbar（醒目、可停留查看）。
                if (event.isError) {
                    Snackbar
                        .make(binding.root, event.message, Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.btnCapture.takeIf { it.visibility == View.VISIBLE })
                        .show()
                } else {
                    Feedback.light(binding.root, event.message)
                }
            }
            is CameraEvent.ShutterFeedback -> {
                playShutterAnimation()
                playThumbnailFlyIn()
            }
            is CameraEvent.RequestCameraPermission -> {
                requestCameraPermissionIfNeeded()
            }
            is CameraEvent.CaptureHaptic -> {
                performCaptureHaptic()
            }
            is CameraEvent.GalleryFlash -> {
                flashGalleryButton()
            }
            is CameraEvent.RequestRebind -> {
                rebindCameraPreview()
            }
            else -> {
                // Handle other events in later steps
            }
        }
    }

    /**
     * Setup pinch-to-zoom gesture detector.
     */
    private fun setupScaleGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    // 双指捏合优先于单击对焦等其它手势（规范 §4）
                    scaleInProgress = true
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    scaleInProgress = false
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scale = detector.scaleFactor
                    val minZ = viewModel.getMinZoomRatio().coerceAtLeast(1f)
                    val maxZ = minOf(viewModel.getMaxZoomRatio().coerceAtLeast(minZ + 0.1f), MAX_USER_ZOOM)
                    val newZoom = (currentZoomRatio * scale).coerceIn(minZ, maxZ)
                    currentZoomRatio = newZoom
                    viewModel.setZoomRatio(newZoom)
                    runCatching {
                        binding.tvZoomRatio.visibility = View.VISIBLE
                        binding.tvZoomRatio.text = String.format("%.1fx", newZoom)
                    }
                    return true
                }
            }
        )
    }

    /**
     * Show focus frame animation at the tapped position.
     */
    private fun showFocusFrame(x: Float, y: Float) {
        binding.focusFrame.apply {
            visibility = View.VISIBLE
            translationX = x - width / 2
            translationY = y - height / 2

            val fadeIn = AlphaAnimation(0.0f, 1.0f).apply {
                duration = 150
                fillAfter = true
            }
            val fadeOut = AlphaAnimation(1.0f, 0.0f).apply {
                duration = 300
                startOffset = 1500
                fillAfter = true
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(animation: Animation?) {}
                    override fun onAnimationEnd(animation: Animation?) {
                        visibility = View.INVISIBLE
                    }
                    override fun onAnimationRepeat(animation: Animation?) {}
                })
            }

            startAnimation(fadeIn)
            postDelayed({ startAnimation(fadeOut) }, 1500)
        }
    }

    /**
     * Play shutter flash animation.
     */
    private fun playShutterAnimation() {
        binding.shutterFlash.apply {
            visibility = View.VISIBLE
            alpha = 0.8f
            animate()
                .alpha(0.0f)
                .setDuration(80) // 规范 §4：快门瞬间闪白约 80ms
                .withEndAction {
                    visibility = View.INVISIBLE
                }
                .start()
        }
    }

    /**
     * Check and request camera permission.
     */
    private fun requestCameraPermissionIfNeeded() {
        // 首启说明页还没结束时，授权流程由 MainActivity 串行走完，这里不抢弹窗（规范 §3）
        if (!PermissionIntroState.isDone(requireContext())) return
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCameraPreview()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Show rationale dialog in production
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * Start camera preview by binding PreviewView to ViewModel.
     */
    private fun startCameraPreview() {
        viewModel.startPreview(
            lifecycleOwner = viewLifecycleOwner,
            previewSurface = binding.previewView.surfaceProvider
        )
    }

    /**
     * 缩略图「飞入」相册入口（规范 §4）：从取景区中心飞向相册按钮后淡出。
     * 缩略图取最近一张照片，取不到就跳过动效，不影响拍照主流程。
     */
    private fun playThumbnailFlyIn() {
        // 低内存机型关闭该动效，避免额外解码位图（规范 §8）
        if (com.watermark.camera.util.UiCapabilities.isLowRamDevice) return
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = runCatching {
                databaseRepository.getRecentPhotos(1).firstOrNull()?.uri
            }.getOrNull() ?: return@launch

            val bitmap = runCatching {
                requireContext().contentResolver.openInputStream(android.net.Uri.parse(uri))
                    ?.use {
                        android.graphics.BitmapFactory.decodeStream(
                            it, null,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                        )
                    }
            }.getOrNull() ?: return@launch

            val root = binding.root as? android.view.ViewGroup ?: return@launch
            val sizePx = (48 * resources.displayMetrics.density).toInt()
            val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
            val previewLoc = IntArray(2).also { binding.previewView.getLocationInWindow(it) }
            val galleryLoc = IntArray(2).also { binding.btnGallery.getLocationInWindow(it) }

            val startX = (previewLoc[0] - rootLoc[0] +
                binding.previewView.width / 2 - sizePx / 2).toFloat()
            val startY = (previewLoc[1] - rootLoc[1] +
                binding.previewView.height / 2 - sizePx / 2).toFloat()
            val endX = (galleryLoc[0] - rootLoc[0] +
                binding.btnGallery.width / 2 - sizePx / 2).toFloat()
            val endY = (galleryLoc[1] - rootLoc[1] +
                binding.btnGallery.height / 2 - sizePx / 2).toFloat()

            val fly = android.widget.ImageView(requireContext()).apply {
                setImageBitmap(bitmap)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = android.view.ViewGroup.LayoutParams(sizePx, sizePx)
                x = startX
                y = startY
                alpha = 0f
                elevation = 30f
                isClickable = false
            }
            root.addView(fly)
            fly.animate().alpha(1f).scaleX(0.85f).scaleY(0.85f).setDuration(120)
                .withEndAction {
                    fly.animate()
                        .x(endX).y(endY)
                        .scaleX(0.35f).scaleY(0.35f)
                        .alpha(0f)
                        .setDuration(260)
                        .withEndAction { runCatching { root.removeView(fly) } }
                        .start()
                }
                .start()
        }
    }

    /**
     * Handle volume key events for capture.
     */
    override fun onResume() {
        requestCameraPermissionIfNeeded()
        super.onResume()
        // Resume camera preview when returning from background
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
            viewModel.onReturnToCamera()
            // Force overlay to match persisted/display config after returning from gallery/settings
            binding.root.post {
                runCatching {
                    val cfg = viewModel.watermarkConfigDisplay.value.copy(showLocation = true)
                    binding.watermarkOverlay.watermarkConfig = cfg
                    binding.watermarkOverlay.locationText =
                        viewModel.locationDisplay.value.ifBlank { "定位中…" }
                    binding.watermarkOverlay.invalidate()
                }
            }
            binding.root.postDelayed({
                runCatching {
                    val cfg = viewModel.watermarkConfigDisplay.value.copy(showLocation = true)
                    binding.watermarkOverlay.watermarkConfig = cfg
                    binding.watermarkOverlay.locationText =
                        viewModel.locationDisplay.value.ifBlank { "定位中…" }
                    binding.watermarkOverlay.invalidate()
                }
            }, 400)
        }
        requireView().isFocusableInTouchMode = true
        requireView().requestFocus()
        requireView().setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                && event.action == KeyEvent.ACTION_DOWN
            ) {
                viewModel.capturePhoto()
                true // Consume the event
            } else {
                false
            }
        }
        runCatching { applySavedTheme() }
        applySystemBarInsets()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.torchOn.collect { on ->
                runCatching { binding.btnFlash.alpha = if (on) 1f else 0.55f }
            }
        }
        startOrientationSensor()
    }

    override fun onPause() {
        super.onPause()
        requireView().setOnKeyListener(null)
        viewModel.stopPreview()
        stopOrientationSensor()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scaleGestureDetector = null
        stopOrientationSensor()
    }

    // region Navigation

    private fun openGallery() {
        // 统一走 NavController（规范 §3），支持系统返回手势
        runCatching {
            findNavController().navigate(R.id.action_camera_to_gallery)
        }.onFailure { e ->
            android.util.Log.e("CameraFragment", "openGallery", e)
            Feedback.actionable(
                binding.root,
                getString(R.string.feedback_gallery_open_failed)
            )
        }
    }






    private fun openWatermarkPickerSheet() {
        val sheet = WatermarkPickerSheet.newInstance()
        sheet.initialConfig = binding.watermarkOverlay.watermarkConfig
        sheet.onSelectionChanged = { cfg ->
            val synced = cfg.copy(showLocation = true)
            binding.watermarkOverlay.watermarkConfig = synced
            binding.watermarkOverlay.invalidate()
            viewModel.applyConfigFromPicker(synced)
            // Explicit style apply so disk + flow stay in sync
            viewModel.applyTimeStyle(synced.timeStyle)
        }
        sheet.show(parentFragmentManager, "WatermarkPicker")
    }

    /**
     * 保存队列角标（规范 §4：取代原来的 14dp 细条指示器）。
     * 处理中显示转圈 + 张数，失败时在角标旁显示红点。
     */
    private fun setupSaveQueueIndicator() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.saveQueueDepth.collect { depth ->
                updateQueueBadge(depth)
            }
        }
    }

    private fun updateQueueBadge(depth: Int) {
        runCatching {
            if (depth <= 0) {
                binding.queueBadge.visibility = View.GONE
                return@runCatching
            }
            binding.queueBadge.visibility = View.VISIBLE
            binding.queueBadgeProgress.visibility = View.GONE
            binding.tvQueueCount.visibility = View.VISIBLE
            binding.tvQueueCount.text = depth.coerceAtMost(99).toString()
            binding.queueBadge.contentDescription =
                getString(R.string.queue_processing, depth)
        }
    }

    /** 保存/处理失败：红点保留到下一次拍照。 */
    private fun markQueueFailed() {
        runCatching { binding.vQueueFailed.visibility = View.VISIBLE }
    }

    private fun clearQueueFailure() {
        runCatching { binding.vQueueFailed.visibility = View.GONE }
    }


    private fun openWatermarkSettings() {
        val settingsFragment = com.watermark.camera.ui.settings.WatermarkSettingsFragment.newInstance()
        settingsFragment.onConfigSaved = { config ->
            binding.watermarkOverlay.watermarkConfig = config
            viewModel.applyConfigFromPicker(config)
        }
        settingsFragment.show(parentFragmentManager, "WatermarkSettings")
    }

    private fun openCollage() {
        runCatching {
            findNavController().navigate(R.id.action_camera_to_collage)
        }.onFailure { e ->
            android.util.Log.e("CameraFragment", "openCollage", e)
            Feedback.actionable(
                binding.root,
                "${getString(R.string.feedback_collage_open_failed)}: ${e.message}"
            )
        }
    }

    // endregion

    // region Orientation Sensor

    private var orientationHelper: com.watermark.camera.util.OrientationHelper? = null

    private fun startOrientationSensor() {
        orientationHelper = com.watermark.camera.util.OrientationHelper(requireContext()).apply {
            startListening { orientation ->
                // 同步朝向 → Overlay 重绘时 applyGravityEdge 贴重力边
                binding.watermarkOverlay.deviceOrientation = orientation
                binding.watermarkOverlay.invalidate()
                viewModel.setDeviceOrientation(orientation)
            }
        }
    }

    private fun stopOrientationSensor() {
        orientationHelper?.stopListening()
        orientationHelper = null
    }

    // endregion


    private fun hideRedundantTopControls() {
        // Keep bottom bar only; remove duplicate / unused top chrome
        runCatching { binding.tvAspectRatio.visibility = View.GONE }
        // 曝光滑杆在单击对焦后出现（规范 §4），不再强制隐藏
        runCatching { binding.sliderExposure.visibility = View.GONE }
        runCatching { binding.tvEvValue.visibility = View.VISIBLE }
        runCatching { binding.btnSettingsTop.visibility = View.GONE }
        runCatching { binding.btnVerify.visibility = View.GONE }
        runCatching { binding.btnMenu.visibility = View.GONE }
        // tvZoomRatio 必须可见：变焦入口（点击弹出楔形条 / 双指捏合辅助显示）
        runCatching {
            binding.tvZoomRatio.visibility = View.VISIBLE
            binding.tvZoomRatio.elevation = 14f
        }
    }

    private fun flashGalleryButton() {
        val v = binding.btnGallery
        v.animate().cancel()
        v.alpha = 1f
        v.animate()
            .alpha(0.25f)
            .setDuration(90L)
            .withEndAction {
                v.animate().alpha(1f).setDuration(160L).start()
            }
            .start()
    }


    private var sideEvMode = 0 // 0=关 1=自动
    private var sideFlashMode = 0 // 0关 1开 2自动 3常亮
    private var sideImageSize = 1 // 0小 1中 2大
    private var sideAntiFake = false
    private var sideThemeDark = true


    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 顶栏 = 状态栏高 + 56dp；横屏时左右也避开挖孔（规范 §4、§8）
            val px12 = (12 * resources.displayMetrics.density).toInt()
            binding.topBar.updatePadding(
                left = px12 + bars.left,
                top = bars.top,
                right = px12 + bars.right
            )
            // 底栏整体避开导航栏与横屏挖孔
            binding.bottomBar.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun openSidePanel() {
        val root = binding.sidePanelRoot
        val sheet = binding.sidePanelSheet
        // 顶图下移，避开状态栏
        runCatching {
            val hero = binding.root.findViewById<android.view.View>(com.watermark.camera.R.id.sidePanelHero)
            ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                hero?.setPadding(0, bars.top, 0, 0)
                // 用 paddingTop 把图片整体顶下去
                v.setPadding(v.paddingLeft, 0, v.paddingRight, v.paddingBottom)
                hero?.let {
                    val lp = it.layoutParams
                    if (lp is android.view.ViewGroup.MarginLayoutParams) {
                        lp.topMargin = bars.top
                        it.layoutParams = lp
                    }
                }
                insets
            }
            ViewCompat.requestApplyInsets(sheet)
        }
        root.visibility = android.view.View.VISIBLE
        sheet.translationX = sheet.width.toFloat().takeIf { it > 0 } ?: 400f
        sheet.animate().translationX(0f).setDuration(200L).start()
        binding.sidePanelScrim.setOnClickListener { closeSidePanel() }

        // Theme only: 自动 / 黑色 / 白色
        binding.sideThemeAuto.setOnClickListener { applyThemeMode("auto") }
        binding.sideThemeBlack.setOnClickListener { applyThemeMode("black") }
        binding.sideThemeWhite.setOnClickListener { applyThemeMode("white") }

        // 基本设置开关
        runCatching {
            val prefs = requireContext().getSharedPreferences("wm_prefs", android.content.Context.MODE_PRIVATE)
            val swLoc = binding.root.findViewById<androidx.appcompat.widget.SwitchCompat>(com.watermark.camera.R.id.sideSwitchLocation)
            val swGyro = binding.root.findViewById<androidx.appcompat.widget.SwitchCompat>(com.watermark.camera.R.id.sideSwitchGyro)
            val swOn = binding.root.findViewById<androidx.appcompat.widget.SwitchCompat>(com.watermark.camera.R.id.sideSwitchKeepOn)
            val cfg = binding.watermarkOverlay.watermarkConfig
            swLoc?.isChecked = cfg.showLocation
            swGyro?.isChecked = cfg.useGyroscope
            swOn?.isChecked = prefs.getBoolean("keep_screen_on", false)
            swLoc?.setOnCheckedChangeListener { _, checked ->
                val c = binding.watermarkOverlay.watermarkConfig.copy(showLocation = checked)
                binding.watermarkOverlay.watermarkConfig = c
                viewModel.applyLiveOverlayConfig(c)
            }
            swGyro?.setOnCheckedChangeListener { _, checked ->
                // 开陀螺仪：清掉拖拽坐标，让重力贴边立即生效
                val c = binding.watermarkOverlay.watermarkConfig.copy(
                    useGyroscope = checked,
                    customX = if (checked) null else binding.watermarkOverlay.watermarkConfig.customX,
                    customY = if (checked) null else binding.watermarkOverlay.watermarkConfig.customY
                )
                binding.watermarkOverlay.watermarkConfig = c
                viewModel.applyLiveOverlayConfig(c)
                if (checked) {
                    // 立即用当前朝向重绘（传感器回调有防抖，可能要等）
                    orientationHelper?.getCurrentOrientation()?.let { o ->
                        binding.watermarkOverlay.deviceOrientation = o
                    }
                    binding.watermarkOverlay.invalidate()
                } else {
                    binding.watermarkOverlay.deviceOrientation =
                        com.watermark.camera.util.OrientationHelper.DeviceOrientation.PORTRAIT
                    binding.watermarkOverlay.invalidate()
                }
            }
            swOn?.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("keep_screen_on", checked).apply()
                requireActivity().window.let { w ->
                    if (checked) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            fun applyPos(pos: com.watermark.camera.data.model.WatermarkPosition) {
                // 设定偏好角；若陀螺仪开启，下次朝向变化仍会跟重力，但左右偏好保留
                val c = binding.watermarkOverlay.watermarkConfig.copy(
                    position = pos, customX = null, customY = null
                )
                binding.watermarkOverlay.watermarkConfig = c
                binding.watermarkOverlay.invalidate()
                viewModel.applyLiveOverlayConfig(c)
            }
            binding.root.findViewById<android.view.View>(com.watermark.camera.R.id.sidePosBL)
                ?.setOnClickListener { applyPos(com.watermark.camera.data.model.WatermarkPosition.BOTTOM_LEFT) }
            binding.root.findViewById<android.view.View>(com.watermark.camera.R.id.sidePosBR)
                ?.setOnClickListener { applyPos(com.watermark.camera.data.model.WatermarkPosition.BOTTOM_RIGHT) }
            binding.root.findViewById<android.view.View>(com.watermark.camera.R.id.sidePosTL)
                ?.setOnClickListener { applyPos(com.watermark.camera.data.model.WatermarkPosition.TOP_LEFT) }
            binding.root.findViewById<android.view.View>(com.watermark.camera.R.id.sidePosTR)
                ?.setOnClickListener { applyPos(com.watermark.camera.data.model.WatermarkPosition.TOP_RIGHT) }
        }
        highlightThemeButtons(
            requireContext().getSharedPreferences("wm_prefs", android.content.Context.MODE_PRIVATE)
                .getString("theme_mode", "auto") ?: "auto"
        )
        binding.sideAbout.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("关于")
                .setMessage("工作相机 Work Watermark Camera\nELYSIYISLOVE")
                .setPositiveButton("确定", null)
                .show()
        }
    }


    private fun applyThemeMode(mode: String) {
        when (mode) {
            "black" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "white" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        persistTheme(mode)
        highlightThemeButtons(mode)
        // 不再手动给取景容器刷黑/白：主题色现在由 values/ 与 values-night/ 的语义色驱动，
        // 相机页本身固定深色（相机类应用惯例），相册/拼图/详情等页面随主题切换。
        // recreate 让已存在的其它页面也立即套用新主题。
        runCatching { requireActivity().recreate() }
    }

    private fun highlightThemeButtons(mode: String) {
        val selected = 0xFF2B6AFF.toInt()
        val normal = 0xFF3A3A3A.toInt()
        runCatching {
            binding.sideThemeAuto.setBackgroundColor(if (mode == "auto") selected else normal)
            binding.sideThemeBlack.setBackgroundColor(if (mode == "black") selected else normal)
            binding.sideThemeWhite.setBackgroundColor(if (mode == "white") selected else normal)
        }
    }

    private fun persistTheme(mode: String) {
        requireContext().getSharedPreferences("wm_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("theme_mode", mode).apply()
    }

    private fun applySavedTheme() {
        val mode = requireContext().getSharedPreferences("wm_prefs", android.content.Context.MODE_PRIVATE)
            .getString("theme_mode", "auto") ?: "auto"
        when (mode) {
            "black" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                binding.container.setBackgroundColor(android.graphics.Color.BLACK)
            }
            "white" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                binding.container.setBackgroundColor(android.graphics.Color.WHITE)
            }
            else -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                binding.container.setBackgroundColor(android.graphics.Color.BLACK)
            }
        }
    }

    private fun closeSidePanel() {
        val sheet = binding.sidePanelSheet
        val root = binding.sidePanelRoot
        sheet.animate()
            .translationX(sheet.width.toFloat())
            .setDuration(200L)
            .withEndAction { root.visibility = android.view.View.GONE }
            .start()
    }

    private fun refreshSidePanelLabels() { /* camera settings removed */ }



    private fun applyRoundedPreviewClip() {
        val preview = binding.previewView
        preview.post {
            val radius = 20f * resources.displayMetrics.density
            preview.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            preview.clipToOutline = true
            // Watermark follows same visual bounds
            binding.watermarkOverlay.outlineProvider = preview.outlineProvider
            binding.watermarkOverlay.clipToOutline = true
        }
    }

    private fun performCaptureHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = requireContext().getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                requireContext().getSystemService(Vibrator::class.java)
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200L)
            }
        } catch (_: Exception) {
        }
    }

    private fun rebindCameraPreview() {
        try {
            viewModel.stopPreview()
            binding.previewView.postDelayed({
                try {
                    if (isAdded && view != null) {
                        viewModel.startPreview(
                            viewLifecycleOwner,
                            binding.previewView.surfaceProvider
                        )
                    }
                } catch (_: Exception) {
                }
            }, 120L)
        } catch (_: Exception) {
        }
    }


    private fun applyLetterboxTheme() {
        val mode = requireContext()
            .getSharedPreferences("wm_prefs", android.content.Context.MODE_PRIVATE)
            .getString("theme_mode", "auto") ?: "auto"
        val night = when (mode) {
            "white" -> false
            "black" -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val bg = if (night) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        runCatching { binding.letterboxMask.setBackgroundColor(bg) }
        runCatching { binding.container.setBackgroundColor(bg) }
        // Keep controls above mask
        runCatching { binding.bottomBar.elevation = 10f }
        runCatching { binding.topBar.elevation = 10f }
        runCatching { binding.zoomChipRow.elevation = 12f }
    }



    /** 音量楔形变焦条 */
    private var zoomWedgeView: com.watermark.camera.ui.widget.ZoomWedgeView? = null
    private var zoomWedgeHost: android.widget.FrameLayout? = null
    private var zoomCenterLabel: android.widget.TextView? = null
    private var zoomWedgeVisible = false

    private fun setupZoomChips() {
        // 镜头芯片只显示设备实际具备的档位（规范 §4 / §8）
        runCatching {
            val minZoom = viewModel.getMinZoomRatio().coerceAtLeast(0.1f)
            val maxZoom = viewModel.getMaxZoomRatio().coerceAtLeast(1f)

            binding.zoomChip05.visibility = if (minZoom <= 0.55f) View.VISIBLE else View.GONE
            binding.zoomChip1x.visibility = View.VISIBLE
            binding.zoomChip2x.visibility = if (maxZoom >= 1.9f) View.VISIBLE else View.GONE
            binding.zoomChipRow.visibility = View.VISIBLE

            binding.zoomChip05.setOnClickListener {
                applyZoomFromUi(0.5f.coerceIn(minZoom, maxZoom))
            }
            binding.zoomChip1x.setOnClickListener { applyZoomFromUi(1f.coerceIn(minZoom, maxZoom)) }
            binding.zoomChip2x.setOnClickListener { applyZoomFromUi(2f.coerceIn(minZoom, maxZoom)) }

            // 没有补光灯的设备直接隐藏按钮
            if (!viewModel.hasFlashUnit()) binding.btnFlash.visibility = View.GONE
        }
        // 倍率文字必须可见，作为变焦入口
        runCatching {
            binding.tvZoomRatio.visibility = View.VISIBLE
            binding.tvZoomRatio.text = String.format("%.1fx", currentZoomRatio)
            binding.tvZoomRatio.elevation = 16f
            binding.tvZoomRatio.isClickable = true
            binding.tvZoomRatio.isFocusable = true
        }
        ensureZoomWedge()

        // 点击倍率：弹出楔形条（按住拖动变焦）；再点空白关闭
        binding.tvZoomRatio.setOnClickListener {
            if (zoomWedgeVisible) {
                hideZoomWedge()
            } else {
                showZoomWedge()
            }
        }
        // 去掉会覆盖 click 的 longClick；用 click 更可靠
        binding.tvZoomRatio.setOnLongClickListener {
            showZoomWedge()
            true
        }
    }

    private fun ensureZoomWedge() {
        if (zoomWedgeHost != null) return
        val parent = binding.root as? android.view.ViewGroup ?: return
        val dens = resources.displayMetrics.density
        val host = android.widget.FrameLayout(requireContext()).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(0x66000000)
            elevation = 30f
            setOnClickListener { hideZoomWedge() }
        }
        val wedge = com.watermark.camera.ui.widget.ZoomWedgeView(requireContext()).apply {
            val lp = android.widget.FrameLayout.LayoutParams(
                (64 * dens).toInt(),
                (260 * dens).toInt()
            )
            lp.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            lp.marginEnd = (20 * dens).toInt()
            layoutParams = lp
            elevation = 40f
            // 阻止点击穿透到 host 导致立刻关闭
            isClickable = true
            onZoomChanged = { z ->
                applyZoomFromUi(z, showCenter = true)
            }
        }
        val label = android.widget.TextView(requireContext()).apply {
            val lp = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.CENTER
            layoutParams = lp
            textSize = 52f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setShadowLayer(14f, 0f, 0f, 0xFF000000.toInt())
            elevation = 50f
            visibility = View.GONE
            // 不拦截点击
            isClickable = false
        }
        host.addView(wedge)
        host.addView(label)
        parent.addView(host)
        zoomWedgeHost = host
        zoomWedgeView = wedge
        zoomCenterLabel = label
    }

    private fun showZoomWedge() {
        ensureZoomWedge()
        val minZ = viewModel.getMinZoomRatio().coerceAtLeast(1f)
        val maxZ = minOf(viewModel.getMaxZoomRatio().coerceAtLeast(minZ + 0.1f), MAX_USER_ZOOM)
        zoomWedgeView?.minZoom = minZ
        zoomWedgeView?.maxZoom = maxZ
        val cur = currentZoomRatio.coerceIn(minZ, maxZ)
        zoomWedgeView?.zoomRatio = cur
        zoomWedgeHost?.visibility = View.VISIBLE
        zoomWedgeVisible = true
        zoomCenterLabel?.let {
            it.text = String.format("%.1fx", cur)
            it.visibility = View.VISIBLE
            it.alpha = 1f
        }
        runCatching { binding.tvZoomRatio.visibility = View.VISIBLE }
    }

    private fun hideZoomWedge() {
        zoomWedgeHost?.visibility = View.GONE
        zoomCenterLabel?.visibility = View.GONE
        zoomWedgeVisible = false
    }

    private fun applyZoomFromUi(ratio: Float, showCenter: Boolean = false) {
        val minZ = viewModel.getMinZoomRatio().coerceAtLeast(1f)
        val maxZ = minOf(viewModel.getMaxZoomRatio().coerceAtLeast(minZ + 0.1f), MAX_USER_ZOOM)
        val target = ratio.coerceIn(minZ, maxZ)
        viewModel.setZoomRatio(target)
        currentZoomRatio = target
        runCatching {
            binding.tvZoomRatio.visibility = View.VISIBLE
            binding.tvZoomRatio.text = String.format("%.1fx", target)
        }
        zoomWedgeView?.zoomRatio = target
        if (showCenter) {
            zoomCenterLabel?.let {
                it.visibility = View.VISIBLE
                it.text = String.format("%.1fx", target)
            }
        }
    }

    private fun showZoomTip(ratio: Float) {
        applyZoomFromUi(ratio, showCenter = true)
    }

    private fun hideZoomTip() {
        zoomCenterLabel?.visibility = View.GONE
    }






}
