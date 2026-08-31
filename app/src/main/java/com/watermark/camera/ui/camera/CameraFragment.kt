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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.watermark.camera.databinding.FragmentCameraBinding
import com.watermark.camera.R
import com.watermark.camera.ui.watermark.WatermarkPickerSheet
import com.watermark.camera.ui.common.BaseFragment
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
            Toast.makeText(requireContext(), "相机权限被拒绝", Toast.LENGTH_LONG).show()
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

        // Settings button click
        binding.btnSettings.setOnClickListener {
            openSidePanel()
        }

        // Top bar buttons
        binding.btnCollage.setOnClickListener {
            binding.btnCollageBottom.performClick()
        }
        binding.btnSettingsTop.setOnClickListener {
            binding.btnSettings.performClick()
        }
        binding.btnVerify.setOnClickListener {
            Toast.makeText(requireContext(), "请在相册中打开照片进行验真", Toast.LENGTH_SHORT).show()
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

        binding.tvZoomRatio.setOnClickListener {
            viewModel.setZoomRatio(1.0f)
        }

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
            if (event.pointerCount == 1 && event.action == android.view.MotionEvent.ACTION_UP && !scaleHandled) {
                val previewView = binding.previewView
                val x = event.x / previewView.width
                val y = event.y / previewView.height
                viewModel.tapToFocus(x, y)
                showFocusFrame(event.x, event.y)
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
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Handle one-time UI events.
     */


    private suspend fun observeWatermarkConfigDisplay() {
        viewModel.watermarkConfigDisplay.collect { config ->
            binding.watermarkOverlay.watermarkConfig = config.copy(showLocation = true)
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
                // All popup toasts suppressed per product requirement
            }
            is CameraEvent.ShutterFeedback -> {
                playShutterAnimation()
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
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scale = detector.scaleFactor
                    val maxZ = minOf(viewModel.getMaxZoomRatio(), MAX_USER_ZOOM)
                    val newZoom = (currentZoomRatio * scale).coerceIn(
                        viewModel.getMinZoomRatio(),
                        maxZ
                    )
                    viewModel.setZoomRatio(newZoom)
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
                .setDuration(200)
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
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(
                com.watermark.camera.R.id.nav_host_fragment,
                com.watermark.camera.ui.gallery.GalleryFragment()
            )
            .addToBackStack("gallery")
            .commit()
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

    private val queueSlotViews = mutableListOf<View>()

    private fun setupSaveQueueIndicator() {
        queueSlotViews.clear()
        val ids = intArrayOf(
            R.id.queueSlot0,
            R.id.queueSlot1,
            R.id.queueSlot2,
            R.id.queueSlot3,
            R.id.queueSlot4,
            R.id.queueSlot5,
            R.id.queueSlot6,
            R.id.queueSlot7,
            R.id.queueSlot8,
            R.id.queueSlot9
        )
        for (id in ids) {
            val slot = binding.root.findViewById<View>(id)
            if (slot != null) queueSlotViews.add(slot)
        }
        runCatching {
            val indicator = binding.root.findViewById<View>(R.id.saveQueueIndicatorRoot)
                
            indicator?.visibility = View.VISIBLE
            indicator?.isClickable = false
            indicator?.isFocusable = false
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.saveQueueDepth.collect { depth ->
                updateQueueIndicator(depth)
            }
        }
    }

    private fun updateQueueIndicator(depth: Int) {
        val d = depth.coerceIn(0, 10)
        // queueSlot0 = bottom of the bar; fill bottom-up as queue grows
        queueSlotViews.forEachIndexed { index, view ->
            val filled = index < d
            view.setBackgroundColor(
                if (filled) 0xFF4CAF50.toInt() else 0x33FFFFFF
            )
            view.alpha = if (filled) 1f else 0.35f
        }
        binding.root.findViewById<View>(R.id.saveQueueIndicatorRoot)?.let { root ->
            root.alpha = if (d >= 10) 1f else 0.85f
            // Full queue: slight red border cue via tag only; keep green slots
        }
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
        try {
            val collage = com.watermark.camera.ui.collage.CollageFragment.newInstance(emptyList())
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, collage, "collage")
                .addToBackStack("collage")
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            android.util.Log.e("CameraFragment", "openCollage", e)
            android.widget.Toast.makeText(requireContext(), "无法打开拼图: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // endregion

    // region Orientation Sensor

    private var orientationHelper: com.watermark.camera.util.OrientationHelper? = null

    private fun startOrientationSensor() {
        orientationHelper = com.watermark.camera.util.OrientationHelper(requireContext()).apply {
            startListening { orientation ->
                binding.watermarkOverlay.deviceOrientation = orientation
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
        runCatching { binding.tvEvValue.visibility = View.GONE }
        runCatching { binding.sliderExposure.visibility = View.GONE }
        runCatching { binding.btnCollage.visibility = View.GONE }
        runCatching { binding.btnSettingsTop.visibility = View.GONE }
        runCatching { binding.btnVerify.visibility = View.GONE }
        runCatching { binding.btnMenu.visibility = View.GONE }
        // zoom can stay or hide — hide to declutter
        runCatching { binding.tvZoomRatio.visibility = View.GONE }
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
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 所有顶部控件下移，避免被状态栏遮挡
            // 仅使用布局中真实存在的 id，避免 R.id 编译失败
            val topIds = intArrayOf(
                com.watermark.camera.R.id.btnFlash,
                com.watermark.camera.R.id.btnMenu,
                com.watermark.camera.R.id.btnSettingsTop,
                com.watermark.camera.R.id.btnTurnOnFlash
            )
            for (id in topIds) {
                runCatching {
                    val v = binding.root.findViewById<android.view.View>(id) ?: return@runCatching
                    val lp = v.layoutParams
                    if (lp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                        lp.topMargin = bars.top + 8
                        v.layoutParams = lp
                    } else {
                        v.setPadding(v.paddingLeft, bars.top + 4, v.paddingRight, v.paddingBottom)
                    }
                }
            }
            runCatching {
                val lp = binding.bottomBar.layoutParams
                if (lp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                    lp.bottomMargin = bars.bottom
                    binding.bottomBar.layoutParams = lp
                }
            }
            runCatching {
                val lp = binding.tvZoomRatio.layoutParams
                if (lp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                    lp.bottomMargin = bars.bottom + 6
                    binding.tvZoomRatio.layoutParams = lp
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun openSidePanel() {
        val root = binding.sidePanelRoot
        val sheet = binding.sidePanelSheet
        root.visibility = android.view.View.VISIBLE
        sheet.translationX = sheet.width.toFloat().takeIf { it > 0 } ?: 400f
        sheet.animate().translationX(0f).setDuration(200L).start()
        binding.sidePanelScrim.setOnClickListener { closeSidePanel() }

        // Theme only: 自动 / 黑色 / 白色
        binding.sideThemeAuto.setOnClickListener { applyThemeMode("auto") }
        binding.sideThemeBlack.setOnClickListener { applyThemeMode("black") }
        binding.sideThemeWhite.setOnClickListener { applyThemeMode("white") }
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
        // Apply to current screen immediately
        val bg = if (mode == "white") android.graphics.Color.WHITE else android.graphics.Color.BLACK
        runCatching { binding.container.setBackgroundColor(bg) }
        runCatching { binding.letterboxMask.setBackgroundColor(bg) }
        runCatching { binding.topBar.setBackgroundColor(bg) }
        runCatching { binding.bottomBar.setBackgroundColor(if (mode == "white") 0xFFF5F5F5.toInt() else 0xFF000000.toInt()) }
        // Recreate activity so theme applies app-wide (gallery/settings/etc.)
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

    private fun setupZoomChips() {
        // 横向滑动变焦 + 波轮动画 + 倍数提示
        runCatching {
            binding.zoomChip05.visibility = View.GONE
            binding.zoomChip1x.visibility = View.GONE
            binding.zoomChip2x.visibility = View.GONE
        }
        runCatching {
            binding.tvZoomRatio.visibility = View.VISIBLE
            binding.tvZoomRatio.text = String.format("%.1fx", currentZoomRatio)
            binding.tvZoomRatio.elevation = 14f
        }
        fun applyZoom(ratio: Float, showTip: Boolean = true) {
            val minZ = viewModel.getMinZoomRatio()
            val maxZ = minOf(viewModel.getMaxZoomRatio(), MAX_USER_ZOOM)
            val target = ratio.coerceIn(minZ, maxZ)
            viewModel.setZoomRatio(target)
            currentZoomRatio = target
            runCatching {
                binding.tvZoomRatio.visibility = View.VISIBLE
                binding.tvZoomRatio.text = String.format("%.1fx", target)
                // 波轮动画
                binding.tvZoomRatio.animate().cancel()
                binding.tvZoomRatio.scaleX = 1f
                binding.tvZoomRatio.scaleY = 1f
                binding.tvZoomRatio.animate()
                    .scaleX(1.18f).scaleY(1.18f)
                    .setDuration(90L)
                    .withEndAction {
                        binding.tvZoomRatio.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(120L)
                            .start()
                    }.start()
            }
            if (showTip) {
                showZoomTip(target)
            }
        }
        var dragStartX = 0f
        var dragStartZoom = 1f
        var dragging = false
        val slop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
        val target = runCatching { binding.tvZoomRatio }.getOrNull()
            ?: runCatching { binding.zoomChipRow }.getOrNull()
        target?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartZoom = currentZoomRatio
                    dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    if (!dragging && kotlin.math.abs(dx) > slop) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        // 横向：右滑放大，左滑缩小
                        applyZoom(dragStartZoom + dx / 100f, showTip = false)
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val was = dragging
                    dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!was) {
                        applyZoom(1.0f, showTip = true)
                    } else {
                        showZoomTip(currentZoomRatio)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private var zoomTipJob: Runnable? = null
    private fun showZoomTip(ratio: Float) {
        val tip = runCatching { binding.tvZoomRatio }.getOrNull() ?: return
        // 简短弹出提示：在倍数旁/上方闪一下文字感
        tip.alpha = 1f
        tip.animate().cancel()
        tip.alpha = 0.55f
        tip.animate().alpha(1f).setDuration(80L).start()
        runCatching {
            android.widget.Toast.makeText(
                requireContext(),
                String.format("变焦 %.1fx", ratio),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        // 节流：取消过频 Toast 用 postDelayed 去抖
        zoomTipJob?.let { tip.removeCallbacks(it) }
        val r = Runnable { /* tip stays */ }
        zoomTipJob = r
        tip.postDelayed(r, 400L)
    }


}
