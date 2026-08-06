package com.foodfridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.foodfridge.data.local.entity.FoodSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodSampleDao {

    @Query("SELECT * FROM food_samples WHERE meal_type = :mealType AND store_time >= :dayStart AND store_time < :dayEnd ORDER BY created_at DESC")
    fun getSamplesByMealAndDate(mealType: String, dayStart: Long, dayEnd: Long): Flow<List<FoodSampleEntity>>

    @Query("SELECT * FROM food_samples WHERE status = 'STORING' AND expire_time <= :now")
    suspend fun getExpiredSamples(now: Long): List<FoodSampleEntity>

    @Query("UPDATE food_samples SET status = :newStatus WHERE id = :id")
    suspend fun updateStatus(id: Int, newStatus: String)

    @Update
    suspend fun update(sample: FoodSampleEntity)

    @Query(
        """
        UPDATE food_samples
        SET status = 'DISPOSED',
            disposed_at = :disposedAt,
            disposed_by_user_id = :disposedByUserId,
            disposed_by_employee_id = :disposedByEmployeeId,
            disposed_by_name = :disposedByName,
            disposed_by_role = :disposedByRole
        WHERE id IN (:sampleIds)
          AND status IN ('STORING', 'WAITING_DISPOSE')
        """
    )
    suspend fun disposeSamples(
        sampleIds: List<Int>,
        disposedAt: Long,
        disposedByUserId: Int?,
        disposedByEmployeeId: String?,
        disposedByName: String,
        disposedByRole: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: FoodSampleEntity): Long

    @Query("SELECT * FROM food_samples ORDER BY created_at DESC")
    fun getAllSamples(): Flow<List<FoodSampleEntity>>

    @Query("SELECT * FROM food_samples WHERE meal_type = :mealType ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestSampleByMeal(mealType: String): FoodSampleEntity?

    @Query("SELECT * FROM food_samples WHERE status IN ('STORING', 'WAITING_DISPOSE') ORDER BY store_time ASC")
    suspend fun getActiveSamples(): List<FoodSampleEntity>

    @Query("DELETE FROM food_samples")
    suspend fun deleteAllSamples()
}
