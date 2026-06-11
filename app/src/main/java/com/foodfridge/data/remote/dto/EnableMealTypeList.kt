package com.foodfridge.data.remote.dto

data class EnableMealTypeList(
    val breakfast: MealTimeInfo?,
    val lunch: MealTimeInfo?,
    val afternoon: MealTimeInfo?,
    val dinner: MealTimeInfo?,
    val supper: MealTimeInfo?,
    val morning: MealTimeInfo?
)
