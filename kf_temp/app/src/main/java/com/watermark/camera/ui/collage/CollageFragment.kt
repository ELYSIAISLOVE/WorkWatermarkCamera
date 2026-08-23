package com.watermark.camera.ui.collage

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.watermark.camera.data.collage.CollageTemplate
import com.watermark.camera.databinding.FragmentCollageBinding
import com.watermark.camera.ui.common.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CollageFragment : BaseFragment<FragmentCollageBinding>() {

    private val viewModel: CollageViewModel by viewModels()

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
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
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnPick.setOnClickListener { photoPickerLauncher.launch("image/*") }
        binding.btnClear.setOnClickListener { viewModel.clearSelections() }
        binding.btnGenerate.setOnClickListener { viewModel.generateCollage() }

        binding.tpl2x2.setOnClickListener { viewModel.selectTemplate(CollageTemplate.Grid4) }
        binding.tpl1x2.setOnClickListener { viewModel.selectTemplate(CollageTemplate.Grid2) }
        binding.tpl2x1.setOnClickListener { viewModel.selectTemplate(CollageTemplate.Grid2) }
        binding.tpl3x1.setOnClickListener { viewModel.selectTemplate(CollageTemplate.VerticalLong) }
        binding.tpl1x3.setOnClickListener { viewModel.selectTemplate(CollageTemplate.Grid9) }
    }

    override fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.selectedTemplate.collect { t ->
                        binding.tvHint.text =
                            "模板: ${t.displayName}（最多 ${t.maxPhotos} 张）· 点选图添加"
                    }
                }
                launch {
                    viewModel.selectedPhotos.collect { photos ->
                        binding.btnGenerate.isEnabled = photos.isNotEmpty()
                        binding.tvHint.text =
                            "已选 ${photos.size} 张 · ${viewModel.selectedTemplate.value.displayName}"
                    }
                }
                launch {
                    viewModel.isGenerating.collect { gen ->
                        binding.progressBar.isVisible = gen
                        binding.btnGenerate.isEnabled =
                            !gen && viewModel.selectedPhotos.value.isNotEmpty()
                        binding.btnPick.isEnabled = !gen
                    }
                }
                launch {
                    viewModel.collageResult.collect { uri ->
                        uri?.let {
                            binding.ivPreview.setImageURI(it)
                            binding.ivPreview.isVisible = true
                            Toast.makeText(requireContext(), "拼图已生成", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                launch {
                    viewModel.errorMessage.collect { msg ->
                        msg?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    fun setPhotoPaths(paths: List<String>) {
        viewModel.setSelectedPhotos(paths)
    }
}
