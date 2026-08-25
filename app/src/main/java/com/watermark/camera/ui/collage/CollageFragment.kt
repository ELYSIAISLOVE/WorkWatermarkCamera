package com.watermark.camera.ui.collage

import com.watermark.camera.util.ViewAnim

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.watermark.camera.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collage: top template row, middle preview/slots, bottom 清空 / 选图 / 生成.
 */
class CollageFragment : Fragment() {

    private var photoUris: MutableList<String> = mutableListOf()
    private var template: Template = Template.GRID_2X2
    private var resultBitmap: Bitmap? = null

    private var tvCount: TextView? = null
    private var ivPreview: ImageView? = null
    private var progress: ProgressBar? = null
    private var btnGenerate: Button? = null
    private var recycler: RecyclerView? = null
    private var adapter: UriAdapter? = null

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val cap = template.capacity.coerceAtMost(9)
        for (u in uris) {
            if (photoUris.size >= cap) break
            val s = u.toString()
            if (s !in photoUris) photoUris.add(s)
        }
        // Trim if template was switched to smaller grid
        if (photoUris.size > cap) {
            photoUris = photoUris.take(cap).toMutableList()
        }
        refreshUi()
    }

    enum class Template(val rows: Int, val cols: Int, val label: String) {
        GRID_2X2(2, 2, "2×2"),
        GRID_3X3(3, 3, "3×3"),
        GRID_4X4(4, 4, "4×4"),
        ROW_1X3(1, 3, "1×3"),
        COL_3X1(3, 1, "3×1"),
        ROW_1X2(1, 2, "1×2"),
        COL_2X1(2, 1, "2×1");

        val capacity: Int get() = rows * cols
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromArgs = arguments?.getStringArrayList(ARG_URIS)
        if (!fromArgs.isNullOrEmpty()) {
            photoUris = fromArgs.take(9).toMutableList()
        }
    }

    fun setPhotoPaths(paths: List<String>) {
        photoUris = paths.take(9).toMutableList()
        // Also stash into arguments so recreate survives
        if (arguments == null) arguments = Bundle()
        arguments?.putStringArrayList(ARG_URIS, ArrayList(photoUris))
        if (isAdded) refreshUi()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_collage, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
        tvCount = view.findViewById(R.id.tvHint)
        ivPreview = view.findViewById(R.id.ivPreview)
        progress = view.findViewById(R.id.progressBar)
        btnGenerate = view.findViewById(R.id.btnGenerate)
        recycler = view.findViewById(R.id.recyclerSelected)

        adapter = UriAdapter(photoUris) { idx ->
            if (idx in photoUris.indices) {
                photoUris.removeAt(idx)
                refreshUi()
            }
        }
        recycler?.layoutManager = GridLayoutManager(requireContext(), 4)
        recycler?.adapter = adapter

        bindTemplateButtons(view)

        view.findViewById<Button>(R.id.btnClear)?.setOnClickListener {
            photoUris.clear()
            resultBitmap?.recycle()
            resultBitmap = null
            ivPreview?.setImageDrawable(null)
            refreshUi()
        }
        view.findViewById<Button>(R.id.btnPick)?.setOnClickListener {
            pickImages.launch("image/*")
        }
        btnGenerate?.setOnClickListener { generate() }

        refreshUi()
        } catch (e: Exception) {
            android.util.Log.e("CollageFragment", "onViewCreated", e)
            android.widget.Toast.makeText(requireContext(), "拼图界面异常: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun bindTemplateButtons(view: View) {
        val map = listOf(
            R.id.tpl2x2 to Template.GRID_2X2,
            R.id.tpl3x3 to Template.GRID_3X3,
            R.id.tpl4x4 to Template.GRID_4X4,
            R.id.tpl1x3 to Template.ROW_1X3,
            R.id.tpl3x1 to Template.COL_3X1,
            R.id.tpl1x2 to Template.ROW_1X2,
            R.id.tpl2x1 to Template.COL_2X1
        )
        for ((id, tpl) in map) {
            view.findViewById<Button>(id)?.setOnClickListener {
                template = tpl
                refreshUi()
            }
        }
    }

    private fun refreshUi() {
        adapter?.notifyDataSetChanged()
        val cap = template.capacity
        tvCount?.text = "已选 ${photoUris.size} 张 · ${template.label}（最多 $cap 张）"
        btnGenerate?.isEnabled = photoUris.isNotEmpty()
    }

    private fun generate() {
        if (photoUris.isEmpty()) {
            Toast.makeText(requireContext(), "请先选图", Toast.LENGTH_SHORT).show()
            return
        }
        progress?.visibility = View.VISIBLE
        btnGenerate?.isEnabled = false
        val uris = photoUris.toList()
        val tpl = template
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                runCatching { buildCollage(uris, tpl) }.getOrNull()
            }
            progress?.visibility = View.GONE
            btnGenerate?.isEnabled = true
            if (bmp == null) {
                Toast.makeText(requireContext(), "拼图失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            resultBitmap?.recycle()
            resultBitmap = bmp
            ivPreview?.setImageBitmap(bmp)
            Toast.makeText(requireContext(), "拼图完成（预览）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildCollage(uris: List<String>, tpl: Template): Bitmap {
        val cell = 512
        val rows = tpl.rows
        val cols = tpl.cols
        val out = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 4
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val n = minOf(uris.size, tpl.capacity)
        for (i in 0 until n) {
            val r = i / cols
            val c = i % cols
            val uri = Uri.parse(uris[i])
            val src = try {
                requireContext().contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (_: Exception) {
                null
            } ?: continue
            val dest = Rect(c * cell, r * cell, (c + 1) * cell, (r + 1) * cell)
            // center-crop
            val scale = maxOf(cell.toFloat() / src.width, cell.toFloat() / src.height)
            val sw = (cell / scale).toInt().coerceAtLeast(1)
            val sh = (cell / scale).toInt().coerceAtLeast(1)
            val sx = ((src.width - sw) / 2).coerceAtLeast(0)
            val sy = ((src.height - sh) / 2).coerceAtLeast(0)
            val srcRect = Rect(sx, sy, sx + sw, sy + sh)
            canvas.drawBitmap(src, srcRect, dest, null)
            src.recycle()
        }
        return out
    }

    private class UriAdapter(
        private val items: List<String>,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<UriAdapter.VH>() {
        class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(200, 200).apply {
                    setMargins(6, 6, 6, 6)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF2A2A2A.toInt())
            }
            return VH(iv)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = Uri.parse(items[position])
            holder.iv.setImageURI(uri)
            holder.iv.setOnLongClickListener {
                // Use adapterPosition for broader RecyclerView version compatibility
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onRemove(pos)
                }
                true
            }
        }
    }

    companion object {
        private const val ARG_URIS = "collage_uris"

        fun newInstance(uris: List<String>): CollageFragment {
            return CollageFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_URIS, ArrayList(uris.take(9)))
                }
            }
        }
    }
}
