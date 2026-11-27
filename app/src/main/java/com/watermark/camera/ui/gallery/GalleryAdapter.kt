package com.watermark.camera.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.watermark.camera.R
import com.watermark.camera.databinding.ItemGalleryHeaderBinding
import com.watermark.camera.databinding.ItemPhotoGridBinding

/**
 * 相册网格适配器：日期分组头 + 照片。
 *
 * 相比旧实现（审计 C6）：
 * - 缩略图走 [ThumbnailLoader]，带 LRU 缓存与按条目取消，不再闪烁与重复解码；
 * - 不再先 `setImageDrawable(null)` 再异步填充；
 * - 分组头通过 [spanSizeLookup] 占满整行。
 */
class GalleryAdapter(
    private val onPhotoClick: (String) -> Unit
) : ListAdapter<GalleryItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1

        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_SIZE = "size"
    }

    /** 所有照片都按正方形展示，列数变化时据此设置格子高度。 */
    var cellSizePx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SIZE)
        }

    private var multiSelect = false

    /** 供 GridLayoutManager 使用：分组头占满一行。 */
    fun spanSizeLookup(spanCount: Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int = when (getItemViewType(position)) {
            TYPE_HEADER -> spanCount
            else -> 1
        }
    }

    fun setMultiSelect(enabled: Boolean) {
        if (multiSelect == enabled) return
        multiSelect = enabled
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is GalleryItem.Header -> TYPE_HEADER
        is GalleryItem.Photo -> TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemGalleryHeaderBinding.inflate(inflater, parent, false))
        } else {
            PhotoHolder(ItemPhotoGridBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GalleryItem.Header -> (holder as HeaderHolder).bind(item)
            is GalleryItem.Photo -> (holder as PhotoHolder).bind(item, multiSelect, cellSizePx)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        if (holder is PhotoHolder) {
            val item = getItem(position) as? GalleryItem.Photo ?: return
            if (payloads.contains(PAYLOAD_SIZE)) {
                holder.applyCellSize(cellSizePx)
            }
            if (payloads.contains(PAYLOAD_SELECTION)) {
                holder.applySelection(item, multiSelect)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is PhotoHolder) holder.release()
        super.onViewRecycled(holder)
    }

    // region ViewHolders

    private class HeaderHolder(
        private val binding: ItemGalleryHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GalleryItem.Header) {
            binding.tvHeaderLabel.text = item.label
            binding.tvHeaderCount.text = "${item.count} 张"
        }
    }

    private inner class PhotoHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundUri: String? = null

        fun bind(item: GalleryItem.Photo, multiSelect: Boolean, cellSize: Int) {
            boundUri = item.entity.uri
            binding.ivThumbnail.setImageDrawable(null)
            applyCellSize(cellSize)
            applySelection(item, multiSelect)

            val uri = item.entity.uri
            ThumbnailLoader.load(binding.root.context, uri) { bitmap ->
                // 回调回来时该 holder 可能已绑定别的照片
                if (boundUri == uri) {
                    binding.ivThumbnail.setImageBitmap(bitmap)
                }
            }

            binding.root.setOnClickListener { onPhotoClick(uri) }
            binding.root.setOnLongClickListener {
                onPhotoClick(uri)
                true
            }
        }

        fun applyCellSize(cellSize: Int) {
            if (cellSize <= 0) return
            for (v in listOf(binding.ivThumbnail, binding.viewSelectionFrame, binding.viewSelectionOverlay)) {
                val lp = v.layoutParams
                if (lp.height != cellSize) {
                    lp.height = cellSize
                    v.layoutParams = lp
                }
            }
        }

        fun applySelection(item: GalleryItem.Photo, multiSelect: Boolean) {
            val selected = multiSelect && item.selected
            binding.viewSelectionFrame.visibility = if (selected) View.VISIBLE else View.GONE
            binding.viewSelectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            binding.tvSelectionIndex.visibility = if (selected) View.VISIBLE else View.GONE
            if (selected) {
                (binding.tvSelectionIndex as TextView).text = item.selectionIndex.toString()
            }
            binding.ivThumbnail.alpha = if (multiSelect && !selected) 0.55f else 1f
        }

        fun release() {
            boundUri?.let { ThumbnailLoader.cancel(it) }
            boundUri = null
            binding.ivThumbnail.setImageDrawable(null)
        }
    }

    // endregion

    private class DiffCallback : DiffUtil.ItemCallback<GalleryItem>() {
        override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean =
            when {
                oldItem is GalleryItem.Header && newItem is GalleryItem.Header ->
                    oldItem.key == newItem.key
                oldItem is GalleryItem.Photo && newItem is GalleryItem.Photo ->
                    oldItem.entity.uri == newItem.entity.uri
                else -> false
            }

        override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean =
            oldItem == newItem
    }
}