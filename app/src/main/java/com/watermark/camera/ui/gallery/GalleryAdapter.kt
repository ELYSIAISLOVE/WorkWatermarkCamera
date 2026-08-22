package com.watermark.camera.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.watermark.camera.data.local.PhotoEntity
import com.watermark.camera.databinding.ItemPhotoGridBinding
import java.util.concurrent.Executors

/**
 * Gallery grid adapter with background thumbnail decoding (avoids full-res setImageURI jank).
 */
class GalleryAdapter(
    private val onPhotoClick: (PhotoEntity) -> Unit,
    private val onPhotoLongClick: (PhotoEntity) -> Unit = {}
) : ListAdapter<PhotoEntity, GalleryAdapter.PhotoViewHolder>(DiffCallback()) {

    private var selectedUris: Set<String> = emptySet()
    private var isMultiSelectMode: Boolean = false

    companion object {
        private val decodeExecutor = Executors.newFixedThreadPool(2)
        private val mainHandler = Handler(Looper.getMainLooper())
        private const val THUMB = 256
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    fun setSelectionState(selectedUris: Set<String>, isMultiSelectMode: Boolean) {
        this.selectedUris = selectedUris
        this.isMultiSelectMode = isMultiSelectMode
        notifyDataSetChanged()
    }

    inner class PhotoViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundUri: String? = null

        fun recycle() {
            boundUri = null
            binding.ivThumbnail.setImageDrawable(null)
        }

        fun bind(photo: PhotoEntity) {
            val uriString = photo.uri
            boundUri = uriString
            binding.ivThumbnail.setImageDrawable(null)
            binding.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.ivThumbnail.tag = uriString

            val isSelected = selectedUris.contains(uriString)
            binding.viewSelectionOverlay.visibility =
                if (isMultiSelectMode && isSelected) View.VISIBLE else View.GONE
            binding.ivCheckMark.visibility =
                if (isMultiSelectMode && isSelected) View.VISIBLE else View.GONE
            binding.ivThumbnail.alpha = if (isMultiSelectMode && !isSelected) 0.6f else 1.0f

            binding.root.setOnClickListener {
                if (isMultiSelectMode) onPhotoLongClick(photo) else onPhotoClick(photo)
            }
            binding.root.setOnLongClickListener {
                onPhotoLongClick(photo)
                true
            }

            val appContext = binding.root.context.applicationContext
            decodeExecutor.execute {
                val bmp = loadThumb(appContext, uriString)
                mainHandler.post {
                    if (binding.ivThumbnail.tag == uriString && boundUri == uriString && bmp != null) {
                        binding.ivThumbnail.setImageBitmap(bmp)
                    }
                }
            }
        }

        private fun loadThumb(context: android.content.Context, uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(THUMB, THUMB), null)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val sample = calcSample(bounds, THUMB)
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun calcSample(opts: BitmapFactory.Options, maxSize: Int): Int {
            var sample = 1
            var w = opts.outWidth
            var h = opts.outHeight
            while (w / sample > maxSize || h / sample > maxSize) {
                sample *= 2
            }
            return sample.coerceAtLeast(1)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PhotoEntity>() {
        override fun areItemsTheSame(old: PhotoEntity, new: PhotoEntity): Boolean = old.uri == new.uri
        override fun areContentsTheSame(old: PhotoEntity, new: PhotoEntity): Boolean = old == new
    }
}
