package com.watermark.camera.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.databinding.FragmentGalleryBinding
import com.watermark.camera.ui.common.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Gallery screen displaying all watermarked photos in a grid.
 *
 * Features:
 * - Grid layout (3 columns) of all indexed photos
 * - Tap to open photo detail (Step 13)
 * - Long press / multi-select mode for collage (Step 18 integration)
 * - Empty state when no photos
 * - Photo count display
 */
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
                launch { observePhotos() }
                launch { observeMultiSelect() }
                launch { observeSelectedUris() }
                launch { observeLoading() }
                launch { observeEvents() }
            }
        }
    }

    // region Setup

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
        binding.btnMultiSelect.setOnClickListener {
            viewModel.toggleMultiSelectMode()
        }

        binding.btnSelectAll.setOnClickListener {
            viewModel.selectAll()
        }

        binding.btnClearSelection.setOnClickListener {
            viewModel.clearSelection()
        }

        binding.btnConfirmSelection.setOnClickListener {
            viewModel.confirmSelectionForCollage()
        }
    }

    // endregion

    // region State Observation

    private suspend fun observePhotos() {
        viewModel.photos.collect { photos ->
            adapter.submitList(photos)
            updateEmptyState(photos)
            binding.tvPhotoCount.text = "共 ${photos.size} 张照片"
        }
    }

    private suspend fun observeMultiSelect() {
        viewModel.isMultiSelectMode.collect { isMultiSelect ->
            binding.btnMultiSelect.text = if (isMultiSelect) "退出多选" else "多选"
            binding.layoutSelectionActions.visibility =
                if (isMultiSelect) View.VISIBLE else View.GONE
        }
    }

    private suspend fun observeSelectedUris() {
        viewModel.selectedUris.collect { selected ->
            adapter.setSelectionState(selected, viewModel.isMultiSelectMode.value)
            binding.tvSelectedCount.text = "已选择 ${selected.size} 张"
            binding.btnConfirmSelection.isEnabled = selected.isNotEmpty()
        }
    }

    private suspend fun observeLoading() {
        viewModel.isLoading.collect { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private suspend fun observeEvents() {
        viewModel.event.collect { event ->
            event?.let { handleEvent(it) }
        }
    }

    // endregion

    // region Event Handling

    private fun handleEvent(event: GalleryEvent) {
        when (event) {
            is GalleryEvent.NavigateToDetail -> {
                // Navigate to PhotoDetailFragment
                val detailFragment = com.watermark.camera.ui.detail.PhotoDetailFragment.newInstance(event.uri)
                parentFragmentManager.beginTransaction()
                    .replace(com.watermark.camera.R.id.nav_host_fragment, detailFragment)
                    .addToBackStack(null)
                    .commit()
                viewModel.consumeEvent()
            }
            is GalleryEvent.SendToCollage -> {
                // Navigate to CollageFragment with selected URIs
                val collageFragment = com.watermark.camera.ui.collage.CollageFragment().apply {
                    setPhotoPaths(event.uris)
                }
                parentFragmentManager.beginTransaction()
                    .replace(com.watermark.camera.R.id.nav_host_fragment, collageFragment)
                    .addToBackStack(null)
                    .commit()
                viewModel.consumeEvent()
            }
        }
    }

    // endregion

    // region UI Helpers

    private fun updateEmptyState(photos: List<PhotoEntity>) {
        val isEmpty = photos.isEmpty()
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    // endregion
}
