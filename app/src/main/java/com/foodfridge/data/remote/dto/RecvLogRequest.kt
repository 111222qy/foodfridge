package com.foodfridge.data.remote.dto

data class RecvLogRequest(
    val task_id: String? = null,
    val d_id: String? = null,
    val log_url: String,
    val start_date: String,
    val end_date: String,
    val consumer_name: String? = null,
    val account_name: String? = null,
    val source: String? = null
)
