package com.foodfridge.data.remote.dto

data class FoodItem(
    val id: Int,
    val food_id: Int,
    val food_name: String,
    val food_image: String,
    val entry_time: String
)
