package com.foodfridge.data.remote.device.dto

/**
 * 设备接入平台通用响应包装。
 */
data class DeviceApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?,
)
