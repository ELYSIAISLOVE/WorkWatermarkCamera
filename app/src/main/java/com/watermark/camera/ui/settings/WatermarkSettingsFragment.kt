package com.watermark.camera.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.data.model.TimeStyle
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
        runCatching { binding.sliderFontScale.visibility = android.view.View.GONE }
        runCatching { binding.tvFontScaleValue.visibility = android.view.View.GONE }
        setupTemplateSpinner()
        setupTemplateGrid()
        setupTimeStyleGrid()
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

    private fun runCatching { binding.sliderFontScale.visibility = android.view.View.GONE }
        runCatching { binding.tvFontScaleValue.visibility = android.view.View.GONE }
        setupTemplateSpinner() {
        val templates = WatermarkTemplate.menuEntries()
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


    private fun setupTemplateGrid() {
        val grid = binding.templateGrid
        grid.removeAllViews()
        val templates = WatermarkTemplate.menuEntries()
        val density = resources.displayMetrics.density
        val colCount = 4
        grid.columnCount = colCount
        templates.forEach { template ->
            val cell = TextView(requireContext()).apply {
                text = template.displayName
                setTextColor(template.textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = android.view.Gravity.CENTER
                setPadding(
                    (6 * density).toInt(),
                    (10 * density).toInt(),
                    (6 * density).toInt(),
                    (10 * density).toInt()
                )
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(template.backgroundColor)
                }
                background = bg
                minHeight = (56 * density).toInt()
                tag = template
                setOnClickListener {
                    viewModel.selectTemplate(template)
                    highlightTemplateGrid(template)
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
            grid.addView(cell, lp)
        }
        highlightTemplateGrid(viewModel.config.value.template)
    }

    private fun highlightTemplateGrid(selected: WatermarkTemplate) {
        val grid = binding.templateGrid
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i) as? TextView ?: continue
            val tpl = child.tag as? WatermarkTemplate ?: continue
            val density = resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(tpl.backgroundColor)
                if (tpl == selected) {
                    setStroke((3 * density).toInt(), 0xFFFFFFFF.toInt())
                } else {
                    setStroke((1 * density).toInt(), 0x33FFFFFF)
                }
            }
            child.background = bg
            child.alpha = if (tpl == selected) 1f else 0.85f
        }
    }

    private fun setupTimeStyleGrid() {
        val grid = binding.timeStyleGrid
        grid.removeAllViews()
        val styles = TimeStyle.entries.toList()
        val density = resources.displayMetrics.density
        grid.columnCount = 4
        val colors = intArrayOf(0xFF333333.toInt(), 0xFF0D47A1.toInt(), 0xFF4E342E.toInt(), 0xFF37474F.toInt())
        styles.forEachIndexed { index, style ->
            val cell = TextView(requireContext()).apply {
                text = style.displayName
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = android.view.Gravity.CENTER
                setPadding((4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt())
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(colors[index % colors.size])
                }
                background = bg
                minHeight = (48 * density).toInt()
                tag = style
                setOnClickListener {
                    viewModel.selectTimeStyle(style)
                    highlightTimeStyleGrid(style)
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
            grid.addView(cell, lp)
        }
        highlightTimeStyleGrid(viewModel.config.value.timeStyle)
    }

    private fun highlightTimeStyleGrid(selected: TimeStyle) {
        val grid = binding.timeStyleGrid
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i) as? TextView ?: continue
            val st = child.tag as? TimeStyle ?: continue
            child.alpha = if (st == selected) 1f else 0.75f
            child.paint.isFakeBoldText = st == selected
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
            binding.tvFontScaleValue.text = "2.5x"
            // Live preview on camera overlay (silent; also after auto-save)
            onConfigSaved?.invoke(config)

            // Update switch
            binding.switchShowLocation.isChecked = config.showLocation

            // Update spinners (avoid infinite loops by checking current selection)
            val templatePos = WatermarkTemplate.menuEntries().indexOf(config.template)
            if (templatePos >= 0 && binding.spinnerTemplate.selectedItemPosition != templatePos) {
                binding.spinnerTemplate.setSelection(templatePos)
            }
            runCatching { highlightTemplateGrid(config.template) }
            runCatching { highlightTimeStyleGrid(config.timeStyle) }

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

    override fun onDismiss(dialog: android.content.DialogInterface) {
        onConfigSaved?.invoke(viewModel.config.value)
        super.onDismiss(dialog)
    }

    override fun onPause() {
        // Flush text fields and silent save when leaving
        viewModel.setName(binding.etName.text?.toString() ?: "")
        viewModel.setProjectName(binding.etProjectName.text?.toString() ?: "")
        viewModel.setRemark(binding.etRemark.text?.toString() ?: "")
        super.onPause()
    }

}
