package com.foodfridge.data.remote.device.dto

/**
 * 设备接入平台通用请求包装。
 *
 * 与平台通用响应格式对齐：code / message / data。
 * 设备单条业务数据放在 [data] 中。
 */
data class DeviceApiRequest<T>(
    val code: Int = 200,
    val message: String = "success",
    val data: T,
)
