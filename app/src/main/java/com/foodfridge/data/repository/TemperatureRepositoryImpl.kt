package com.foodfridge.data.repository

import com.foodfridge.data.local.dao.TemperatureDao
import com.foodfridge.data.local.entity.TemperatureRecordEntity
import com.foodfridge.domain.model.TemperatureRecord
import com.foodfridge.domain.repository.TemperatureRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TemperatureRepositoryImpl @Inject constructor(
    private val temperatureDao: TemperatureDao
) : TemperatureRepository {

    override fun getLatestTemperature(): Flow<TemperatureRecord?> {
        return temperatureDao.getLatestTemperature().map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun insertTemperature(record: TemperatureRecord) {
        temperatureDao.insert(record.toEntity())
    }

    private fun TemperatureRecordEntity.toDomain(): TemperatureRecord {
        return TemperatureRecord(
            id = id,
            temperature = temperature,
            recordedAt = recordedAt
        )
    }

    private fun TemperatureRecord.toEntity(): TemperatureRecordEntity {
        return TemperatureRecordEntity(
            id = id,
            temperature = temperature,
            recordedAt = recordedAt
        )
    }
}
