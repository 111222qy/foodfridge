package com.foodfridge.data.remote.dto

data class FoodEarlyWarningsRequest(
    val d_id: Int,
    val o_id: Int,
    val warning_time: Int
)
