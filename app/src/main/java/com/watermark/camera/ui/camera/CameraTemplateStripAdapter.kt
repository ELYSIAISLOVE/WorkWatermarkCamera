package com.watermark.camera.ui.camera

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.watermark.camera.R
import com.watermark.camera.data.model.WatermarkTemplate

/**
 * Horizontal template chips above the bottom bar.
 * Renders a color card + name (no external thumbnail assets required).
 */
class CameraTemplateStripAdapter(
    private val onSelect: (WatermarkTemplate) -> Unit
) : RecyclerView.Adapter<CameraTemplateStripAdapter.Holder>() {

    private val items: List<WatermarkTemplate> = WatermarkTemplate.menuEntries()
    private var selected: WatermarkTemplate = WatermarkTemplate.default()

    fun setSelected(template: WatermarkTemplate) {
        val old = selected
        selected = template
        val oldIdx = items.indexOf(old)
        val newIdx = items.indexOf(template)
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        if (newIdx >= 0) notifyItemChanged(newIdx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera_template, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item, item == selected)
        holder.itemView.setOnClickListener {
            setSelected(item)
            onSelect(item)
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorDot: View = itemView.findViewById(R.id.templateColorDot)
        private val name: TextView = itemView.findViewById(R.id.templateName)
        private val root: View = itemView.findViewById(R.id.templateItemRoot)

        fun bind(template: WatermarkTemplate, isSelected: Boolean) {
            name.text = template.cardTitle()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * itemView.resources.displayMetrics.density
                setColor(template.backgroundColor)
            }
            colorDot.background = bg

            val stroke = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * itemView.resources.displayMetrics.density
                setColor(Color.parseColor("#33000000"))
                if (isSelected) {
                    setStroke(
                        (2 * itemView.resources.displayMetrics.density).toInt(),
                        Color.WHITE
                    )
                } else {
                    setStroke(
                        (1 * itemView.resources.displayMetrics.density).toInt(),
                        Color.parseColor("#55FFFFFF")
                    )
                }
            }
            root.background = stroke
            name.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#CCFFFFFF"))
        }
    }
}
