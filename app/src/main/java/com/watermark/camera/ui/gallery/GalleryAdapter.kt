package com.watermark.camera.ui.gallery

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.databinding.ItemPhotoGridBinding

/**
 * RecyclerView adapter for the photo grid gallery.
 *
 * Supports:
 * - Single click: open photo detail
 * - Long click / check mark: multi-select mode for collage (Step 18)
 * - Selection indicator overlay
 */
class GalleryAdapter(
    private val onPhotoClick: (PhotoEntity) -> Unit,
    private val onPhotoLongClick: (PhotoEntity) -> Unit = {}
) : ListAdapter<PhotoEntity, GalleryAdapter.PhotoViewHolder>(DiffCallback()) {

    /** Currently selected URIs (for multi-select mode). */
    private var selectedUris: Set<String> = emptySet()

    /** Whether multi-select mode is active. */
    private var isMultiSelectMode: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Update selection state from ViewModel.
     */
    fun setSelectionState(selectedUris: Set<String>, isMultiSelectMode: Boolean) {
        this.selectedUris = selectedUris
        this.isMultiSelectMode = isMultiSelectMode
        notifyDataSetChanged()
    }

    inner class PhotoViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: PhotoEntity) {
            val uri = Uri.parse(photo.uri)

            // Load thumbnail using system URI (no external image library needed)
            binding.ivThumbnail.setImageURI(uri)
            binding.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP

            // Selection overlay
            val isSelected = selectedUris.contains(photo.uri)
            binding.viewSelectionOverlay.visibility =
                if (isMultiSelectMode && isSelected) View.VISIBLE else View.GONE
            binding.ivCheckMark.visibility =
                if (isMultiSelectMode && isSelected) View.VISIBLE else View.GONE

            // Dim unselected items in multi-select mode
            binding.ivThumbnail.alpha = if (isMultiSelectMode && !isSelected) 0.6f else 1.0f

            // Click handlers
            binding.root.setOnClickListener {
                if (isMultiSelectMode) {
                    onPhotoLongClick(photo)
                } else {
                    onPhotoClick(photo)
                }
            }

            binding.root.setOnLongClickListener {
                onPhotoLongClick(photo)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PhotoEntity>() {
        override fun areItemsTheSame(old: PhotoEntity, new: PhotoEntity): Boolean {
            return old.id == new.id
        }

        override fun areContentsTheSame(old: PhotoEntity, new: PhotoEntity): Boolean {
            return old == new
        }
    }
}
