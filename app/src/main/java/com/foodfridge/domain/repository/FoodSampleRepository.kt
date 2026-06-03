package com.foodfridge.domain.repository

import com.foodfridge.domain.model.FoodSample
import kotlinx.coroutines.flow.Flow

interface FoodSampleRepository {
    fun getSamplesByMealAndDate(mealType: String, dayStart: Long, dayEnd: Long): Flow<List<FoodSample>>

    suspend fun getExpiredSamples(now: Long): List<FoodSample>

    suspend fun updateStatus(id: Int, newStatus: String)

    suspend fun insertSample(sample: FoodSample): Long

    fun getAllSamples(): Flow<List<FoodSample>>

    suspend fun getLatestSampleByMeal(mealType: String): FoodSample?
}
