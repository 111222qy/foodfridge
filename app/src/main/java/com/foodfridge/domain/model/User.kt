package com.foodfridge.domain.model

data class User(
    val id: Int,
    val fullName: String,
    val employeeId: String,
    val role: String = "SAMPLER", // SUPERVISOR(监督员), SAMPLER(留样员), ADMIN(管理员)
    val isActive: Boolean,
    val password: String? = null,
    val faceEmbedding: ByteArray? = null,
    val facePhotoPath: String? = null
)
