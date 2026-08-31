package com.watermark.camera.ui.collage

import com.watermark.camera.util.ViewAnim

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Paint
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.provider.MediaStore
import android.os.Environment
import android.os.Build
import android.content.ContentValues
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var template: Template = Template.GRID_3XN
    private var resultBitmap: Bitmap? = null
    private var reportTitle: String = "白班打点"
    private var reportSubtitle: String = "工作现场照片汇总整理"
    private var reporterName: String = "—"
    /** 标题等文字倍率，默认 1.2 */
    private var reportTextScale: Float = 1.2f
    private var tvEmptyHint: TextView? = null

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
        val cap = template.capacity
        for (u in uris) {
            if (photoUris.size >= cap) break
            val s = u.toString()
            if (s !in photoUris) photoUris.add(s)
        }
        if (photoUris.size > cap) {
            photoUris = photoUris.take(cap).toMutableList()
        }
        refreshUi()
        autoPreview()
    }

        enum class Template(val cols: Int, val fixedRows: Int?, val label: String, val capacity: Int) {
        VERTICAL(1, null, "竖向", 30),
        GRID_2X2(2, 2, "2×2", 4),
        GRID_3XN(3, null, "3×N", 30);

        fun rowsFor(count: Int): Int {
            val n = count.coerceAtLeast(1)
            return fixedRows ?: ((n + cols - 1) / cols)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromArgs = arguments?.getStringArrayList(ARG_URIS)
        if (!fromArgs.isNullOrEmpty()) {
            photoUris = fromArgs.take(30).toMutableList()
        }
    }

    fun setPhotoPaths(paths: List<String>) {
        photoUris = paths.take(30).toMutableList()
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
        applySystemBarInsets(view)

        super.onViewCreated(view, savedInstanceState)
        try {
        tvCount = view.findViewById(R.id.tvHint)
        ivPreview = view.findViewById(R.id.ivPreview)
        progress = view.findViewById(R.id.progressBar)
        btnGenerate = view.findViewById(R.id.btnGenerate)
        tvEmptyHint = view.findViewById(R.id.tvEmptyHint)
        // 排版按钮
        bindTemplateButtons(view)
        val seek = view.findViewById<android.widget.SeekBar>(R.id.seekTextScale)
        val tvScale = view.findViewById<TextView>(R.id.tvTextScale)
        // progress 0..100 -> scale 1.0 .. 2.0, default 1.2 -> progress 20 mapped? 1.0+progress/50 -> 1.2 at 10
        // use 1.0 + progress/100*1.0 => 1.0-2.0, default 1.2 => progress 20
        seek?.progress = ((reportTextScale - 1.0f) * 100f).toInt().coerceIn(0, 100)
        tvScale?.text = String.format("%.1fx", reportTextScale)
        seek?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                reportTextScale = 1.0f + progress / 100f
                tvScale?.text = String.format("%.1fx", reportTextScale)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { autoPreview() }
        })

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
        btnGenerate?.setOnClickListener { saveCollage() }
        view.findViewById<View>(R.id.btnEditTitle)?.setOnClickListener {
            promptEdit("修改标题", reportTitle) { reportTitle = it; autoPreview() }
        }
        view.findViewById<View>(R.id.btnEditSubtitle)?.setOnClickListener {
            promptEdit("修改副标题", reportSubtitle) { reportSubtitle = it; autoPreview() }
        }
        view.findViewById<View>(R.id.btnEditReporter)?.setOnClickListener {
            promptEdit("修改汇报人", if (reporterName == "—") "" else reporterName) {
                reporterName = it.ifBlank { "—" }
                autoPreview()
            }
        }

        refreshUi()
        if (photoUris.isNotEmpty()) autoPreview()
        } catch (e: Exception) {
            android.util.Log.e("CollageFragment", "onViewCreated", e)
            android.widget.Toast.makeText(requireContext(), "拼图界面异常: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun bindTemplateButtons(view: View) {
        val map = listOf(
            R.id.tplVertical to Template.VERTICAL,
            R.id.tpl2x2 to Template.GRID_2X2,
            R.id.tpl3xN to Template.GRID_3XN
        )
        for ((id, tpl) in map) {
            view.findViewById<Button>(id)?.setOnClickListener {
                template = tpl
                if (photoUris.size > tpl.capacity) {
                    photoUris = photoUris.take(tpl.capacity).toMutableList()
                }
                refreshUi()
                autoPreview()
            }
        }
    }

    private fun refreshUi() {
        adapter?.notifyDataSetChanged()
        val cap = template.capacity
        tvCount?.text = "已选 ${photoUris.size}/${template.capacity} 张 · ${template.label} · 点上方可改标题/汇报人"
        btnGenerate?.isEnabled = photoUris.isNotEmpty()
        tvEmptyHint?.visibility = if (photoUris.isEmpty() && resultBitmap == null) View.VISIBLE else View.GONE
    }

    private fun autoPreview() {
        if (photoUris.isEmpty()) return
        generate(showToast = false)
    }

    private fun generate(showToast: Boolean = false) {
        if (photoUris.isEmpty()) {
            if (showToast) Toast.makeText(requireContext(), "请先选图", Toast.LENGTH_SHORT).show()
            return
        }
        progress?.visibility = View.VISIBLE
        btnGenerate?.isEnabled = false
        val uris = photoUris.toList()
        val tpl = template
        val title = reportTitle
        val sub = reportSubtitle
        val reporter = reporterName
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                runCatching { buildCollage(uris, tpl, title, sub, reporter) }.getOrNull()
            }
            progress?.visibility = View.GONE
            btnGenerate?.isEnabled = photoUris.isNotEmpty()
            if (bmp == null) {
                Toast.makeText(requireContext(), "拼图失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            resultBitmap?.recycle()
            resultBitmap = bmp
            ivPreview?.setImageBitmap(bmp)
            tvEmptyHint?.visibility = View.GONE
            // 仅重要提示保留（保存成功/失败）
        }
    }

    private fun promptEdit(title: String, current: String, onOk: (String) -> Unit) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
        }
        val et = EditText(ctx).apply {
            setText(current)
            setSelection(text.length)
            setSingleLine(true)
            hint = title
        }
        box.addView(et)
        val scaleLabel = TextView(ctx).apply {
            text = String.format("文字大小 %.1fx", reportTextScale)
            setTextColor(0xFF666666.toInt())
            textSize = 13f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        box.addView(scaleLabel)
        val seek = android.widget.SeekBar(ctx).apply {
            max = 100
            progress = ((reportTextScale - 1.0f) * 100f).toInt().coerceIn(0, 100)
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                reportTextScale = 1.0f + progress / 100f
                scaleLabel.text = String.format("文字大小 %.1fx", reportTextScale)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        box.addView(seek)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ -> onOk(et.text?.toString()?.trim().orEmpty()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveCollage() {
        val bmp = resultBitmap
        if (bmp == null) {
            if (photoUris.isEmpty()) {
                Toast.makeText(requireContext(), "请先选图", Toast.LENGTH_SHORT).show()
            } else {
                generate(showToast = false)
                Toast.makeText(requireContext(), "正在生成，请稍后再点保存", Toast.LENGTH_SHORT).show()
            }
            return
        }
        progress?.visibility = View.VISIBLE
        btnGenerate?.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { saveBitmapToGallery(bmp) }.getOrElse { e ->
                    android.util.Log.e("CollageFragment", "save", e)
                    null
                }
            }
            progress?.visibility = View.GONE
            btnGenerate?.isEnabled = true
            if (ok != null) {
                Toast.makeText(requireContext(), "已保存到相册", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): android.net.Uri {
        val name = "collage_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/WatermarkCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = requireContext().contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建相册条目")
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)) {
                throw IllegalStateException("压缩失败")
            }
        } ?: throw IllegalStateException("无法写入文件")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun buildCollage(
        uris: List<String>,
        tpl: Template,
        title: String = reportTitle,
        subtitle: String = reportSubtitle,
        reporter: String = reporterName
    ): Bitmap {
        // 贴边拼图：格比例跟第一张；缩放放入格内不丢内容；高质量
        val pageW = 2400
        val edge = 2 // 照片贴边，缝隙极小
        val headerH = (280 + 80 * (reportTextScale - 1f).coerceIn(0f, 1.2f)).toInt()
        val footerH = 140
        val n = minOf(uris.size, tpl.capacity).coerceAtLeast(0)
        if (n == 0) {
            return Bitmap.createBitmap(pageW, headerH + footerH, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawColor(Color.WHITE)
            }
        }

        // 第一张尺寸 → 统一格子比例
        val firstBounds = decodeBounds(uris[0])
        val aspect = if (firstBounds != null && firstBounds.first > 0) {
            firstBounds.second.toFloat() / firstBounds.first.toFloat()
        } else {
            0.75f
        }.coerceIn(0.4f, 2.5f)

        val cols = tpl.cols
        val rows = tpl.rowsFor(n)
        val contentW = pageW
        val cellW = (contentW - edge * (cols - 1)) / cols
        val cellH = maxOf(1, (cellW * aspect).toInt())
        val gridH = rows * cellH + (rows - 1).coerceAtLeast(0) * edge
        val pageH = headerH + gridH + footerH

        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        // Header
        paint.color = 0xFF2F7BFF.toInt()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val ts = reportTextScale.coerceIn(1.0f, 2.0f)
        paint.textSize = 64f * ts
        val titleY = 70f + 40f * ts
        canvas.drawText(title.ifBlank { "白班打点" }, pageW / 2f, titleY, paint)
        paint.textSize = 28f * ts
        paint.typeface = Typeface.DEFAULT
        paint.color = 0xFF5B8DEF.toInt()
        canvas.drawText(subtitle.ifBlank { "工作现场照片汇总整理" }, pageW / 2f, titleY + 36f * ts, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 26f * ts
        paint.color = 0xFF333333.toInt()
        val dateStr = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
        val margin = 28
        canvas.drawText("汇报人: ${reporter.ifBlank { "—" }}", margin.toFloat(), 190f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dateStr, (pageW - margin).toFloat(), 190f, paint)
        paint.color = 0xFFE8EEF8.toInt()
        paint.strokeWidth = 2f
        canvas.drawLine(margin.toFloat(), 220f, (pageW - margin).toFloat(), 220f, paint)

        val gridTop = headerH
        // 解码目标边长：保证清晰可辨
        val decodeSide = maxOf(cellW, cellH) * 2

        for (i in 0 until n) {
            val r = i / cols
            val c = i % cols
            val left = c * (cellW + edge)
            val top = gridTop + r * (cellH + edge)
            val src = decodeUri(uris[i], decodeSide) ?: continue
            try {
                // 按第一张比例的格子：等比完整放入（不丢内容），居中，可有极少留白
                val scale = minOf(cellW.toFloat() / src.width, cellH.toFloat() / src.height)
                val dw = maxOf(1, (src.width * scale).toInt())
                val dh = maxOf(1, (src.height * scale).toInt())
                val dx = left + (cellW - dw) / 2
                val dy = top + (cellH - dh) / 2
                // 格底浅灰，便于辨认边界
                paint.style = Paint.Style.FILL
                paint.color = 0xFFF3F5F8.toInt()
                canvas.drawRect(left.toFloat(), top.toFloat(), (left + cellW).toFloat(), (top + cellH).toFloat(), paint)
                val dest = Rect(dx, dy, dx + dw, dy + dh)
                canvas.drawBitmap(src, null, dest, paint)
            } finally {
                src.recycle()
            }
        }

        // Footer
        val fy = pageH - footerH
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF7F9FC.toInt()
        canvas.drawRect(0f, fy.toFloat(), pageW.toFloat(), pageH.toFloat(), paint)
        paint.color = 0xFF2F7BFF.toInt()
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 32f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("工作水印相机", (margin + 70).toFloat(), fy + 60f, paint)
        paint.textSize = 22f
        paint.typeface = Typeface.DEFAULT
        paint.color = 0xFF666666.toInt()
        canvas.drawText("水印拍照 真实时间地点", (margin + 70).toFloat(), fy + 98f, paint)
        paint.color = 0xFF2F7BFF.toInt()
        canvas.drawCircle((margin + 30).toFloat(), fy + 72f, 24f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 24f
        canvas.drawText("印", (margin + 30).toFloat(), fy + 80f, paint)

        return out
    }

    private fun decodeBounds(uriStr: String): Pair<Int, Int>? {
        return try {
            val uri = Uri.parse(uriStr)
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUri(uriStr: String, maxSide: Int): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            // 先 bounds 再采样，尽量高清
            val bounds = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, o)
                o.outWidth to o.outHeight
            } ?: return null
            var sample = 1
            val maxDim = maxOf(bounds.first, bounds.second)
            while (maxDim / sample > maxSide * 2) sample *= 2
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
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
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }


}
