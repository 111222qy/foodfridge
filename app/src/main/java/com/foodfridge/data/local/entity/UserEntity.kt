package com.foodfridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    @ColumnInfo(name = "full_name")
    val fullName: String,
    
    @ColumnInfo(name = "employee_id")
    val employeeId: String,
    
    @ColumnInfo(name = "role")
    val role: String = "SAMPLER",
    
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,


    @ColumnInfo(name = "password")
    val password: String? = null,

    @ColumnInfo(name = "face_embedding", typeAffinity = ColumnInfo.BLOB)
    val faceEmbedding: ByteArray? = null
)
