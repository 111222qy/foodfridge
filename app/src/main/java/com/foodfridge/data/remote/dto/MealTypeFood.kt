package com.foodfridge.data.remote.dto

data class MealTypeFood(
    val use_date: String,
    val week_day: Int,
    val meal_type: String,
    val foods: List<Int>
)
