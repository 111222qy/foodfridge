package com.foodfridge.data.remote.device.dto

/**
 * 留样上报请求体。
 *
 * 字段与《设备接入方案》第 4 节“智能留样称”对齐，但不上传图片（无 photo 字段）。
 */
data class SamplingUploadData(
    val device_id: String,
    val timestamp: Long,
    val dish_name: String,
    val stall_name: String? = null,
    val operator_name: String? = null,
    val weight: Float,
)
