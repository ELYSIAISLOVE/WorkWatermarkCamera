package com.watermark.camera.ui.collage

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.watermark.camera.R
import com.watermark.camera.data.collage.CollageEngine
import com.watermark.camera.data.collage.CollageTemplate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 拼图页。
 *
 * 重做要点（对应审计 A1/D1–D6）：
 * - 不再自带一套 Template 枚举与手写绘制，统一走 [CollageViewModel] + `CollageEngine`；
 * - 引入 ViewModel，旋转屏幕/切后台返回不再丢已选照片与结果；
 * - 新增间距 / 圆角 / 背景色可调（此前全是硬编码常量）；
 * - 模板扩展为 竖向 / 两图 / 四宫格 / 九宫格；
 * - 生成后可分享。
 */
@AndroidEntryPoint
class CollageFragment : Fragment() {

    private val viewModel: CollageViewModel by viewModels()

    private var importedArguments = false

    private var _binding: com.watermark.camera.databinding.FragmentCollageBinding? = null
    private val binding get() = _binding!!

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        viewModel.importFromPicker(requireContext(), uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = com.watermark.camera.databinding.FragmentCollageBinding.inflate(
            inflater, container, false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTemplates()
        setupStyle()
        setupBackgrounds()
        setupReportBar()
        setupActions()
        observe()
        importArgumentsOnce()
    }

    /** 从相册带过来的已选照片（只处理一次，避免旋转屏幕后重复导入）。 */
    private fun importArgumentsOnce() {
        if (importedArguments) return
        val uris = arguments?.getStringArrayList(ARG_URIS) ?: return
        if (uris.isEmpty()) return
        importedArguments = true
        viewModel.importFromPicker(requireContext(), uris.map { Uri.parse(it) })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // region UI

    private fun setupTemplates() {
        val map = listOf(
            binding.tplVertical to CollageTemplate.VerticalLong,
            binding.tplGrid2 to CollageTemplate.Grid2,
            binding.tplGrid4 to CollageTemplate.Grid4,
            binding.tplGrid9 to CollageTemplate.Grid9
        )
        map.forEach { (btn, tpl) ->
            btn.setOnClickListener {
                viewModel.selectTemplate(tpl)
                highlightTemplate(btn)
            }
        }
        highlightTemplate(binding.tplVertical)
    }

    private fun highlightTemplate(selected: Button) {
        listOf(binding.tplVertical, binding.tplGrid2, binding.tplGrid4, binding.tplGrid9)
            .forEach { it.alpha = if (it === selected) 1f else 0.6f }
    }

    private fun setupStyle() {
        binding.seekSpacing.progress = viewModel.style.value.spacingPx
        binding.tvSpacing.text = "${viewModel.style.value.spacingPx}"
        binding.seekSpacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.setSpacingPx(progress)
                binding.tvSpacing.text = "$progress"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekRadius.progress = viewModel.style.value.cornerRadiusPx.toInt()
        binding.tvRadius.text = "${viewModel.style.value.cornerRadiusPx.toInt()}"
        binding.seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.setCornerRadiusPx(progress.toFloat())
                binding.tvRadius.text = "$progress"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupBackgrounds() {
        val colors = listOf(
            "白" to Color.WHITE,
            "浅灰" to 0xFFF0F0F0.toInt(),
            "黑" to Color.BLACK,
            "主色" to 0xFF2A6B4F.toInt()
        )
        val density = resources.displayMetrics.density
        binding.bgColorRow.removeAllViews()
        colors.forEach { (label, color) ->
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setTextColor(if (color == Color.WHITE) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(color)
                    setStroke((1 * density).toInt(), 0xFF999999.toInt())
                }
                setOnClickListener { viewModel.setBackgroundColor(color) }
            }
            val lp = LinearLayout.LayoutParams((52 * density).toInt(), (28 * density).toInt())
            lp.marginEnd = (8 * density).toInt()
            binding.bgColorRow.addView(chip, lp)
        }
    }

    private fun setupReportBar() {
        binding.btnEditTitle.setOnClickListener {
            editText("拼图标题", viewModel.reportTitle.value) { viewModel.setReportTitle(it) }
        }
        binding.btnEditReporter.setOnClickListener {
            editText("汇报人", viewModel.reporterName.value) { viewModel.setReporterName(it) }
        }
        binding.btnEditSubtitle.setOnClickListener {
            editText("副标题", viewModel.reportSubtitle.value) { viewModel.setReportSubtitle(it) }
        }
    }

    private fun editText(title: String, current: String, onSave: (String) -> Unit) {
        val input = EditText(requireContext()).apply { setText(current) }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ -> onSave(input.text?.toString().orEmpty()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupActions() {
        binding.btnPick.setOnClickListener { pickImages.launch("image/*") }
        binding.btnClear.setOnClickListener { viewModel.clearSelections() }
        binding.btnGenerate.setOnClickListener { viewModel.generateCollage() }
        binding.btnShare.setOnClickListener {
            val uri = viewModel.savedUri.value ?: return@setOnClickListener
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享拼图"))
        }
    }

    // endregion

    // region 状态

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.selectedPhotos.collect { photos ->
                        binding.tvHint.text = "已选 ${photos.size} 张 · ${viewModel.selectedTemplate.value.displayName}"
                        binding.btnGenerate.isEnabled = photos.isNotEmpty()
                        binding.tvEmptyHint.visibility =
                            if (photos.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.isGenerating.collect { generating ->
                        binding.progressBar.visibility =
                            if (generating) View.VISIBLE else View.GONE
                        binding.btnGenerate.isEnabled =
                            !generating && viewModel.selectedPhotos.value.isNotEmpty()
                    }
                }

                launch {
                    viewModel.collageResult.collect { uri ->
                        binding.btnShare.isEnabled = uri != null
                        if (uri != null) showPreview(uri)
                    }
                }

                launch {
                    viewModel.errorMessage.collect { msg ->
                        msg?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    private fun showPreview(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                if (bitmap != null) binding.ivPreview.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            android.util.Log.e("CollageFragment", "preview failed", e)
        }
    }

    // endregion

    companion object {
        fun newInstance(uris: List<String>): CollageFragment = CollageFragment().apply {
            arguments = Bundle().apply {
                putStringArrayList(ARG_URIS, ArrayList(uris))
            }
        }

        private const val ARG_URIS = "collage_uris"
    }
}