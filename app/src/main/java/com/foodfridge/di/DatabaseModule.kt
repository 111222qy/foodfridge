package com.foodfridge.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foodfridge.data.local.AppDatabase
import com.foodfridge.data.local.dao.FoodSampleDao
import com.foodfridge.data.local.dao.PendingUploadDao
import com.foodfridge.data.local.dao.TemperatureDao
import com.foodfridge.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN face_photo_path TEXT")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_uploads (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                retry_count INTEGER NOT NULL,
                last_error TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_uploads_type_created_at ON pending_uploads(type, created_at)"
        )
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_food_samples_meal_type_created_at")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_food_samples_meal_type_store_time " +
                "ON food_samples(meal_type, store_time)"
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE food_samples ADD COLUMN disposed_at INTEGER")
        db.execSQL("ALTER TABLE food_samples ADD COLUMN disposed_by_user_id INTEGER")
        db.execSQL("ALTER TABLE food_samples ADD COLUMN disposed_by_employee_id TEXT")
        db.execSQL("ALTER TABLE food_samples ADD COLUMN disposed_by_name TEXT")
        db.execSQL("ALTER TABLE food_samples ADD COLUMN disposed_by_role TEXT")
    }
}

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
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL(
                    "INSERT OR IGNORE INTO users (full_name, employee_id, role, is_active, password) VALUES ('管理员', 'admin', 'ADMIN', 1, NULL)"
                )
            }
        })
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()
    }

    @Provides
    @Singleton
    fun providePendingUploadDao(db: AppDatabase): PendingUploadDao {
        return db.pendingUploadDao()
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
