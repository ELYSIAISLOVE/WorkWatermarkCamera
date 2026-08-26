package com.watermark.camera.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Watermark configuration data class.
 *
 * Defines all properties of a watermark that can be customized by the user.
 * Stored in SharedPreferences as JSON.
 *
 * @param template The watermark template style.
 * @param name User's name or employee ID.
 * @param projectName Project name.
 * @param remark Additional remarks.
 * @param location Location string (auto or manual).
 * @param transparency Watermark background transparency (0.3 to 1.0).
 * @param fontScale Font size scale (0.5 to 8.0).
 * @param position Watermark position on the photo.
 * @param useGyroscope Whether to use gyroscope for adaptive positioning.
 * @param showLocation Whether to show location in watermark.
 */
@Parcelize
data class WatermarkConfig(
    val template: WatermarkTemplate = WatermarkTemplate.GENERAL,
    val timeStyle: TimeStyle = TimeStyle.DEFAULT,
    /** 通用/自定义水印标题（用户输入） */
    val customTitle: String = "",
    val name: String = "",
    val projectName: String = "",
    val remark: String = "",
    val location: String = "",
    val transparency: Float = 0.8f,
    val fontScale: Float = 2.0f,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_LEFT,
    /**
     * Free-drag position of card top-left as fraction of [0,1] in view/photo space.
     * null = derive from [position] enum only.
     */
    val customX: Float? = null,
    val customY: Float? = null,
    val useGyroscope: Boolean = true,
    val showLocation: Boolean = true
) : Parcelable {

    companion object {
        /**
         * Minimum transparency value.
         */
        const val MIN_TRANSPARENCY = 0.3f

        /**
         * Maximum transparency value.
         */
        const val MAX_TRANSPARENCY = 1.0f

        /**
         * Minimum font scale.
         */
        const val MIN_FONT_SCALE = 0.5f

        /**
         * Maximum font scale.
         */
        const val MAX_FONT_SCALE = 8.0f

        /**
         * Default transparency.
         */
        const val DEFAULT_TRANSPARENCY = 0.8f

        /**
         * Default font scale.
         */
        const val DEFAULT_FONT_SCALE = 2.5f
    }

    /**
     * Validate and clamp transparency to valid range.
     */
    fun clampedTransparency(): Float =
        transparency.coerceIn(MIN_TRANSPARENCY, MAX_TRANSPARENCY)

    /**
     * Validate and clamp font scale to valid range.
     */
    fun clampedFontScale(): Float = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE).coerceAtLeast(1.8f)

    /**
     * Check if any custom field is filled.
     */
    fun hasCustomFields(): Boolean =
        name.isNotBlank() || projectName.isNotBlank() || remark.isNotBlank()

    /**
     * Get display text for the watermark.
     *
     * @param timeStr Time string (HH:mm).
     * @param dateStr Date string (yyyy/MM/dd).
     * @param weekStr Weekday string (e.g., "星期四").
     * @return Formatted multi-line watermark text.
     */
    fun getDisplayText(
        timeStr: String,
        dateStr: String,
        weekStr: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine(template.cardTitle(customTitle))
        sb.appendLine("$timeStr | $dateStr")
        sb.appendLine(weekStr)

        if (showLocation && location.isNotBlank()) {
            sb.appendLine("● $location")
        }

        if (name.isNotBlank()) {
            sb.appendLine("汇报人: $name")
        }

        if (projectName.isNotBlank()) {
            sb.appendLine("项目: $projectName")
        }

        if (remark.isNotBlank()) {
            sb.appendLine("备注: $remark")
        }

        return sb.toString().trimEnd()
    }
}

/**
 * Watermark position on the photo.
 */
enum class WatermarkPosition {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    CENTER
}
