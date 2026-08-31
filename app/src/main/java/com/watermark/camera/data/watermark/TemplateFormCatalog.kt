package com.watermark.camera.data.watermark

import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkTemplate
import org.json.JSONObject

/**
 * 各模板独立、精简可填字段（时间/地点自动）。
 * storageKey = TEMPLATE_fieldKey，模板间互不覆盖。
 */
object TemplateFormCatalog {

    data class EditableField(
        val key: String,
        val label: String,
        val hint: String
    )

    data class Spec(
        val title: String,
        val slogan: String,
        val editable: List<EditableField>
    )

    fun specOf(template: WatermarkTemplate): Spec = when (template) {
        WatermarkTemplate.PROPERTY_INSPECTION -> Spec(
            "物业巡检", "规范巡检 · 保障安全",
            listOf(
                EditableField("person", "巡检人", "请输入巡检人姓名"),
                EditableField("item", "巡检项", "请输入巡检项目")
            )
        )
        WatermarkTemplate.DUTY -> Spec(
            "执勤水印", "忠于职守 · 保障安全",
            listOf(
                EditableField("person", "执勤人", "请输入执勤人姓名"),
                EditableField("post", "岗位", "请输入执勤岗位")
            )
        )
        WatermarkTemplate.ENGINEERING -> Spec(
            "工程水印", "精益求精 · 匠心工程",
            listOf(
                EditableField("project", "工程名称", "请输入工程名称"),
                EditableField("person", "施工人", "请输入施工人姓名")
            )
        )
        WatermarkTemplate.ATTENDANCE -> Spec(
            "考勤水印", "准时考勤 · 诚信自律",
            listOf(
                EditableField("person", "姓名", "请输入姓名"),
                EditableField("jobId", "工号", "请输入工号")
            )
        )
        WatermarkTemplate.EVIDENCE -> Spec(
            "取证水印", "真实取证 · 有据可查",
            listOf(
                EditableField("person", "取证人", "请输入取证人"),
                EditableField("content", "取证内容", "请简要描述取证内容")
            )
        )
        WatermarkTemplate.GENERAL, WatermarkTemplate.WORK_REPORT -> Spec(
            "通用水印", "真实记录 · 规范留存",
            listOf(
                EditableField("person", "记录人", "请输入记录人"),
                EditableField("content", "内容", "请输入记录内容")
            )
        )
    }

    fun storageKey(template: WatermarkTemplate, fieldKey: String): String =
        "${template.name}_$fieldKey"

    fun readField(config: WatermarkConfig, template: WatermarkTemplate, fieldKey: String): String {
        return try {
            val jo = JSONObject(config.fieldsJson.ifBlank { "{}" })
            jo.optString(storageKey(template, fieldKey), "").ifBlank {
                legacy(config, fieldKey)
            }
        } catch (_: Exception) {
            legacy(config, fieldKey)
        }
    }

    private fun legacy(config: WatermarkConfig, fieldKey: String): String = when (fieldKey) {
        "person" -> config.name
        "project", "item", "post", "jobId" -> config.projectName
        "content" -> config.customTitle.ifBlank { config.remark }
        else -> ""
    }

    fun writeFields(
        config: WatermarkConfig,
        template: WatermarkTemplate,
        values: Map<String, String>
    ): WatermarkConfig {
        val jo = try {
            JSONObject(config.fieldsJson.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        values.forEach { (k, v) -> jo.put(storageKey(template, k), v.trim()) }
        val person = values["person"] ?: readField(config, template, "person")
        val project = values["project"] ?: values["item"] ?: values["post"]
            ?: values["jobId"] ?: readField(config, template, "project")
        val content = values["content"] ?: readField(config, template, "content")
        return config.copy(
            fieldsJson = jo.toString(),
            name = person,
            projectName = project,
            customTitle = content,
            remark = values["content"] ?: config.remark,
            template = template
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
