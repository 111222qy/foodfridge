package com.foodfridge.data.remote.device.dto

/**
 * 留样上报请求体中的设备单条信息。
 *
 * 字段与《设备接入方案》中“智能留样称”对齐。
 */
data class SamplingUploadData(
    val device_id: String,
    val timestamp: Long,
    val dish_name: String,
    val stall_name: String? = null,
    val operator_name: String? = null,
    val weight: Float,
    val photo: String? = null,
)
