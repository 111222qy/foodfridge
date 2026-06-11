package com.foodfridge.data.remote.dto

data class GetFoodListRequest(
    val d_id: Int,
    val o_id: Int,
    val date: String? = null,
    val meal_type: String,
    val entry_cupboard: Boolean? = null,
    val use_menu: Boolean? = null
)
