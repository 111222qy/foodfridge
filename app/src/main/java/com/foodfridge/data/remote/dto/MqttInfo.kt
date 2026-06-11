package com.foodfridge.data.remote.dto

data class MqttInfo(
    val client_id: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int,
    val use_tsl: Boolean
)
