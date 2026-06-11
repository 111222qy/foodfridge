package com.foodfridge.data.remote.dto

data class ActivateRequest(
    val device_number: String,
    val device_mac: String,
    val activation_code: String
)
