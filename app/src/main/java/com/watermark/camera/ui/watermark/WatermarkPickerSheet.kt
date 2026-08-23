package com.watermark.camera.ui.watermark

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Popup picker: left = template boards (grid, scrollable),
 * right = digit/time styles (list, independent scroll).
 * Selection updates preview via [onSelectionChanged].
 */
class WatermarkPickerSheet : BottomSheetDialogFragment() {

    var initialConfig: WatermarkConfig = WatermarkConfig()
    var onSelectionChanged: ((WatermarkConfig) -> Unit)? = null

    private lateinit var previewTitle: TextView
    private lateinit var previewBody: TextView
    private lateinit var previewCard: View
    private var current: WatermarkConfig = WatermarkConfig()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_watermark_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        current = initialConfig.copy(showLocation = true, fontScale = 2.5f)
        previewTitle = view.findViewById(R.id.pickerPreviewTitle)
        previewBody = view.findViewById(R.id.pickerPreviewBody)
        previewCard = view.findViewById(R.id.pickerPreviewCard)
        view.findViewById<View>(R.id.pickerClose).setOnClickListener { dismiss() }

        val leftRv = view.findViewById<RecyclerView>(R.id.pickerTemplateGrid)
        leftRv.layoutManager = GridLayoutManager(requireContext(), 2)
        leftRv.adapter = TemplateGridAdapter(WatermarkTemplate.menuEntries(), current.template) { t ->
            current = current.copy(template = t)
            refreshPreview()
            onSelectionChanged?.invoke(current)
        }

        val rightRv = view.findViewById<RecyclerView>(R.id.pickerStyleList)
        rightRv.layoutManager = LinearLayoutManager(requireContext())
        rightRv.adapter = StyleListAdapter(TimeStyle.values().toList(), current.timeStyle) { s ->
            current = current.copy(timeStyle = s)
            refreshPreview()
            onSelectionChanged?.invoke(current)
        }

        refreshPreview()
    }

    private fun refreshPreview() {
        previewTitle.text = current.template.displayName + "水印"
        previewBody.text = when (current.timeStyle) {
            TimeStyle.DIGITAL_TUBE -> "12:34:56  数码管样式"
            TimeStyle.FLIP_CALENDAR -> "12:34  翻页日历"
            TimeStyle.RETRO_SLASH -> "12/34  复古斜线"
            else -> "12:34:56  默认样式"
        }
        val bg = GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(current.template.backgroundColor)
        }
        previewCard.background = bg
        previewTitle.setTextColor(Color.WHITE)
        previewBody.setTextColor(
            when (current.timeStyle) {
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
            val t = items[position]
            holder.name.text = t.displayName
            holder.hint.text = t.description
            val gd = GradientDrawable().apply {
                cornerRadius = 10f * holder.itemView.resources.displayMetrics.density
                setColor(t.backgroundColor)
                setStroke(
                    if (t == selected) 3 else 1,
                    if (t == selected) Color.WHITE else 0x66FFFFFF
                )
            }
            holder.card.background = gd
            holder.itemView.setOnClickListener {
                val old = items.indexOf(selected)
                selected = t
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(position)
                onClick(t)
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
            val s = items[position]
            holder.name.text = s.displayName
            holder.sample.text = when (s) {
                TimeStyle.DIGITAL_TUBE -> "88:88:88"
                TimeStyle.FLIP_CALENDAR -> "12 34"
                TimeStyle.RETRO_SLASH -> "12/34"
                else -> "12:34:56"
            }
            holder.sample.setTextColor(
                when (s) {
                    TimeStyle.DIGITAL_TUBE -> 0xFF00FF66.toInt()
                    TimeStyle.RETRO_SLASH -> 0xFFD4A574.toInt()
                    else -> Color.WHITE
                }
            )
            holder.itemView.alpha = if (s == selected) 1f else 0.7f
            holder.itemView.setBackgroundColor(
                if (s == selected) 0x33FFFFFF else Color.TRANSPARENT
            )
            holder.itemView.setOnClickListener {
                val old = items.indexOf(selected)
                selected = s
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(position)
                onClick(s)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
