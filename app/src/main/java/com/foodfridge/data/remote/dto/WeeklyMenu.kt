package com.foodfridge.data.remote.dto

data class WeeklyMenu(
    val menu_id: Int,
    val name: String,
    val menu_type: String,
    val week: Int,
    val is_continue_to_used: Boolean,
    val meal_type_foods: List<MealTypeFood>
)
