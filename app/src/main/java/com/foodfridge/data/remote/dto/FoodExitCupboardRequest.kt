package com.foodfridge.data.remote.dto

data class FoodExitCupboardRequest(
    val d_id: Int,
    val o_id: Int,
    val date: String,
    val food_ids: List<Int>,
    val ids: List<Int>,
    val sample_exit_user: List<SampleExitUser>,
    val required_storage_hours: Int
)
