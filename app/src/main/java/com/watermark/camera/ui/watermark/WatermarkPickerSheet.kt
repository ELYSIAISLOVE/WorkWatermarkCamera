package com.watermark.camera.ui.watermark

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.watermark.camera.R
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkFont
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.data.watermark.TemplateFormCatalog

/**
 * Bottom sheet: left templates, right time styles, independent scroll.
 */
class WatermarkPickerSheet : BottomSheetDialogFragment() {

    var initialConfig: WatermarkConfig = WatermarkConfig()
    var onSelectionChanged: ((WatermarkConfig) -> Unit)? = null

    private lateinit var preview: WatermarkPreviewView
    private var config: WatermarkConfig = WatermarkConfig()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_watermark_picker, container, false)

    /**
     * 面板高度与宽度（规范 §5）：
     * - 初始高度为屏幕的 50%，可上拉到 90%；
     * - 平板（宽度 ≥600dp）限宽 640dp 并居中。
     */
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return

        val dm = resources.displayMetrics
        val maxHeight = (dm.heightPixels * 0.9f).toInt()
        val peekHeight = (dm.heightPixels * 0.5f).toInt()

        val lp = sheet.layoutParams
        lp.height = maxHeight
        lp.width = if (resources.configuration.screenWidthDp >= 600) {
            (640 * dm.density).toInt().coerceAtMost(dm.widthPixels)
        } else {
            dm.widthPixels
        }
        (lp as? android.widget.FrameLayout.LayoutParams)?.gravity =
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        sheet.layoutParams = lp

        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).apply {
            isFitToContents = false
            this.peekHeight = peekHeight
            this.maxHeight = maxHeight
            skipCollapsed = false
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        config = initialConfig.copy(showLocation = true)

        preview = view.findViewById(R.id.pickerPreviewCard)
        view.findViewById<View>(R.id.pickerClose).setOnClickListener { dismiss() }

        val customRow = view.findViewById<View>(R.id.pickerCustomTitleRow)
        val customEt = view.findViewById<EditText>(R.id.pickerCustomTitle)
        customEt?.setText(config.customTitle)
        customEt?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                config = config.copy(customTitle = s?.toString()?.trim().orEmpty())
                refreshPreview()
                onSelectionChanged?.invoke(config)
            }
        })

        val seek = view.findViewById<SeekBar>(R.id.pickerTransparency)
        val seekVal = view.findViewById<TextView>(R.id.pickerTransparencyValue)
        val initialT = (config.transparency.coerceIn(0.3f, 1f) * 100).toInt()
        seek?.progress = initialT
        seekVal?.text = "${initialT}%"
        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = progress.coerceIn(30, 100) // min 30%
                if (progress < 30) seek?.progress = 30
                val tVal = p / 100f
                config = config.copy(transparency = tVal)
                seekVal?.text = "${p}%"
                refreshPreview()
                onSelectionChanged?.invoke(config)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })


        val templates = WatermarkTemplate.menuEntries()

        val leftRv = view.findViewById<RecyclerView>(R.id.pickerTemplateGrid)
        leftRv.layoutManager = GridLayoutManager(requireContext(), 2)
        val templateAdapter = TemplateGridAdapter(templates, config.template) { chosen ->
            config = config.copy(template = chosen)
            runCatching { customRow?.visibility = View.GONE }
            refreshPreview()
            onSelectionChanged?.invoke(config)
            // 换模板后字段集合变了，重建字段显隐开关
            setupFieldToggles(view)
            showTemplateFieldsDialog(chosen)
        }
        runCatching { customRow?.visibility = View.GONE }
        leftRv.adapter = templateAdapter

        val styles = listOf(
            TimeStyle.DEFAULT,
            TimeStyle.DIGITAL_TUBE,
            TimeStyle.FLIP_CALENDAR,
            TimeStyle.RETRO_SLASH
        )
        val rightRv = view.findViewById<RecyclerView>(R.id.pickerStyleList)
        rightRv.layoutManager = LinearLayoutManager(requireContext())
        val styleAdapter = StyleListAdapter(styles, config.timeStyle) { chosen ->
            config = config.copy(timeStyle = chosen)
            refreshPreview()
            onSelectionChanged?.invoke(config)
        }
        rightRv.adapter = styleAdapter

        setupFontScale(view)
        setupFontRow(view)
        setupShowDate(view)
        setupPositionRow(view)
        setupFieldToggles(view)

        refreshPreview()
    }

    // region 样式控件（字号 / 字体 / 显示日期 / 位置）

    private fun setupFontScale(root: View) {
        val seek = root.findViewById<SeekBar>(R.id.pickerFontScale)
        val label = root.findViewById<TextView>(R.id.pickerFontScaleValue)
        // 0.30x – 3.00x，步长 0.01
        val progress = ((config.clampedFontScale() - 0.30f) * 100).toInt().coerceIn(0, 270)
        seek?.progress = progress
        label?.text = String.format(java.util.Locale.US, "%.2fx", config.clampedFontScale())
        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val scale = 0.30f + value / 100f
                config = config.copy(fontScale = scale)
                label?.text = String.format(java.util.Locale.US, "%.2fx", scale)
                if (fromUser) {
                    refreshPreview()
                    onSelectionChanged?.invoke(config)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupFontRow(root: View) {
        val row = root.findViewById<LinearLayout>(R.id.pickerFontRow) ?: return
        row.removeAllViews()
        val density = resources.displayMetrics.density
        WatermarkFont.entries.forEach { font ->
            val chip = TextView(requireContext()).apply {
                text = font.label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(
                    (12 * density).toInt(), (6 * density).toInt(),
                    (12 * density).toInt(), (6 * density).toInt()
                )
                isClickable = true
                isFocusable = true
                setTextColor(if (font == config.resolvedFontFamily()) Color.WHITE else 0xFF333333.toInt())
                background = chipBackground(font == config.resolvedFontFamily())
                setOnClickListener {
                    config = config.copy(fontFamily = font.name)
                    setupFontRow(root)
                    refreshPreview()
                    onSelectionChanged?.invoke(config)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * density).toInt()
            row.addView(chip, lp)
        }
    }

    private fun chipBackground(selected: Boolean): android.graphics.drawable.GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 14f * resources.displayMetrics.density
            setColor(if (selected) 0xFF2A6B4F.toInt() else 0xFFEDEDED.toInt())
        }

    private fun setupShowDate(root: View) {
        val cb = root.findViewById<android.widget.CheckBox>(R.id.pickerShowDate) ?: return
        cb.isChecked = config.showDate
        cb.setOnCheckedChangeListener { _, checked ->
            config = config.copy(showDate = checked)
            refreshPreview()
            onSelectionChanged?.invoke(config)
        }
    }

    private fun setupPositionRow(root: View) {
        val row = root.findViewById<LinearLayout>(R.id.pickerPositionRow) ?: return
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val corners = listOf(
            WatermarkPosition.TOP_LEFT to "↖",
            WatermarkPosition.TOP_RIGHT to "↗",
            WatermarkPosition.BOTTOM_LEFT to "↙",
            WatermarkPosition.BOTTOM_RIGHT to "↘"
        )
        corners.forEach { (pos, glyph) ->
            val btn = TextView(requireContext()).apply {
                text = glyph
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(
                        if (config.position == pos) 0xFF2A6B4F.toInt() else 0xFF3A3A3A.toInt()
                    )
                }
                setOnClickListener {
                    config = config.copy(position = pos, customX = null, customY = null)
                    setupPositionRow(root)
                    refreshPreview()
                    onSelectionChanged?.invoke(config)
                }
            }
            val lp = LinearLayout.LayoutParams(
                (32 * density).toInt(), (32 * density).toInt()
            )
            lp.marginEnd = (4 * density).toInt()
            row.addView(btn, lp)
        }
    }

    /** 按当前模板动态生成「字段显隐」开关（审计 B3）。 */
    private fun setupFieldToggles(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.pickerFieldToggles) ?: return
        container.removeAllViews()
        val spec = TemplateFormCatalog.specOf(config.template)
        val density = resources.displayMetrics.density

        val title = TextView(requireContext()).apply {
            text = "显示哪些字段"
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        container.addView(title)

        for (field in spec.editable) {
            val cb = android.widget.CheckBox(requireContext()).apply {
                text = field.label
                textSize = 13f
                setTextColor(0xFF333333.toInt())
                isChecked = !config.isFieldHidden(field.key)
                setOnCheckedChangeListener { _, checked ->
                    config = config.withFieldVisible(field.key, checked)
                    refreshPreview()
                    onSelectionChanged?.invoke(config)
                }
            }
            container.addView(cb)
        }

        // 地点行单独开关（日期由上面的「显示日期」控制）
        val locCb = android.widget.CheckBox(requireContext()).apply {
            text = "地点"
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            isChecked = config.showLocation
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(showLocation = checked)
                refreshPreview()
                onSelectionChanged?.invoke(config)
            }
        }
        container.addView(locCb)
    }

    // endregion

    /**
     * 刷新预览。
     *
     * 过去这里用 TextView 拼字符串假装预览，和成片完全不是一回事（审计 B1）。
     * 现在直接把当前配置交给 [WatermarkPreviewView]，它内部调用的就是成片用的
     * `WatermarkRenderer`，因此「所见即所得」。
     */
    private fun refreshPreview() {
        preview.config = config
        preview.timeBadge = "可信时间"
        preview.invalidate()
    }

    companion object {
        fun newInstance(): WatermarkPickerSheet = WatermarkPickerSheet()
    }


    private fun showTemplateFieldsDialog(template: WatermarkTemplate) {
        val spec = TemplateFormCatalog.specOf(template)
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (4 * density).toInt())
        }
        val editors = linkedMapOf<String, EditText>()
        for (field in spec.editable) {
            container.addView(android.widget.TextView(ctx).apply {
                text = field.label
                textSize = 13f
                setTextColor(0xFF333333.toInt())
                setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
            })
            val et = EditText(ctx).apply {
                hint = field.hint
                setText(TemplateFormCatalog.readField(config, template, field.key))
                setSingleLine(true)
                textSize = 15f
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFF5F5F5.toInt())
                    cornerRadius = 8 * density
                }
            }
            container.addView(et)
            editors[field.key] = et
        }
        AlertDialog.Builder(ctx)
            .setTitle("填写「${spec.title}」信息")
            .setMessage("时间、地点自动生成；下列字段仅作用于本模板")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val values = editors.mapValues { it.value.text?.toString().orEmpty() }
                config = TemplateFormCatalog.writeFields(config, template, values)
                refreshPreview()
                onSelectionChanged?.invoke(config)
            }
            .setNegativeButton("跳过", null)
            .show()
    }

    private class TemplateGridAdapter(
        private val items: List<WatermarkTemplate>,
        private var selected: WatermarkTemplate,
        private val onClick: (WatermarkTemplate) -> Unit
    ) : RecyclerView.Adapter<TemplateGridAdapter.H>() {

        class H(v: View) : RecyclerView.ViewHolder(v) {
            val card: View = v.findViewById(R.id.itemPickerTemplateCard)
            val name: TextView = v.findViewById(R.id.itemPickerTemplateName)
            val hint: TextView = v.findViewById(R.id.itemPickerTemplateHint)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_picker_template, parent, false)
            return H(v)
        }

        override fun onBindViewHolder(holder: H, position: Int) {
            val item = items[position]
            holder.name.text = item.cardTitle()
            holder.hint.text = item.description
            val gd = GradientDrawable()
            gd.cornerRadius = 10f * holder.itemView.resources.displayMetrics.density
            gd.setColor(item.resolvedHeaderColor())
            val strokeW = if (item == selected) 3 else 1
            val strokeC = if (item == selected) Color.WHITE else 0x66FFFFFF.toInt()
            gd.setStroke(strokeW, strokeC)
            holder.card.background = gd
            // 同步资源卡片色（drawable 备份）
            runCatching {
                val resName = TemplateFormCatalog.drawableName(item)
                val id = holder.itemView.resources.getIdentifier(
                    resName, "drawable", holder.itemView.context.packageName
                )
                if (id != 0 && item != selected) {
                    // keep gradient for stroke selection state
                }
            }
            holder.itemView.setOnClickListener {
                val old = items.indexOf(selected)
                selected = item
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(position)
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class StyleListAdapter(
        private val items: List<TimeStyle>,
        private var selected: TimeStyle,
        private val onClick: (TimeStyle) -> Unit
    ) : RecyclerView.Adapter<StyleListAdapter.H>() {

        class H(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.itemPickerStyleName)
            val sample: TextView = v.findViewById(R.id.itemPickerStyleSample)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_picker_style, parent, false)
            return H(v)
        }

        override fun onBindViewHolder(holder: H, position: Int) {
            val item = items[position]
            holder.name.text = when (item) {
                TimeStyle.DIGITAL_TUBE -> "七段数码管"
                TimeStyle.FLIP_CALENDAR -> "翻页日历"
                TimeStyle.RETRO_SLASH -> "汉字样式"
                else -> "默认"
            }
            holder.sample.text = when (item) {
                TimeStyle.DIGITAL_TUBE -> "88:88:88"
                TimeStyle.FLIP_CALENDAR -> "12 34"
                TimeStyle.RETRO_SLASH -> "12/34"
                else -> "12:34:56"
            }
            holder.sample.setTextColor(
                when (item) {
                    TimeStyle.DIGITAL_TUBE -> 0xFF00FF66.toInt()
                    TimeStyle.RETRO_SLASH -> 0xFFD4A574.toInt()
                    else -> Color.WHITE
                }
            )
            holder.itemView.alpha = if (item == selected) 1f else 0.7f
            holder.itemView.setBackgroundColor(
                if (item == selected) 0x33FFFFFF.toInt() else Color.TRANSPARENT
            )
            holder.itemView.setOnClickListener {
                val old = items.indexOf(selected)
                selected = item
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(position)
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
