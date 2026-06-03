package com.foodfridge.di

import android.content.Context
import androidx.room.Room
import com.foodfridge.data.local.AppDatabase
import com.foodfridge.data.local.dao.FoodSampleDao
import com.foodfridge.data.local.dao.TemperatureDao
import com.foodfridge.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL(
                    "INSERT OR IGNORE INTO users (full_name, employee_id, role, is_active, password) VALUES ('管理员', 'admin', 'ADMIN', 1, NULL)"
                )
            }
        })
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    @Singleton
    fun provideUserDao(db: AppDatabase): UserDao {
        return db.userDao()
    }

    @Provides
    @Singleton
    fun provideFoodSampleDao(db: AppDatabase): FoodSampleDao {
        return db.foodSampleDao()
    }

    @Provides
    @Singleton
    fun provideTemperatureDao(db: AppDatabase): TemperatureDao {
        return db.temperatureDao()
    }
}
