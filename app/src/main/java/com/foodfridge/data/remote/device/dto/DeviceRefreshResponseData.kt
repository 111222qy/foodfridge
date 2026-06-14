package com.foodfridge.data.remote.device.dto

/**
 * 心跳保活响应 data 字段。
 */
data class DeviceRefreshResponseData(
    val device_id: String?,
    val status: String?,
    val last_heartbeat: String?,
)
