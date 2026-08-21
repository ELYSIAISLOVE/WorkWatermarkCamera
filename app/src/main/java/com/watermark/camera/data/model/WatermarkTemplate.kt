package com.watermark.camera.data.model

import androidx.annotation.ColorInt

/**
 * Predefined watermark templates.
 *
 * Each template defines the visual style of the watermark card.
 */
enum class WatermarkTemplate(
    val displayName: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val textColor: Int,
    val description: String
) {
    /**
     * Blue glassmorphism — for security/patrol scenarios.
     */
    DUTY(
        displayName = "执勤",
        backgroundColor = 0xFF2B6AFF.toInt(),
        textColor = 0xFFFFFFFF.toInt(),
        description = "安保、巡逻"
    ),

    /**
     * Orange glassmorphism — for construction/supervision scenarios.
     */
    ENGINEERING(
        displayName = "工程",
        backgroundColor = 0xFFFF8C42.toInt(),
        textColor = 0xFF000000.toInt(),
        description = "施工、监理"
    ),

    /**
     * Green glassmorphism — for attendance/check-in scenarios.
     */
    ATTENDANCE(
        displayName = "考勤",
        backgroundColor = 0xFF52C41A.toInt(),
        textColor = 0xFFFFFFFF.toInt(),
        description = "打卡、签到"
    ),

    /**
     * Gray glassmorphism — general purpose.
     */
    GENERAL(
        displayName = "通用",
        backgroundColor = 0xFF595959.toInt(),
        textColor = 0xFFFFFFFF.toInt(),
        description = "通用场景"
    );

    companion object {
        /**
         * Get template by display name.
         */
        fun fromDisplayName(name: String): WatermarkTemplate {
            return entries.find { it.displayName == name } ?: GENERAL
        }

        /**
         * Get default template.
         */
        fun default(): WatermarkTemplate = GENERAL
    }
}

/**
 * Watermark color themes.
 */
enum class WatermarkTheme(
    val displayName: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val textColor: Int
) {
    DEEP_BLUE_GLASS(
        displayName = "深蓝毛玻璃",
        backgroundColor = 0xFF2B6AFF.toInt(),
        textColor = 0xFFFFFFFF.toInt()
    ),
    DEEP_GRAY_GLASS(
        displayName = "深灰毛玻璃",
        backgroundColor = 0xFF595959.toInt(),
        textColor = 0xFFFFFFFF.toInt()
    ),
    WHITE_GLASS(
        displayName = "纯白毛玻璃",
        backgroundColor = 0xFFFFFFFF.toInt(),
        textColor = 0xFF000000.toInt()
    ),
    TRANSPARENT_BLACK(
        displayName = "透明黑",
        backgroundColor = 0x00000000,
        textColor = 0xFFFFFFFF.toInt()
    )
}
