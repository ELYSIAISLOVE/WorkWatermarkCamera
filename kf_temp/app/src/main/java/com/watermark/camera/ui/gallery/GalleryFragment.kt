package com.watermark.camera.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.watermark.camera.R
import com.watermark.camera.databinding.FragmentGalleryBinding
import com.watermark.camera.ui.common.BaseFragment
import com.watermark.camera.ui.detail.PhotoDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GalleryFragment : BaseFragment<FragmentGalleryBinding>() {

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)

    override fun initViews() {
        setupRecyclerView()
        setupButtons()
    }

    override fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.photos.collect { photos ->
                        adapter.submitList(photos)
                        binding.layoutEmpty.visibility =
                            if (photos.isEmpty()) View.VISIBLE else View.GONE
                        binding.tvPhotoCount.text = "共 ${photos.size} 张照片"
                    }
                }
                launch {
                    viewModel.isMultiSelectMode.collect { multi ->
                        binding.btnMultiSelect.text = if (multi) "取消" else "多选"
                        binding.layoutSelectionActions.visibility =
                            if (multi) View.VISIBLE else View.GONE
                        adapter.setSelectionState(
                            viewModel.selectedUris.value,
                            multi
                        )
                    }
                }
                launch {
                    viewModel.selectedUris.collect { selected ->
                        adapter.setSelectionState(selected, viewModel.isMultiSelectMode.value)
                        binding.tvSelectedCount.text = "已选 ${selected.size}"
                        binding.btnConfirmSelection.isEnabled = selected.isNotEmpty()
                        binding.btnDeleteSelected.isEnabled = selected.isNotEmpty()
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBar.visibility =
                            if (loading) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.event.collect { event ->
                        event?.let { handleEvent(it) }
                    }
                }
                launch {
                    viewModel.message.collect { msg ->
                        msg?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                            viewModel.consumeMessage()
                        }
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = GalleryAdapter(
            onPhotoClick = { photo ->
                if (viewModel.isMultiSelectMode.value) {
                    viewModel.toggleSelection(photo.uri)
                } else {
                    viewModel.openPhotoDetail(photo.uri)
                }
            },
            onPhotoLongClick = { photo ->
                if (!viewModel.isMultiSelectMode.value) {
                    viewModel.toggleMultiSelectMode()
                }
                viewModel.toggleSelection(photo.uri)
            }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnMultiSelect.setOnClickListener {
            viewModel.toggleMultiSelectMode()
        }
        binding.btnClearSelection.setOnClickListener {
            viewModel.clearSelection()
        }
        binding.btnConfirmSelection.setOnClickListener {
            viewModel.sendSelectionToCollage()
        }
        binding.btnDeleteSelected.setOnClickListener {
            val n = viewModel.selectedUris.value.size
            if (n <= 0) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("删除照片")
                .setMessage("确定删除选中的 $n 张照片？此操作不可恢复。")
                .setPositiveButton("删除") { _, _ -> viewModel.deleteSelected() }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.btnRefresh.setOnClickListener {
            viewModel.refresh()
        }
    }

    private fun handleEvent(event: GalleryEvent) {
        when (event) {
            is GalleryEvent.NavigateToDetail -> {
                val detail = PhotoDetailFragment.newInstance(event.uri, ArrayList(event.allUris))
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, detail)
                    .addToBackStack("detail")
                    .commit()
                viewModel.consumeEvent()
            }
            is GalleryEvent.SendToCollage -> {
                val collage = com.watermark.camera.ui.collage.CollageFragment().apply {
                    setPhotoPaths(event.uris)
                }
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, collage)
                    .addToBackStack("collage")
                    .commit()
                viewModel.consumeEvent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

}
