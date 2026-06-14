package com.foodfridge.data.remote.device.dto

/**
 * 温度上报响应 data 字段。
 */
data class TemperatureUploadResponseData(
    val recordId: String?,
    val processTime: Int?,
)
