package com.watermark.camera.ui.watermark

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.watermark.camera.R
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkTemplate

/**
 * Bottom sheet: left templates, right time styles, independent scroll.
 */
class WatermarkPickerSheet : BottomSheetDialogFragment() {

    var initialConfig: WatermarkConfig = WatermarkConfig()
    var onSelectionChanged: ((WatermarkConfig) -> Unit)? = null

    private lateinit var previewTitle: TextView
    private lateinit var previewBody: TextView
    private lateinit var previewCard: View
    private var config: WatermarkConfig = WatermarkConfig()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_watermark_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        config = initialConfig.copy(showLocation = true)

        previewTitle = view.findViewById(R.id.pickerPreviewTitle)
        previewBody = view.findViewById(R.id.pickerPreviewBody)
        previewCard = view.findViewById(R.id.pickerPreviewCard)
        view.findViewById<View>(R.id.pickerClose).setOnClickListener { dismiss() }

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


        val templates = listOf(
            WatermarkTemplate.PROPERTY_INSPECTION,
            WatermarkTemplate.DUTY,
            WatermarkTemplate.ATTENDANCE,
            WatermarkTemplate.WORK_REPORT,
            WatermarkTemplate.EVIDENCE,
            WatermarkTemplate.ENGINEERING,
            WatermarkTemplate.GENERAL
        )

        val leftRv = view.findViewById<RecyclerView>(R.id.pickerTemplateGrid)
        leftRv.layoutManager = GridLayoutManager(requireContext(), 2)
        val templateAdapter = TemplateGridAdapter(templates, config.template) { chosen ->
            config = config.copy(template = chosen)
            refreshPreview()
            onSelectionChanged?.invoke(config)
        }
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

        refreshPreview()
    }

    private fun refreshPreview() {
        val tmpl: WatermarkTemplate = config.template
        previewTitle.text = tmpl.displayName + "水印"
        previewBody.text = when (config.timeStyle) {
            TimeStyle.DIGITAL_TUBE -> "88:88:88  数码管(等宽绿)"
            TimeStyle.FLIP_CALENDAR -> "12:34  翻页日历(衬线)"
            TimeStyle.RETRO_SLASH -> "12/34/56  复古斜线"
            else -> "12:34:56  默认样式"
        }
        // Typeface preview on sample line
        previewBody.typeface = when (config.timeStyle) {
            TimeStyle.DIGITAL_TUBE -> android.graphics.Typeface.MONOSPACE
            TimeStyle.FLIP_CALENDAR -> android.graphics.Typeface.SERIF
            TimeStyle.RETRO_SLASH -> android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.ITALIC
            )
            else -> android.graphics.Typeface.DEFAULT
        }
        val bg = GradientDrawable()
        bg.cornerRadius = 12f * resources.displayMetrics.density
        bg.setColor(tmpl.backgroundColor)
        previewCard.background = bg
        previewTitle.setTextColor(Color.WHITE)
        previewBody.setTextColor(
            when (config.timeStyle) {
                TimeStyle.DIGITAL_TUBE -> 0xFF00FF66.toInt()
                TimeStyle.RETRO_SLASH -> 0xFFD4A574.toInt()
                else -> Color.WHITE
            }
        )
    }

    companion object {
        fun newInstance(): WatermarkPickerSheet = WatermarkPickerSheet()
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
            holder.name.text = item.displayName
            holder.hint.text = item.description
            val gd = GradientDrawable()
            gd.cornerRadius = 10f * holder.itemView.resources.displayMetrics.density
            gd.setColor(item.backgroundColor)
            val strokeW = if (item == selected) 3 else 1
            val strokeC = if (item == selected) Color.WHITE else 0x66FFFFFF.toInt()
            gd.setStroke(strokeW, strokeC)
            holder.card.background = gd
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
                TimeStyle.DIGITAL_TUBE -> "数码管"
                TimeStyle.FLIP_CALENDAR -> "翻页日历"
                TimeStyle.RETRO_SLASH -> "复古斜线"
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
