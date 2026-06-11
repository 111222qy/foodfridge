package com.foodfridge.data.remote.dto

data class GetAllMenuFoodListRequest(
    val device_id: Int,
    val menu_id: Int? = null
)
