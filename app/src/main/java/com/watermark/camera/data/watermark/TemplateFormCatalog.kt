package com.watermark.camera.data.watermark

import com.watermark.camera.data.model.WatermarkTemplate

/**
 * 设计稿字段目录 —— 与预览/成片绘制共用。
 * 作为「资源卡片」的结构化描述，而不是位图依赖。
 */
object TemplateFormCatalog {

    data class Field(
        val label: String,
        /** name | project | remark | location | time | content | status */
        val key: String
    )

    data class Spec(
        val title: String,
        val slogan: String,
        /** 每行最多 2 个字段；单字段行 right 为 null */
        val rows: List<Pair<Field, Field?>>
    )

    fun specOf(template: WatermarkTemplate): Spec = when (template) {
        WatermarkTemplate.PROPERTY_INSPECTION -> Spec(
            title = "物业巡检",
            slogan = "规范巡检 · 保障安全",
            rows = listOf(
                Field("时间:", "time") to Field("巡检人:", "name"),
                Field("地点:", "location") to null,
                Field("巡检项:", "project") to Field("备注:", "remark")
            )
        )
        WatermarkTemplate.DUTY -> Spec(
            title = "执勤水印",
            slogan = "忠于职守 · 保障安全",
            rows = listOf(
                Field("时间:", "time") to Field("执勤人:", "name"),
                Field("地点:", "location") to null,
                Field("岗位:", "project") to Field("备注:", "remark")
            )
        )
        WatermarkTemplate.ENGINEERING -> Spec(
            title = "工程水印",
            slogan = "精益求精 · 匠心工程",
            rows = listOf(
                Field("时间:", "time") to Field("施工内容:", "content"),
                Field("工程名称:", "project") to Field("施工人:", "name"),
                Field("施工部位:", "remark") to Field("备注:", "remark")
            )
        )
        WatermarkTemplate.ATTENDANCE -> Spec(
            title = "考勤水印",
            slogan = "准时考勤 · 诚信自律",
            rows = listOf(
                Field("时间:", "time") to Field("工号:", "project"),
                Field("地点:", "location") to Field("状态:", "status"),
                Field("姓名:", "name") to Field("备注:", "remark")
            )
        )
        WatermarkTemplate.EVIDENCE -> Spec(
            title = "取证水印",
            slogan = "真实取证 · 有据可查",
            rows = listOf(
                Field("时间:", "time") to Field("取证人:", "name"),
                Field("地点:", "location") to Field("备注:", "remark"),
                Field("取证内容:", "content") to null
            )
        )
        WatermarkTemplate.GENERAL, WatermarkTemplate.WORK_REPORT -> Spec(
            title = "通用水印",
            slogan = "真实记录 · 规范留存",
            rows = listOf(
                Field("时间:", "time") to Field("记录人:", "name"),
                Field("地点:", "location") to Field("备注:", "remark"),
                Field("内容:", "content") to null
            )
        )
    }

    fun drawableName(template: WatermarkTemplate): String = when (template) {
        WatermarkTemplate.PROPERTY_INSPECTION -> "bg_wm_card_property"
        WatermarkTemplate.DUTY -> "bg_wm_card_duty"
        WatermarkTemplate.ENGINEERING -> "bg_wm_card_engineering"
        WatermarkTemplate.ATTENDANCE -> "bg_wm_card_attendance"
        WatermarkTemplate.EVIDENCE -> "bg_wm_card_evidence"
        else -> "bg_wm_card_general"
    }
}
