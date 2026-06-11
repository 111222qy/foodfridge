package com.foodfridge.data.remote.dto

data class ActivateResponseData(
    val o_id: Int,
    val d_id: Int,
    val device_number: String,
    val device_name: String,
    val mqtt_info: MqttInfo,
    val balance_sensor_version: String,
    val tray_sensor_version: String,
    val offline_face_activation_code: String
)
