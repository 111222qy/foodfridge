package com.foodfridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foodfridge.data.local.entity.PendingUploadEntity

@Dao
interface PendingUploadDao {

    @Query("SELECT * FROM pending_uploads WHERE type = :type ORDER BY created_at ASC")
    suspend fun getPendingByType(type: String): List<PendingUploadEntity>

    @Query("SELECT * FROM pending_uploads ORDER BY created_at ASC")
    suspend fun getAllPending(): List<PendingUploadEntity>

    @Query("SELECT * FROM pending_uploads ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingBatch(limit: Int): List<PendingUploadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingUploadEntity): Long

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("UPDATE pending_uploads SET retry_count = retry_count + 1, last_error = :lastError WHERE id = :id")
    suspend fun incrementRetry(id: Int, lastError: String?)

    @Query("DELETE FROM pending_uploads WHERE retry_count >= :maxRetries OR created_at < :olderThan")
    suspend fun prune(maxRetries: Int, olderThan: Long)
}
