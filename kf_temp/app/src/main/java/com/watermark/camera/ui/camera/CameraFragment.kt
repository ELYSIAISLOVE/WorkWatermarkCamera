package com.watermark.camera.ui.camera

import android.Manifest
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.watermark.camera.databinding.FragmentCameraBinding
import com.watermark.camera.data.model.WatermarkTemplate
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
                    viewModel.watermarkConfigDisplay.value.copy(showLocation = true, fontScale = 2.5f)
                binding.watermarkOverlay.invalidate()
            }
            binding.root.postDelayed({
                binding.watermarkOverlay.locationText =
                    viewModel.locationDisplay.value.ifBlank { "定位中…" }
                binding.watermarkOverlay.watermarkConfig =
                    viewModel.watermarkConfigDisplay.value.copy(showLocation = true, fontScale = 2.5f)
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
            viewModel.capturePhoto()
        }

        // Flash button click
        binding.btnFlash.setOnClickListener {
            viewModel.cycleFlashMode()
        }

        // Gallery button click
        binding.btnGallery.setOnClickListener {
            openGallery()
        }

        // Watermark: short click toggles template strip; long click opens full settings
        binding.btnWatermark.setOnClickListener {
            toggleTemplateStrip()
        }
        binding.btnWatermark.setOnLongClickListener {
            openWatermarkSettings()
            true
        }
        setupTemplateStrip()

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

        // Low light warning - turn on flash
        binding.btnTurnOnFlash.setOnClickListener {
            viewModel.turnOnFlash()
        }

        // Zoom ratio click to reset
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
                binding.tvZoomRatio.visibility = View.GONE
                binding.tvZoomRatio.text = String.format("%.1fx", state.zoomRatio)
                binding.tvAspectRatio.text = "4:3"
                binding.tvAspectRatio.visibility = View.GONE
                binding.tvEvValue.visibility = View.GONE
                binding.tvEvValue.text = String.format("EV %+.1f", state.evValue)
                currentZoomRatio = state.zoomRatio

                // Flash mode indicator
                binding.btnFlash.visibility = View.VISIBLE
                val flashIcon = when (state.flashMode) {
                    com.watermark.camera.domain.repository.FlashMode.AUTO ->
                        android.R.drawable.ic_menu_compass
                    com.watermark.camera.domain.repository.FlashMode.ON ->
                        android.R.drawable.ic_menu_gallery
                    com.watermark.camera.domain.repository.FlashMode.OFF ->
                        android.R.drawable.ic_menu_close_clear_cancel
                    com.watermark.camera.domain.repository.FlashMode.TORCH ->
                        android.R.drawable.ic_menu_mapmode
                }
                binding.btnFlash.setImageResource(flashIcon)

                // Low light warning
                binding.lowLightWarning.visibility =
                    if (state.isLowLight && state.flashMode == com.watermark.camera.domain.repository.FlashMode.AUTO)
                        View.VISIBLE else View.GONE

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
            binding.watermarkOverlay.watermarkConfig = config.copy(showLocation = true, fontScale = 2.5f)
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
                    val newZoom = (currentZoomRatio * scale).coerceIn(
                        viewModel.getMinZoomRatio(),
                        viewModel.getMaxZoomRatio()
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


    private var templateStripAdapter: CameraTemplateStripAdapter? = null
    private var templateStripVisible = false

    private fun setupTemplateStrip() {
        val rv = binding.templateStrip
        rv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        val adapter = CameraTemplateStripAdapter { template ->
            viewModel.applyTemplate(template)
            binding.watermarkOverlay.watermarkConfig =
                binding.watermarkOverlay.watermarkConfig.copy(template = template, showLocation = true, fontScale = 2.5f)
        }
        templateStripAdapter = adapter
        rv.adapter = adapter
        // Sync selection with current config when strip shown
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.watermarkConfigDisplay.collect { cfg ->
                templateStripAdapter?.setSelected(cfg.template)
            }
        }
    }

    private fun toggleTemplateStrip() {
        templateStripVisible = !templateStripVisible
        binding.templateStrip.visibility = if (templateStripVisible) View.VISIBLE else View.GONE
    }

    private fun openWatermarkSettings() {
        val settingsFragment = com.watermark.camera.ui.settings.WatermarkSettingsFragment.newInstance()
        settingsFragment.onConfigSaved = { config ->
            binding.watermarkOverlay.watermarkConfig = config
            templateStripAdapter?.setSelected(config.template)
        }
        settingsFragment.show(parentFragmentManager, "WatermarkSettings")
    }

    private fun openCollage() {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(
                com.watermark.camera.R.id.nav_host_fragment,
                com.watermark.camera.ui.collage.CollageFragment()
            )
            .addToBackStack("collage")
            .commit()
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

    private fun openSidePanel() {
        val root = binding.sidePanelRoot
        val sheet = binding.sidePanelSheet
        root.visibility = android.view.View.VISIBLE
        refreshSidePanelLabels()
        sheet.post {
            sheet.translationX = sheet.width.toFloat()
            sheet.animate().translationX(0f).setDuration(220L).start()
        }
        binding.sidePanelScrim.setOnClickListener { closeSidePanel() }
        binding.sideEv.setOnClickListener {
            sideEvMode = (sideEvMode + 1) % 2
            refreshSidePanelLabels()
            // Auto EV placeholder: 0f when 关, else keep current
            if (sideEvMode == 0) viewModel.setExposureCompensation(0f)
        }
        binding.sideFlash.setOnClickListener {
            sideFlashMode = (sideFlashMode + 1) % 4
            refreshSidePanelLabels()
            val mode = when (sideFlashMode) {
                1 -> com.watermark.camera.domain.repository.FlashMode.ON
                2 -> com.watermark.camera.domain.repository.FlashMode.AUTO
                3 -> com.watermark.camera.domain.repository.FlashMode.TORCH
                else -> com.watermark.camera.domain.repository.FlashMode.OFF
            }
            viewModel.setFlashMode(mode)
        }
        binding.sideImageSize.setOnClickListener {
            sideImageSize = (sideImageSize + 1) % 3
            refreshSidePanelLabels()
            viewModel.setImageSizePreset(sideImageSize)
        }
        binding.sideAntiFake.setOnClickListener {
            sideAntiFake = !sideAntiFake
            refreshSidePanelLabels()
            viewModel.setAntiFakeWatermark(sideAntiFake)
        }
        binding.sideThemeBlack.setOnClickListener {
            sideThemeDark = true
            binding.container.setBackgroundColor(android.graphics.Color.BLACK)
runCatching { binding.previewRoundedMask.setBackgroundResource(com.watermark.camera.R.drawable.bg_preview_rounded_stroke) }
        }
        binding.sideThemeWhite.setOnClickListener {
            sideThemeDark = false
            binding.container.setBackgroundColor(android.graphics.Color.WHITE)
runCatching { binding.previewRoundedMask.setBackgroundResource(com.watermark.camera.R.drawable.bg_preview_rounded_stroke) }
        }
        binding.sideAbout.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("关于")
                .setMessage("工作相机 Work Watermark Camera\n版本见应用信息")
                .setPositiveButton("确定", null)
                .show()
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

    private fun refreshSidePanelLabels() {
        binding.sideEv.text = "EV补偿：" + if (sideEvMode == 0) "关" else "自动"
        binding.sideFlash.text = "闪光灯：" + when (sideFlashMode) {
            1 -> "开"
            2 -> "自动"
            3 -> "常亮"
            else -> "关"
        }
        binding.sideImageSize.text = "图片大小：" + when (sideImageSize) {
            0 -> "小"
            2 -> "大"
            else -> "中"
        }
        binding.sideAntiFake.text = "防伪水印：" + if (sideAntiFake) "开" else "关"
    }



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
                vibrator.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10L)
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

}
