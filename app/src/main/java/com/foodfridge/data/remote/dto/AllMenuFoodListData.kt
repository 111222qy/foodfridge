package com.foodfridge.data.remote.dto

data class AllMenuFoodListData(
    val weekly_menus: List<WeeklyMenu>,
    val monthly_menus: List<Any>,
    val total_weekly: Int,
    val total_monthly: Int
)
