package com.watermark.camera.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.databinding.FragmentWatermarkSettingsBinding
import com.watermark.camera.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Watermark settings BottomSheet dialog.
 *
 * Allows user to customize:
 * - Template style (Duty / Engineering / Attendance / General)
 * - Custom fields (name, project name, remark)
 * - Watermark position (top-left / top-right / bottom-left / bottom-right / center)
 * - Transparency (0.3 - 1.0)
 * - Font scale (0.5 - 1.5)
 * - Show location toggle
 *
 * Changes are saved to SharedPreferences via WatermarkConfigDataSource.
 */
@AndroidEntryPoint
class WatermarkSettingsFragment : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "WatermarkSettingsFrag"
        fun newInstance() = WatermarkSettingsFragment()
    }

    private var _binding: FragmentWatermarkSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WatermarkSettingsViewModel by viewModels()

    /** Callback when config is saved successfully. */
    var onConfigSaved: ((WatermarkConfig) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWatermarkSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTemplateSpinner()
        setupPositionSpinner()
        setupSliders()
        setupSwitches()
        setupButtons()
        observeState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // region UI Setup

    private fun setupTemplateSpinner() {
        val templates = WatermarkTemplate.entries.toList()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            templates.map { "${it.displayName} — ${it.description}" }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerTemplate.adapter = adapter
        binding.spinnerTemplate.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    viewModel.selectTemplate(templates[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun setupPositionSpinner() {
        val positions = WatermarkPosition.entries.toList()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            positions.map {
                when (it) {
                    WatermarkPosition.TOP_LEFT -> "左上"
                    WatermarkPosition.TOP_RIGHT -> "右上"
                    WatermarkPosition.BOTTOM_LEFT -> "左下"
                    WatermarkPosition.BOTTOM_RIGHT -> "右下"
                    WatermarkPosition.CENTER -> "居中"
                }
            }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerPosition.adapter = adapter
        binding.spinnerPosition.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    viewModel.setPosition(positions[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun setupSliders() {
        binding.sliderTransparency.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setTransparency(value)
                binding.tvTransparencyValue.text = String.format("%.0f%%", value * 100)
            }
        }

        binding.sliderFontScale.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setFontScale(value)
                binding.tvFontScaleValue.text = String.format("%.1fx", value)
            }
        }
    }

    private fun setupSwitches() {
        binding.switchShowLocation.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowLocation(isChecked)
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            viewModel.setName(binding.etName.text?.toString() ?: "")
            viewModel.setProjectName(binding.etProjectName.text?.toString() ?: "")
            viewModel.setRemark(binding.etRemark.text?.toString() ?: "")
            viewModel.saveConfig()
        }

        binding.btnReset.setOnClickListener {
            viewModel.resetToDefault()
            Toast.makeText(requireContext(), "已恢复默认设置", Toast.LENGTH_SHORT).show()
        }
    }

    // endregion

    // region State Observation

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeConfig() }
                launch { observeSaving() }
                launch { observeSaveSuccess() }
                launch { observeError() }
            }
        }
    }

    private suspend fun observeConfig() {
        viewModel.config.collect { config ->
            // Update text fields only if not focused (avoid cursor jumping)
            if (binding.etName.isFocused.not()) {
                binding.etName.setText(config.name)
            }
            if (binding.etProjectName.isFocused.not()) {
                binding.etProjectName.setText(config.projectName)
            }
            if (binding.etRemark.isFocused.not()) {
                binding.etRemark.setText(config.remark)
            }

            // Update sliders
            binding.sliderTransparency.value = config.transparency
            binding.tvTransparencyValue.text = String.format("%.0f%%", config.transparency * 100)

            binding.sliderFontScale.value = config.fontScale
            binding.tvFontScaleValue.text = String.format("%.1fx", config.fontScale)

            // Update switch
            binding.switchShowLocation.isChecked = config.showLocation

            // Update spinners (avoid infinite loops by checking current selection)
            val templatePos = WatermarkTemplate.entries.indexOf(config.template)
            if (binding.spinnerTemplate.selectedItemPosition != templatePos && templatePos >= 0) {
                binding.spinnerTemplate.setSelection(templatePos)
            }

            val positionPos = WatermarkPosition.entries.indexOf(config.position)
            if (binding.spinnerPosition.selectedItemPosition != positionPos && positionPos >= 0) {
                binding.spinnerPosition.setSelection(positionPos)
            }
        }
    }

    private suspend fun observeSaving() {
        viewModel.isSaving.collect { isSaving ->
            binding.btnSave.isEnabled = !isSaving
            binding.progressBar.visibility = if (isSaving) View.VISIBLE else View.GONE
        }
    }

    private suspend fun observeSaveSuccess() {
        viewModel.saveSuccess.collect { success ->
            if (success) {
                Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show()
                onConfigSaved?.invoke(viewModel.config.value)
                viewModel.consumeSaveSuccess()
                dismiss()
            }
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

    // endregion

    // region Text Watchers (two-way binding)

    override fun onStart() {
        super.onStart()
        // Add text watchers after view is created to avoid triggering during init
        binding.etName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setName(binding.etName.text?.toString() ?: "")
        }
        binding.etProjectName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setProjectName(binding.etProjectName.text?.toString() ?: "")
        }
        binding.etRemark.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setRemark(binding.etRemark.text?.toString() ?: "")
        }
    }

    // endregion
}
