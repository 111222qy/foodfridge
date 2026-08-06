package com.foodfridge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 设备平台上报失败后的本地缓存条目。
 */
@Entity(
    tableName = "pending_uploads",
    indices = [
        Index(value = ["type", "created_at"]),
    ],
)
data class PendingUploadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
)
