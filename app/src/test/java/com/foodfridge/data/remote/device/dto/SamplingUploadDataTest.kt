package com.foodfridge.data.remote.device.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class SamplingUploadDataTest {
    @Test
    fun `sampling upload includes meal type`() {
        val request = SamplingUploadData(
            device_id = "CODE03",
            timestamp = 1784779200000,
            dish_name = "黄金面包片",
            meal_type = "BREAKFAST",
            operator_name = "测试人员",
            weight = 125f,
        )

        val json = Gson().toJsonTree(request).asJsonObject

        assertEquals("BREAKFAST", json.get("meal_type").asString)
    }
}
