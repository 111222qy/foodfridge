package com.foodfridge.data.remote.dto

data class RecognizeData(
    val company_id: String,
    val card_name: String,
    val card_phone: String,
    val card_no: String,
    val cardinfo_id: String,
    val gender: String,
    val member_card: String,
    val face_url: String,
    val face_token: String
)
