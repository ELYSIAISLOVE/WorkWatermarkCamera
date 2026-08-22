package com.watermark.camera.ui.collage

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.watermark.camera.data.collage.CollageTemplate
import com.watermark.camera.databinding.FragmentCollageBinding
import com.watermark.camera.ui.common.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Collage creation screen.
 *
 * Features:
 * - Template selection (2 / 4 / 9 / long)
 * - Photo selection (reserved for Step 12 multi-select PhotoPicker)
 * - Generation progress and result display
 * - Preview of generated collage
 *
 * Note: Multi-photo selection UI will be integrated in Step 12.
 *       Currently supports programmatic photo path injection for testing.
 */
@AndroidEntryPoint
class CollageFragment : BaseFragment<FragmentCollageBinding>() {

    private val viewModel: CollageViewModel by viewModels()

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importFromPicker(requireContext(), uris)
            Toast.makeText(requireContext(), "已选择 ${uris.size} 张", Toast.LENGTH_SHORT).show()
        }
    }

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCollageBinding = FragmentCollageBinding.inflate(inflater, container, false)

    override fun initViews() {
        setupTemplateSpinner()
        setupButtons()
    }

    override fun observeData() {
        observeState()
    }

    // region UI Setup

    private fun setupTemplateSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            CollageTemplate.ALL.map { it.displayName }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerTemplate.adapter = adapter
        binding.spinnerTemplate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    viewModel.selectTemplate(CollageTemplate.fromIndex(position))
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupButtons() {
        binding.btnGenerate.setOnClickListener {
            viewModel.generateCollage(
                projectText = binding.etProject.text?.toString() ?: ""
            )
        }

        binding.btnClear.setOnClickListener {
            viewModel.clearSelections()
        }

        binding.btnSelectPhotos.setOnClickListener {
            photoPickerLauncher.launch("image/*")
        }
    }

    // endregion

    // region State Observation

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeTemplate() }
                launch { observePhotos() }
                launch { observeGenerating() }
                launch { observeResult() }
                launch { observeError() }
            }
        }
    }

    private suspend fun observeTemplate() {
        viewModel.selectedTemplate.collect { template ->
            binding.tvTemplateInfo.text =
                "模板: ${template.displayName} (最多${template.maxPhotos}张)"
        }
    }

    private suspend fun observePhotos() {
        viewModel.selectedPhotos.collect { photos ->
            binding.tvPhotoCount.text = "已选择: ${photos.size} 张照片"
            binding.btnGenerate.isEnabled = photos.isNotEmpty()
        }
    }

    private suspend fun observeGenerating() {
        viewModel.isGenerating.collect { isGenerating ->
            binding.progressBar.visibility =
                if (isGenerating) View.VISIBLE else View.GONE
            binding.btnGenerate.isEnabled = !isGenerating
            binding.btnSelectPhotos.isEnabled = !isGenerating
        }
    }

    private suspend fun observeResult() {
        viewModel.collageResult.collect { uri ->
            uri?.let { displayResult(it) }
        }
    }

    private suspend fun observeError() {
        viewModel.errorMessage.collect { message ->
            message?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            }
        }
    }

    // endregion

    // region Result Display

    private fun displayResult(uri: Uri) {
        binding.ivPreview.setImageURI(uri)
        binding.ivPreview.visibility = View.VISIBLE
        binding.tvResultInfo.text = "拼图已生成"
        binding.tvResultInfo.visibility = View.VISIBLE
    }

    // endregion

    // region Public API (for Step 12 integration)

    /**
     * Inject photo paths from external source (e.g., Step 12 PhotoPicker).
     *
     * @param paths Absolute file paths of selected photos.
     */
    fun setPhotoPaths(paths: List<String>) {
        viewModel.setSelectedPhotos(paths)
    }

    // endregion
}
