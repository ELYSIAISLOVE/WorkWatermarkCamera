package com.watermark.camera.data.watermark

import android.graphics.Color
import com.watermark.camera.data.model.WatermarkTemplate

/**
 * 七套水印模板的视觉参数表（规范 §8 / `docs/07` 批次 3）：
 * 所有模板共用一套参数模型，靠 [accent]、[cornerFactor]、[headerStyle] 与页脚对齐方式区分，
 * 不再在每个模板里各写一套绘制分支。
 *
 * 注意：卡片正文始终保持浅底深字，保证户外强光下时间与地点可读（留证优先）。
 */
object CardSkinCatalog {

    enum class HeaderStyle {
        /** 表头纯色 */
        SOLID,

        /** 表头由深色渐变到主色，观感更立体 */
        GRADIENT,

        /** 表头主色 + 底部一条高亮细线，适合需要强调条目的模板 */
        ACCENT_BAR
    }

    enum class FooterAlign { CENTER, LEFT }

    data class CardSkin(
        val accent: Int,
        val cornerFactor: Float,
        val headerStyle: HeaderStyle,
        val footerAlign: FooterAlign
    )

    private val DEFAULT = CardSkin(
        accent = 0xFF4A5568.toInt(),
        cornerFactor = 0.48f,
        headerStyle = HeaderStyle.SOLID,
        footerAlign = FooterAlign.CENTER
    )

    private val skins: Map<WatermarkTemplate, CardSkin> = mapOf(
        // 通用水印：中性深灰、圆角适中
        WatermarkTemplate.GENERAL to DEFAULT,
        // 物业巡检：品牌绿、圆角略小、渐变表头
        WatermarkTemplate.PROPERTY_INSPECTION to CardSkin(
            accent = 0xFF2A6B4F.toInt(),
            cornerFactor = 0.40f,
            headerStyle = HeaderStyle.GRADIENT,
            footerAlign = FooterAlign.LEFT
        ),
        // 执勤水印：深蓝、直角感更强、强调条
        WatermarkTemplate.DUTY to CardSkin(
            accent = 0xFF1B5EA8.toInt(),
            cornerFactor = 0.30f,
            headerStyle = HeaderStyle.ACCENT_BAR,
            footerAlign = FooterAlign.LEFT
        ),
        // 工程水印：橙、圆角小、强调条
        WatermarkTemplate.ENGINEERING to CardSkin(
            accent = 0xFFD97A2B.toInt(),
            cornerFactor = 0.34f,
            headerStyle = HeaderStyle.ACCENT_BAR,
            footerAlign = FooterAlign.LEFT
        ),
        // 考勤水印：青、居中页脚
        WatermarkTemplate.ATTENDANCE to CardSkin(
            accent = 0xFF1A8A9A.toInt(),
            cornerFactor = 0.44f,
            headerStyle = HeaderStyle.SOLID,
            footerAlign = FooterAlign.CENTER
        ),
        // 取证水印：紫、渐变、左侧页脚
        WatermarkTemplate.EVIDENCE to CardSkin(
            accent = 0xFF5C3D9A.toInt(),
            cornerFactor = 0.40f,
            headerStyle = HeaderStyle.GRADIENT,
            footerAlign = FooterAlign.LEFT
        ),
        // 工作汇报：棕、渐变
        WatermarkTemplate.WORK_REPORT to CardSkin(
            accent = 0xFF7A4B2C.toInt(),
            cornerFactor = 0.46f,
            headerStyle = HeaderStyle.GRADIENT,
            footerAlign = FooterAlign.LEFT
        )
    )

    fun of(template: WatermarkTemplate): CardSkin = skins[template] ?: DEFAULT

    /** 表头渐变的起点色：主色压暗，避免整块过亮。 */
    fun gradientStart(accent: Int): Int = Color.argb(
        255,
        (Color.red(accent) * 0.55f).toInt(),
        (Color.green(accent) * 0.55f).toInt(),
        (Color.blue(accent) * 0.55f).toInt()
    )
}