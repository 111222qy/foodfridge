package com.foodfridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(
    tableName = "food_samples",
    indices = [
        Index(value = ["meal_type", "created_at"]),
        Index(value = ["status", "expire_time"])
    ]
)
data class FoodSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "operator_id")
    val operatorId: Int,

    @ColumnInfo(name = "operator_name")
    val operatorName: String,

    @ColumnInfo(name = "food_name")
    val foodName: String,

    @ColumnInfo(name = "weight_grams")
    val weightGrams: Float,

    @ColumnInfo(name = "meal_type")
    val mealType: String, // BREAKFAST, LUNCH, DINNER

    @ColumnInfo(name = "barcode")
    val barcode: String,

    @ColumnInfo(name = "status")
    val status: String, // WAITING, STORING, WAITING_DISPOSE

    @ColumnInfo(name = "store_time")
    val storeTime: Long,

    @ColumnInfo(name = "expire_time")
    val expireTime: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
