package com.foodfridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "temperature_records")
data class TemperatureRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "temperature")
    val temperature: Float,

    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long = System.currentTimeMillis()
)
