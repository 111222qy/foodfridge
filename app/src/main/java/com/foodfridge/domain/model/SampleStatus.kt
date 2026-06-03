package com.foodfridge.domain.model

enum class SampleStatus(val displayName: String) {
    WAITING("待存样"),
    STORING("存样中"),
    WAITING_DISPOSE("待消样")
}
