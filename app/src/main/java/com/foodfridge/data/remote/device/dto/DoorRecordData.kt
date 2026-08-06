package com.foodfridge.data.remote.device.dto

/**
 * 开关门记录上报请求体。
 *
 * 字段与后端协商确定。
 * 一次完整的「开门 → 关门」事件结束后上传。
 */
data class DoorRecordData(
    val device_id: String,
    val timestamp: Long,
    val operator_name: String? = null,
    val open_timestamp: Long,
    val close_timestamp: Long,
)
