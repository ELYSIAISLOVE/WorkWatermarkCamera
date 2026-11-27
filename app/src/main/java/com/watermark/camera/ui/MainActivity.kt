package com.watermark.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.watermark.camera.R
import com.watermark.camera.databinding.ActivityMainBinding
import com.watermark.camera.ui.common.Feedback
import com.watermark.camera.ui.permission.PermissionIntroState
import com.watermark.camera.util.Logger
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用外壳。
 *
 * 权限策略按 `docs/08-交互与界面设计规范.md` §3「权限情境化」：
 * - 首启先一屏说明用途，再**依次**请求相机、定位，各自只弹一次；
 * - 之后不再自动弹窗，缺权限只在底部显示常驻提示条 +「去设置」；
 * - 相册权限由相册页按需请求，不在这里申请；不再每次 onResume 重复请求。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    /** 首启流程第一步：相机 */
    private val introCameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(TAG, "Intro camera permission granted=$granted")
        // 无论是否授予都继续请求定位：两项权限互不依赖，用户可只给其中一项
        introLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** 首启流程第二步：定位，之后收尾 */
    private val introLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(TAG, "Intro location permission granted=$granted")
        finishIntro()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.btnPermissionGoSettings.setOnClickListener { Feedback.openAppSettings(this) }
        binding.btnIntroContinue.setOnClickListener { startIntroFlow() }
        binding.btnIntroLater.setOnClickListener { finishIntro() }

        showIntro(!PermissionIntroState.isDone(this))
    }

    override fun onResume() {
        super.onResume()
        // 只刷新提示条，不在回到前台时请求权限（规范 §3）
        refreshPermissionBanner()
    }

    private fun startIntroFlow() {
        binding.btnIntroContinue.isEnabled = false
        introCameraLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun finishIntro() {
        PermissionIntroState.markDone(this)
        showIntro(false)
    }

    private fun showIntro(show: Boolean) {
        binding.permissionIntro.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnIntroContinue.isEnabled = true
        if (show) {
            binding.permissionBanner.visibility = View.GONE
        } else {
            refreshPermissionBanner()
        }
    }

    /**
     * 缺权限时给出常驻提示条。相机缺失优先提示（直接挡住主流程），
     * 定位缺失只影响水印上的地点。
     */
    private fun refreshPermissionBanner() {
        if (!PermissionIntroState.isDone(this)) {
            binding.permissionBanner.visibility = View.GONE
            return
        }
        val cameraGranted = isGranted(Manifest.permission.CAMERA)
        val locationGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

        val messageRes = when {
            !cameraGranted -> R.string.permission_banner_camera
            !locationGranted -> R.string.permission_banner_location
            else -> null
        }
        if (messageRes == null) {
            binding.permissionBanner.visibility = View.GONE
        } else {
            binding.tvPermissionBannerMessage.setText(messageRes)
            binding.permissionBanner.visibility = View.VISIBLE
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 提示条贴导航栏之上，说明页避开状态栏、刘海与导航栏。 */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.permissionBanner.updatePadding(
                left = px(16) + bars.left,
                right = px(8) + bars.right,
                bottom = px(8) + bars.bottom
            )
            binding.permissionIntro.updatePadding(
                top = px(32) + bars.top,
                bottom = px(24) + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}