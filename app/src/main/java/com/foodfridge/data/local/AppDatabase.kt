package com.foodfridge.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.foodfridge.data.local.dao.FoodSampleDao
import com.foodfridge.data.local.dao.PendingUploadDao
import com.foodfridge.data.local.dao.TemperatureDao
import com.foodfridge.data.local.dao.UserDao
import com.foodfridge.data.local.entity.FoodSampleEntity
import com.foodfridge.data.local.entity.PendingUploadEntity
import com.foodfridge.data.local.entity.TemperatureRecordEntity
import com.foodfridge.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        FoodSampleEntity::class,
        TemperatureRecordEntity::class,
        PendingUploadEntity::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun foodSampleDao(): FoodSampleDao
    abstract fun temperatureDao(): TemperatureDao
    abstract fun pendingUploadDao(): PendingUploadDao

    companion object {
        const val DATABASE_NAME = "food_fridge_db"
    }
}
