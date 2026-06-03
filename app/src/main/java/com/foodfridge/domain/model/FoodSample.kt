package com.foodfridge.domain.model

data class FoodSample(
    val id: Int,
    val operatorId: Int,
    val operatorName: String,
    val foodName: String,
    val weightGrams: Float,
    val mealType: MealType,
    val barcode: String,
    val status: SampleStatus,
    val storeTime: Long,
    val expireTime: Long,
    val createdAt: Long
)
