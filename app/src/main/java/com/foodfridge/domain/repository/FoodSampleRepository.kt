package com.foodfridge.domain.repository

import com.foodfridge.domain.model.FoodSample
import kotlinx.coroutines.flow.Flow

interface FoodSampleRepository {
    fun getSamplesByMealAndDate(mealType: String, dayStart: Long, dayEnd: Long): Flow<List<FoodSample>>

    suspend fun getExpiredSamples(now: Long): List<FoodSample>

    suspend fun updateStatus(id: Int, newStatus: String)

    suspend fun updateSample(sample: FoodSample)

    suspend fun disposeSamples(
        sampleIds: List<Int>,
        disposedAt: Long,
        disposedByUserId: Int?,
        disposedByEmployeeId: String?,
        disposedByName: String,
        disposedByRole: String,
    ): Int

    suspend fun insertSample(sample: FoodSample): Long

    fun getAllSamples(): Flow<List<FoodSample>>

    suspend fun getLatestSampleByMeal(mealType: String): FoodSample?

    suspend fun getLatestSamplesByMealAndDate(mealType: String, dayStart: Long, dayEnd: Long): List<FoodSample>

    suspend fun getActiveSamples(): List<FoodSample>

    suspend fun deleteAllSamples()
}
