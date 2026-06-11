package com.foodfridge.data.remote.dto

data class AdminAccountRequest(
    val org_no: String,
    val device_no: String,
    val mode: String,
    val utoken: String
)
