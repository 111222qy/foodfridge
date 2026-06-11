package com.foodfridge.data.remote.dto

data class ApiResponse<T>(
    val code: Int,
    val msg: String,
    val data: T?,
    val _t: Long? = null,
    val queries: Int? = null
)
