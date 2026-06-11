package com.foodfridge.data.remote.dto

data class ClearNotEntryFoodsRequest(
    val d_id: Int,
    val o_id: Int,
    val food_ids: List<Int>,
    val record_ids: List<Int>? = null
)
