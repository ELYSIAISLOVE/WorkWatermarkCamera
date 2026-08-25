package com.watermark.camera.data.model

import androidx.annotation.ColorInt

/**
 * Watermark templates aligned with assets/design/watermark_templates.
 * ENGINEERING/GENERAL kept for saved-config compatibility.
 */
enum class WatermarkTemplate(
    val displayName: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val textColor: Int,
    val description: String,
    val designAsset: String = ""
) {
    PROPERTY_INSPECTION(
        "物业巡检", 0xFF1B4F72.toInt(), 0xFFFFFFFF.toInt(),
        "物业、巡检记录",
        "design/watermark_templates/watermark_property_inspection.svg"
    ),
    DUTY(
        "执勤", 0xFF2B6AFF.toInt(), 0xFFFFFFFF.toInt(),
        "安保、巡逻",
        "design/watermark_templates/watermark_duty.svg"
    ),
    ATTENDANCE(
        "考勤", 0xFF52C41A.toInt(), 0xFFFFFFFF.toInt(),
        "打卡、签到",
        "design/watermark_templates/watermark_attendance.svg"
    ),
    WORK_REPORT(
        "工作汇报", 0xFF7A4B2C.toInt(), 0xFFFFFFFF.toInt(),
        "工作汇报记录",
        "design/watermark_templates/watermark_work_report.svg"
    ),
    EVIDENCE(
        "取证", 0xFF8B1A1A.toInt(), 0xFFFFFFFF.toInt(),
        "拍照取证",
        "design/watermark_templates/watermark_evidence.svg"
    ),
    ENGINEERING(
        "工程", 0xFFFF8C42.toInt(), 0xFF000000.toInt(),
        "施工、监理"
    ),
    GENERAL(
        "自定义", 0xFF595959.toInt(), 0xFFFFFFFF.toInt(),
        "可自行输入文字"
    );


    /**
     * 选单 / 预览 / 成片统一标题。
     * 例：执勤水印、考勤水印、取证水印、工程水印；
     * 自定义模板优先用用户输入，否则「自定义水印」。
     */
    fun cardTitle(customTitle: String = ""): String = when (this) {
        GENERAL -> {
            val c = customTitle.trim()
            when {
                c.isEmpty() -> "自定义水印"
                else -> c
            }
        }
        else -> "${displayName}水印"
    }

    companion object {
        fun fromDisplayName(name: String): WatermarkTemplate =
            entries.find { it.displayName == name } ?: GENERAL

        fun default(): WatermarkTemplate = WORK_REPORT

        fun menuEntries(): List<WatermarkTemplate> = listOf(
            PROPERTY_INSPECTION, DUTY, ATTENDANCE, WORK_REPORT, EVIDENCE, ENGINEERING, GENERAL
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
