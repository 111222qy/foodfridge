package com.foodfridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foodfridge.data.local.entity.TemperatureRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemperatureDao {

    @Query("SELECT * FROM temperature_records ORDER BY recorded_at DESC LIMIT 1")
    fun getLatestTemperature(): Flow<TemperatureRecordEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TemperatureRecordEntity)
}
