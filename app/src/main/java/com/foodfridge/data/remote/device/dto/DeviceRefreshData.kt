package com.foodfridge.data.remote.device.dto

/**
 * 心跳保活请求体中的设备信息。
 */
data class DeviceRefreshData(
    val device_id: String,
    val timestamp: Long,
)
