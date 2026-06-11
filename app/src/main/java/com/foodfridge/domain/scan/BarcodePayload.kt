package com.foodfridge.domain.scan

/**
 * 从 QR 码解析出的留样信息载荷
 *
 * 格式: SS|1|菜品名|时间|重量|餐次
 */
data class BarcodePayload(
    val version: Int,
    val dishName: String,
    val timestamp: Long,
    val weightGrams: Float,
    val mealType: String,
)
