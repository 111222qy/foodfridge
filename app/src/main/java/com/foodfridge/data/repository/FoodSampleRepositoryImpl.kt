package com.foodfridge.data.repository

import com.foodfridge.data.local.dao.FoodSampleDao
import com.foodfridge.data.local.entity.FoodSampleEntity
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.domain.repository.FoodSampleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class FoodSampleRepositoryImpl @Inject constructor(
    private val foodSampleDao: FoodSampleDao
) : FoodSampleRepository {

    override fun getSamplesByMealAndDate(mealType: String, dayStart: Long, dayEnd: Long): Flow<List<FoodSample>> {
        return foodSampleDao.getSamplesByMealAndDate(mealType, dayStart, dayEnd).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getExpiredSamples(now: Long): List<FoodSample> {
        return foodSampleDao.getExpiredSamples(now).map { it.toDomain() }
    }

    override suspend fun updateStatus(id: Int, newStatus: String) {
        foodSampleDao.updateStatus(id, newStatus)
    }

    override suspend fun insertSample(sample: FoodSample): Long {
        return foodSampleDao.insert(sample.toEntity())
    }

    override fun getAllSamples(): Flow<List<FoodSample>> {
        return foodSampleDao.getAllSamples().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getLatestSampleByMeal(mealType: String): FoodSample? {
        return foodSampleDao.getLatestSampleByMeal(mealType)?.toDomain()
    }

    // Mappers
    private fun FoodSampleEntity.toDomain(): FoodSample {
        return FoodSample(
            id = id,
            operatorId = operatorId,
            operatorName = operatorName,
            foodName = foodName,
            weightGrams = weightGrams,
            mealType = MealType.valueOf(mealType),
            barcode = barcode,
            status = SampleStatus.valueOf(status),
            storeTime = storeTime,
            expireTime = expireTime,
            createdAt = createdAt
        )
    }

    private fun FoodSample.toEntity(): FoodSampleEntity {
        return FoodSampleEntity(
            id = id,
            operatorId = operatorId,
            operatorName = operatorName,
            foodName = foodName,
            weightGrams = weightGrams,
            mealType = mealType.name,
            barcode = barcode,
            status = status.name,
            storeTime = storeTime,
            expireTime = expireTime,
            createdAt = createdAt
        )
    }
}
