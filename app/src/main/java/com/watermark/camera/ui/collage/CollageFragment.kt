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
    private var template: Template = Template.GRID_3X3
    private var resultBitmap: Bitmap? = null
    private var reportTitle: String = "白班打点"
    private var reportSubtitle: String = "工作现场照片汇总整理"
    private var reporterName: String = "—"
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
        val cap = 15
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
        applySystemBarInsets(view)

        super.onViewCreated(view, savedInstanceState)
        try {
        tvCount = view.findViewById(R.id.tvHint)
        ivPreview = view.findViewById(R.id.ivPreview)
        progress = view.findViewById(R.id.progressBar)
        btnGenerate = view.findViewById(R.id.btnGenerate)
        tvEmptyHint = view.findViewById(R.id.tvEmptyHint)
        // 简化布局已去掉模板条与选图列表，仅保留预览 + 选图/保存

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
        // 模板入口已从布局移除，固定使用 3 列汇报排版
    }

    private fun refreshUi() {
        adapter?.notifyDataSetChanged()
        val cap = template.capacity
        tvCount?.text = "已选 ${photoUris.size} 张 · 点击上方文字可改标题/汇报人"
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
            if (showToast) Toast.makeText(requireContext(), "预览已更新", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptEdit(title: String, current: String, onOk: (String) -> Unit) {
        val ctx = requireContext()
        val et = EditText(ctx).apply {
            setText(current)
            setSelection(text.length)
            setSingleLine(true)
            hint = title
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(et)
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
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
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

    private fun buildCollage(uris: List<String>, tpl: Template, title: String = reportTitle, subtitle: String = reportSubtitle, reporter: String = reporterName): Bitmap {
        // 参考「白班打点」工作汇报：白底 + 蓝色标题头 + 网格照片 + 底部品牌栏
        val pageW = 1080
        val margin = 36
        val gap = 16
        val cols = when {
            tpl.cols >= 3 -> 3
            tpl.cols == 2 -> 2
            else -> tpl.cols.coerceAtLeast(1)
        }
        val n = minOf(uris.size, maxOf(tpl.capacity, 15))
        val rows = maxOf(tpl.rows, (n + cols - 1) / cols)
        val contentW = pageW - margin * 2
        val cellW = (contentW - gap * (cols - 1)) / cols
        val cellH = (cellW * 0.75f).toInt() // 4:3 格，尽量少裁切
        val headerH = 280
        val footerH = 160
        val gridH = rows * cellH + (rows - 1).coerceAtLeast(0) * gap
        val pageH = headerH + gridH + footerH + margin

        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Header title
        paint.color = 0xFF2F7BFF.toInt()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 72f
        canvas.drawText(title.ifBlank { "白班打点" }, pageW / 2f, 100f, paint)
        paint.textSize = 32f
        paint.typeface = Typeface.DEFAULT
        paint.color = 0xFF5B8DEF.toInt()
        canvas.drawText(subtitle.ifBlank { "工作现场照片汇总整理" }, pageW / 2f, 150f, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 28f
        paint.color = 0xFF333333.toInt()
        val dateStr = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
        canvas.drawText("汇报人: ${reporter.ifBlank { "—" }}", margin.toFloat(), 210f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dateStr, (pageW - margin).toFloat(), 210f, paint)

        // Divider
        paint.color = 0xFFE8EEF8.toInt()
        paint.strokeWidth = 2f
        canvas.drawLine(margin.toFloat(), 240f, (pageW - margin).toFloat(), 240f, paint)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val gridTop = headerH
        for (i in 0 until n) {
            val r = i / cols
            val c = i % cols
            val left = margin + c * (cellW + gap)
            val top = gridTop + r * (cellH + gap)
            val uri = Uri.parse(uris[i])
            val src = try {
                requireContext().contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (_: Exception) {
                null
            }
            // 白底圆角格
            paint.style = Paint.Style.FILL
            paint.color = 0xFFF5F7FA.toInt()
            val cellRect = RectF(left.toFloat(), top.toFloat(), (left + cellW).toFloat(), (top + cellH).toFloat())
            canvas.drawRoundRect(cellRect, 12f, 12f, paint)
            if (src == null) continue
            // 等比适配进格子，不裁切主体（letterbox）
            val scale = minOf(cellW.toFloat() / src.width, cellH.toFloat() / src.height)
            val dw = (src.width * scale).toInt().coerceAtLeast(1)
            val dh = (src.height * scale).toInt().coerceAtLeast(1)
            val dx = left + (cellW - dw) / 2
            val dy = top + (cellH - dh) / 2
            val dest = Rect(dx, dy, dx + dw, dy + dh)
            canvas.drawBitmap(src, null, dest, null)
            src.recycle()
        }

        // Footer bar
        val fy = pageH - footerH
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF7F9FC.toInt()
        canvas.drawRect(0f, fy.toFloat(), pageW.toFloat(), pageH.toFloat(), paint)
        paint.color = 0xFF2F7BFF.toInt()
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 36f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("工作水印相机", (margin + 80).toFloat(), fy + 70f, paint)
        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT
        paint.color = 0xFF666666.toInt()
        canvas.drawText("水印拍照 真实时间地点", (margin + 80).toFloat(), fy + 110f, paint)
        // 简易圆形 logo
        paint.color = 0xFF2F7BFF.toInt()
        canvas.drawCircle((margin + 36).toFloat(), fy + 80f, 28f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 28f
        canvas.drawText("印", (margin + 36).toFloat(), fy + 90f, paint)
        // 右侧二维码占位
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xFF2F7BFF.toInt()
        val qr = 72f
        val qx = pageW - margin - qr
        val qy = fy + 44f
        canvas.drawRect(qx, qy, qx + qr, qy + qr, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 18f
        paint.color = 0xFF888888.toInt()
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("扫码", qx + qr / 2f, qy + qr + 28f, paint)

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
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }


}
