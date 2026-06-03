package com.foodfridge.domain.repository

import com.foodfridge.domain.model.TemperatureRecord
import kotlinx.coroutines.flow.Flow

interface TemperatureRepository {
    fun getLatestTemperature(): Flow<TemperatureRecord?>

    suspend fun insertTemperature(record: TemperatureRecord)
}
