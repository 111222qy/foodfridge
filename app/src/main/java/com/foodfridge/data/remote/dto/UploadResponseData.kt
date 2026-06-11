package com.foodfridge.data.remote.dto

data class UploadResponseData(
    val url: String,
    val public_url: String,
    val private_url: String,
    val size: Int,
    val mime_type: String,
    val path: String
)
