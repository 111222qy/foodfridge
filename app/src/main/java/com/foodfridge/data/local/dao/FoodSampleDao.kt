package com.foodfridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foodfridge.data.local.entity.FoodSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodSampleDao {

    @Query("SELECT * FROM food_samples WHERE meal_type = :mealType AND created_at >= :dayStart AND created_at < :dayEnd ORDER BY created_at DESC")
    fun getSamplesByMealAndDate(mealType: String, dayStart: Long, dayEnd: Long): Flow<List<FoodSampleEntity>>

    @Query("SELECT * FROM food_samples WHERE status = 'STORING' AND expire_time <= :now")
    suspend fun getExpiredSamples(now: Long): List<FoodSampleEntity>

    @Query("UPDATE food_samples SET status = :newStatus WHERE id = :id")
    suspend fun updateStatus(id: Int, newStatus: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: FoodSampleEntity): Long

    @Query("SELECT * FROM food_samples ORDER BY created_at DESC")
    fun getAllSamples(): Flow<List<FoodSampleEntity>>

    @Query("SELECT * FROM food_samples WHERE meal_type = :mealType ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestSampleByMeal(mealType: String): FoodSampleEntity?
}
