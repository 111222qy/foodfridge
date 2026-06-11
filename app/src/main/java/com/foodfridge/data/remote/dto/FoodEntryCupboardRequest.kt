package com.foodfridge.data.remote.dto

data class FoodEntryCupboardRequest(
    val d_id: Int,
    val o_id: Int,
    val food_ids: List<Int>,
    val meal_type: String,
    val sample_entry_user: List<SampleEntryUser>,
    val temperature: Double
)
