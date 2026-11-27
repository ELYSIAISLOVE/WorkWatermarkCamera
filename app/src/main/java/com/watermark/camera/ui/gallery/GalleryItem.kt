package com.watermark.camera.ui.gallery

import com.watermark.camera.data.local.PhotoEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 相册列表项：日期分组头 + 照片。
 *
 * 相册重做（审计 C4）后按日期分组展示，分组头吸顶显示「今天 · 3 张」这类信息。
 */
sealed class GalleryItem {

    /** 日期分组头。 */
    data class Header(
        /** 分组键，例如 2026-11-27，用于 DiffUtil 稳定比较。 */
        val key: String,
        val label: String,
        val count: Int
    ) : GalleryItem()

    /** 一张照片。 */
    data class Photo(
        val entity: PhotoEntity,
        val selected: Boolean,
        val selectionIndex: Int
    ) : GalleryItem()

    companion object {

        /**
         * 按拍摄日期把照片分组展开成列表项。
         *
         * @param photos 已按时间倒序排好的照片。
         * @param selectedUris 当前选中的 uri 集合。
         */
        fun build(photos: List<PhotoEntity>, selectedUris: Set<String>): List<GalleryItem> {
            if (photos.isEmpty()) return emptyList()
            val items = ArrayList<GalleryItem>(photos.size + 8)
            var currentKey: String? = null
            var bucket = ArrayList<PhotoEntity>()
            // 选中顺序决定角标数字，和用户点击顺序一致
            val selectionOrder = selectedUris.toList()

            fun flush() {
                val key = currentKey ?: return
                items.add(Header(key, dayLabel(bucket.first().timestamp), bucket.size))
                for (p in bucket) {
                    val index = selectionOrder.indexOf(p.uri)
                    items.add(Photo(p, index >= 0, if (index >= 0) index + 1 else 0))
                }
                bucket = ArrayList()
            }

            for (p in photos) {
                val key = dayKey(p.timestamp)
                if (key != currentKey) {
                    flush()
                    currentKey = key
                }
                bucket.add(p)
            }
            flush()
            return items
        }

        private fun dayKey(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))

        /** 分组头文案：今天 / 昨天 / 11月27日（跨年补年份）。 */
        private fun dayLabel(ts: Long): String {
            val cal = Calendar.getInstance()
            val now = Calendar.getInstance()
            cal.timeInMillis = ts

            val sameYear = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
            val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
            val todayOfYear = now.get(Calendar.DAY_OF_YEAR)
            val diff = todayOfYear - dayOfYear

            return when {
                sameYear && diff == 0 -> "今天"
                sameYear && diff == 1 -> "昨天"
                sameYear && cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))
                else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ts))
            }
        }
    }
}