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
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import com.watermark.camera.databinding.FragmentCameraBinding
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

        // Watermark edit button click
        binding.btnWatermark.setOnClickListener {
            val settingsFragment = com.watermark.camera.ui.settings.WatermarkSettingsFragment.newInstance()
            settingsFragment.onConfigSaved = { config ->
                binding.watermarkOverlay.watermarkConfig = config
                Toast.makeText(requireContext(), "水印已更新", Toast.LENGTH_SHORT).show()
            }
            settingsFragment.show(parentFragmentManager, "WatermarkSettings")
        }

        // Collage button click
        binding.btnCollageBottom.setOnClickListener {
            openCollage()
        }

        // Settings button click
        binding.btnSettings.setOnClickListener {
            val settingsFragment = com.watermark.camera.ui.settings.WatermarkSettingsFragment.newInstance()
            settingsFragment.onConfigSaved = { config ->
                binding.watermarkOverlay.watermarkConfig = config
            }
            settingsFragment.show(parentFragmentManager, "WatermarkSettings")
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
            viewModel.cycleAspectRatio()
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
                binding.tvAspectRatio.text = state.aspectRatio
                binding.tvAspectRatio.visibility = View.VISIBLE
                binding.tvEvValue.visibility = View.VISIBLE
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

                // Focus lock indicator
                binding.tvAspectRatio.setTextColor(
                    if (state.isFocusLocked)
                        requireContext().getColor(android.R.color.holo_red_dark)
                    else
                        requireContext().getColor(android.R.color.white)
                )
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

    private suspend fun observeLocationDisplay() {
        viewModel.locationDisplay.collect { text ->
            binding.watermarkOverlay.locationText = text
        }
    }

    private fun handleEvent(event: CameraEvent) {
        when (event) {
            is CameraEvent.ShowToast -> {
                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
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
            }
        }
    }

    private fun stopOrientationSensor() {
        orientationHelper?.stopListening()
        orientationHelper = null
    }

    // endregion

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
