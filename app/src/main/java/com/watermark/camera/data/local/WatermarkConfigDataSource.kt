package com.watermark.camera.data.local

import android.content.Context
import android.content.SharedPreferences
import com.watermark.camera.data.model.WatermarkConfig
import com.watermark.camera.data.model.WatermarkPosition
import com.watermark.camera.data.model.WatermarkTemplate
import com.watermark.camera.data.model.TimeStyle
import com.watermark.camera.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local data source for watermark configuration.
 *
 * Persists watermark settings using SharedPreferences (JSON format).
 * All operations are suspend functions for coroutine compatibility.
 */
@Singleton
class WatermarkConfigDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WatermarkConfigDS"
        private const val PREFS_NAME = "watermark_config"
        private const val KEY_CONFIG = "watermark_config_json"
        private const val KEY_FIRST_LAUNCH = "first_launch"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Load watermark configuration from storage.
     *
     * @return Stored config, or default config if not found or parse error.
     */
    suspend fun loadConfig(): WatermarkConfig = withContext(Dispatchers.IO) {
        try {
            val jsonStr = prefs.getString(KEY_CONFIG, null)
            if (jsonStr == null) {
                Logger.i(TAG, "No saved config found, using default")
                return@withContext WatermarkConfig()
            }

            parseConfigFromJson(jsonStr)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load config, using default", e)
            WatermarkConfig()
        }
    }

    /**
     * Save watermark configuration to storage.
     *
     * @param config The configuration to save.
     * @return True if saved successfully.
     */
    suspend fun saveConfig(config: WatermarkConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = configToJson(config)
            prefs.edit().putString(KEY_CONFIG, json.toString()).commit()
            Logger.i(TAG, "Config saved successfully")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save config", e)
            false
        }
    }

    /**
     * Reset configuration to defaults.
     */
    suspend fun resetConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            prefs.edit().remove(KEY_CONFIG).commit()
            Logger.i(TAG, "Config reset to default")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to reset config", e)
            false
        }
    }

    /**
     * Check if this is the first app launch (for onboarding).
     */
    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    /**
     * Mark first launch as completed.
     */
    fun markFirstLaunchComplete() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    // ==================== JSON Serialization ====================

    private fun configToJson(config: WatermarkConfig): JSONObject {
        return JSONObject().apply {
            put("template", config.template.name)
            put("timeStyle", config.timeStyle.name)
            put("customTitle", config.customTitle)
            put("fieldsJson", config.fieldsJson)
            put("name", config.name)
            put("projectName", config.projectName)
            put("remark", config.remark)
            put("location", config.location)
            put("transparency", config.transparency)
            put("fontScale", config.fontScale)
            put("position", config.position.name)
            if (config.customX != null) put("customX", config.customX.toDouble())
            if (config.customY != null) put("customY", config.customY.toDouble())
            put("useGyroscope", config.useGyroscope)
            put("showLocation", config.showLocation)
        }
    }

    private fun parseConfigFromJson(jsonStr: String): WatermarkConfig {
        val json = JSONObject(jsonStr)
        return WatermarkConfig(
            template = try {
                val raw = WatermarkTemplate.valueOf(json.getString("template"))
                // 旧「工作汇报」迁移到物业巡检，避免继续走已下线样式
                if (raw == WatermarkTemplate.WORK_REPORT) WatermarkTemplate.PROPERTY_INSPECTION else raw
            } catch (e: Exception) {
                WatermarkTemplate.PROPERTY_INSPECTION
            },
            timeStyle = try {
                TimeStyle.valueOf(json.optString("timeStyle", "DEFAULT"))
            } catch (e: Exception) {
                TimeStyle.DEFAULT
            },
            customTitle = json.optString("customTitle", ""),
            fieldsJson = json.optString("fieldsJson", "{}"),
            name = json.optString("name", ""),
            projectName = json.optString("projectName", ""),
            remark = json.optString("remark", ""),
            location = json.optString("location", ""),
            transparency = json.optDouble("transparency", 0.8).toFloat(),
            fontScale = 2.5f,
            position = try {
                WatermarkPosition.valueOf(json.getString("position"))
            } catch (e: Exception) {
                WatermarkPosition.BOTTOM_LEFT
            },
            customX = if (json.has("customX")) json.getDouble("customX").toFloat() else null,
            customY = if (json.has("customY")) json.getDouble("customY").toFloat() else null,
            useGyroscope = json.optBoolean("useGyroscope", true),
            showLocation = json.optBoolean("showLocation", true)
        )
    }
}
