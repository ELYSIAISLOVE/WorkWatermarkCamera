package com.watermark.camera.data.location

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener

/**
 * 高德纯定位封装（不加载地图 SDK）。
 * 使用前请在 Manifest 配置 com.amap.api.v2.apikey，并在 Application 中调用
 * AMapLocationClient.updatePrivacyShow / updatePrivacyAgree。
 */
class AmapLocationHelper(private val context: Context) {

    companion object {
        private const val TAG = "AmapLocationHelper"
    }

    private var client: AMapLocationClient? = null

    data class Result(
        val latitude: Double,
        val longitude: Double,
        val address: String,
        val accuracy: Float,
        val provider: String = "amap"
    )

    fun start(onResult: (Result?) -> Unit) {
        try {
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)
            val c = AMapLocationClient(context.applicationContext)
            val opt = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = true
                isGpsFirst = true
                gpsFirstTimeout = 10000
                httpTimeOut = 20000
                isLocationCacheEnable = false
            }
            c.setLocationOption(opt)
            c.setLocationListener(AMapLocationListener { loc: AMapLocation? ->
                if (loc != null && loc.errorCode == 0) {
                    val addr = buildString {
                        if (!loc.province.isNullOrBlank()) append(loc.province)
                        if (!loc.city.isNullOrBlank()) append(loc.city)
                        if (!loc.district.isNullOrBlank()) append(loc.district)
                        if (!loc.street.isNullOrBlank()) append(loc.street)
                        if (!loc.aoiName.isNullOrBlank() && isEmpty()) append(loc.aoiName)
                    }.ifBlank { loc.address ?: "" }
                    onResult(
                        Result(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            address = addr,
                            accuracy = loc.accuracy
                        )
                    )
                } else {
                    Log.w(TAG, "amap error=${loc?.errorCode} ${loc?.errorInfo}")
                    onResult(null)
                }
                runCatching { c.stopLocation() }
            })
            client = c
            c.startLocation()
        } catch (e: Exception) {
            Log.e(TAG, "amap start failed", e)
            onResult(null)
        }
    }

    fun stop() {
        runCatching {
            client?.stopLocation()
            client?.onDestroy()
        }
        client = null
    }
}
