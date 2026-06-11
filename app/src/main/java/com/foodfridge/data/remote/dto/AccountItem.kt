package com.foodfridge.data.remote.dto

data class AccountItem(
    val id: Int,
    val face_url: String?,
    val face_token: String?,
    val manage_card: String?,
    val username: String,
    val member_name: String,
    val mobile: String,
    val password: String,
    val org_name: String,
    val org_no: Int
)
