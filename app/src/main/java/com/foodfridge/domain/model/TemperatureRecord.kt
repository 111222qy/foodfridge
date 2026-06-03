package com.foodfridge.domain.model

data class TemperatureRecord(
    val id: Int,
    val temperature: Float,
    val recordedAt: Long
)
