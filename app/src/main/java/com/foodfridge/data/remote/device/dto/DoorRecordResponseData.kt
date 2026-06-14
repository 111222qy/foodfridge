package com.foodfridge.data.remote.device.dto

/**
 * 开关门记录上报响应 data 字段。
 */
data class DoorRecordResponseData(
    val recordId: String?,
    val processTime: Int?,
)
