package com.foodfridge.data.remote.device.dto

/**
 * 温度上报请求体。
 */
data class TemperatureUploadData(
    val device_id: String,
    val timestamp: Long,
    val temperature: Float,
)
