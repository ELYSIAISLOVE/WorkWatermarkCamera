package com.watermark.camera.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.watermark.camera.BuildConfig
import com.watermark.camera.R
import com.watermark.camera.databinding.FragmentSettingsBinding
import com.watermark.camera.domain.usecase.GetWatermarkConfigUseCase
import com.watermark.camera.ui.common.BaseFragment
import com.watermark.camera.ui.common.Feedback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 合并后的唯一设置页（规范 §3）：
 * 拍摄 / 水印 / 时间与地点 / 关于 四组，取消侧栏与第二个设置页。
 */
@AndroidEntryPoint
class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {

    companion object {
        private const val PREFS = "ui_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        private const val THEME_SYSTEM = "system"
        private const val THEME_LIGHT = "light"
        private const val THEME_DARK = "dark"
    }

    @Inject
    lateinit var getWatermarkConfig: GetWatermarkConfigUseCase

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentSettingsBinding = FragmentSettingsBinding.inflate(inflater, container, false)

    override fun initViews() {
        // edge-to-edge：页面自己吃掉状态栏/导航栏安全区（规范 §8）
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.btnSettingsBack.setOnClickListener { findNavController().popBackStack() }

        // 拍摄：主题模式
        bindThemeRadio()
        binding.rgTheme.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> THEME_LIGHT
                R.id.rbThemeDark -> THEME_DARK
                else -> THEME_SYSTEM
            }
            prefs.edit().putString(KEY_THEME, mode).apply()
            applyThemeMode(mode)
        }

        // 水印：打开水印编辑（复用同一套面板）
        binding.btnOpenWatermarkEditor.setOnClickListener {
            com.watermark.camera.ui.settings.WatermarkSettingsFragment.newInstance()
                .show(parentFragmentManager, "WatermarkSettings")
        }

        // 拍摄：保持屏幕常亮（默认开）
        val keepOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        binding.swKeepScreenOn.isChecked = keepOn
        binding.swKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply()
        }

        // 时间与地点：权限状态与跳转系统设置
        binding.btnLocationSettings.setOnClickListener { Feedback.openAppSettings(requireContext()) }

        // 关于
        binding.tvVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            // NoParamsUseCase 的公开入口是 operator invoke()
            val config = getWatermarkConfig().getOrNull()
            binding.tvCurrentTemplate.text = config?.customTitle?.ifBlank { null }
                ?: config?.template?.displayName
                ?: getString(R.string.settings_template_unknown)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocationState()
    }

    private fun bindThemeRadio() {
        val checked = when (prefs.getString(KEY_THEME, THEME_SYSTEM)) {
            THEME_LIGHT -> R.id.rbThemeLight
            THEME_DARK -> R.id.rbThemeDark
            else -> R.id.rbThemeSystem
        }
        binding.rgTheme.check(checked)
    }

    private fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /** 「时间与地点」组：定位权限的当前状态直接写清，缺权限给出「去设置」。 */
    private fun refreshLocationState() {
        val fine = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val text = when {
            fine -> getString(R.string.settings_location_precise)
            coarse -> getString(R.string.settings_location_approximate)
            else -> getString(R.string.settings_location_denied)
        }
        binding.tvLocationState.text = text
        binding.btnLocationSettings.visibility = if (fine || coarse) View.GONE else View.VISIBLE
    }
}
