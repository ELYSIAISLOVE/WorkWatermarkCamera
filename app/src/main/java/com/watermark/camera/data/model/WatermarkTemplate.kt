package com.watermark.camera.data.model

import androidx.annotation.ColorInt

/**
 * 水印模板 —— 对齐设计稿表单卡片样式。
 * GENERAL = 通用/自定义场景。
 */
enum class WatermarkTemplate(
    val displayName: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val textColor: Int,
    val description: String,
    val designAsset: String = "",
    /** 页脚标语 */
    val footerSlogan: String = "",
    /** 表头主色（含页脚） */
    @ColorInt val headerColor: Int = 0
) {
    PROPERTY_INSPECTION(
        "物业巡检", 0xFF2A6B4F.toInt(), 0xFFFFFFFF.toInt(),
        "规范巡检 · 保障安全",
        footerSlogan = "规范巡检 · 保障安全",
        headerColor = 0xFF2A6B4F.toInt()
    ),
    DUTY(
        "执勤水印", 0xFF1B5EA8.toInt(), 0xFFFFFFFF.toInt(),
        "忠于职守 · 保障安全",
        footerSlogan = "忠于职守 · 保障安全",
        headerColor = 0xFF1B5EA8.toInt()
    ),
    ENGINEERING(
        "工程水印", 0xFFD97A2B.toInt(), 0xFFFFFFFF.toInt(),
        "精益求精 · 匠心工程",
        footerSlogan = "精益求精 · 匠心工程",
        headerColor = 0xFFD97A2B.toInt()
    ),
    ATTENDANCE(
        "考勤水印", 0xFF1A8A9A.toInt(), 0xFFFFFFFF.toInt(),
        "准时考勤 · 诚信自律",
        footerSlogan = "准时考勤 · 诚信自律",
        headerColor = 0xFF1A8A9A.toInt()
    ),
    EVIDENCE(
        "取证水印", 0xFF5C3D9A.toInt(), 0xFFFFFFFF.toInt(),
        "真实取证 · 有据可查",
        footerSlogan = "真实取证 · 有据可查",
        headerColor = 0xFF5C3D9A.toInt()
    ),
    GENERAL(
        "通用水印", 0xFF4A5568.toInt(), 0xFFFFFFFF.toInt(),
        "真实记录 · 规范留存",
        footerSlogan = "真实记录 · 规范留存",
        headerColor = 0xFF4A5568.toInt()
    ),
    /** 兼容旧配置，菜单不再展示 */
    WORK_REPORT(
        "工作汇报", 0xFF7A4B2C.toInt(), 0xFFFFFFFF.toInt(),
        "工作汇报记录",
        footerSlogan = "真实记录 · 规范留存",
        headerColor = 0xFF7A4B2C.toInt()
    );

    /**
     * 选单 / 预览 / 成片统一标题。
     * 通用模板优先用户 customTitle，否则「通用水印」。
     */
    fun cardTitle(customTitle: String = ""): String = when (this) {
        GENERAL -> customTitle.trim().ifEmpty { "通用水印" }
        PROPERTY_INSPECTION -> "物业巡检"
        DUTY -> "执勤水印"
        ENGINEERING -> "工程水印"
        ATTENDANCE -> "考勤水印"
        EVIDENCE -> "取证水印"
        WORK_REPORT -> "工作汇报"
    }

    fun resolvedHeaderColor(): Int = if (headerColor != 0) headerColor else backgroundColor

    companion object {
        fun fromDisplayName(name: String): WatermarkTemplate =
            entries.find { it.displayName == name || it.cardTitle() == name } ?: GENERAL

        fun default(): WatermarkTemplate = PROPERTY_INSPECTION

        fun menuEntries(): List<WatermarkTemplate> = listOf(
            PROPERTY_INSPECTION, DUTY, ENGINEERING, ATTENDANCE, EVIDENCE, GENERAL
        )
    }
}

enum class WatermarkTheme(
    val displayName: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val textColor: Int
) {
    DEEP_BLUE_GLASS("深蓝毛玻璃", 0xFF2B6AFF.toInt(), 0xFFFFFFFF.toInt()),
    DEEP_GRAY_GLASS("深灰毛玻璃", 0xFF595959.toInt(), 0xFFFFFFFF.toInt()),
    WHITE_GLASS("纯白毛玻璃", 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
    TRANSPARENT_BLACK("透明黑", 0x00000000, 0xFFFFFFFF.toInt())
}

/**
 * Time digit styles — design assets reserved for future graphic rendering.
 */
enum class TimeStyle(
    val displayName: String,
    val designAsset: String
) {
    DEFAULT("默认", "design/time_styles/time_default.svg"),
    DIGITAL_TUBE("数码管", "design/time_styles/time_digital_tube.svg"),
    FLIP_CALENDAR("翻页日历", "design/time_styles/time_flip_calendar.svg"),
    RETRO_SLASH("复古斜线", "design/time_styles/time_retro_slash.svg");

    companion object {
        fun default(): TimeStyle = DEFAULT
        fun fromName(name: String): TimeStyle =
            entries.find { it.name == name || it.displayName == name } ?: DEFAULT
    }
}
