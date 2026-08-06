package com.foodfridge.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object DeviceActivation : Screen("device_activation")
    object FaceRecognitionGate : Screen("face_recognition_gate")
    object Home : Screen("home")
    object SampleDetail : Screen("sample_detail/{mealType}/{dayOffset}") {
        fun createRoute(mealType: String, dayOffset: Int) =
            "sample_detail/${Uri.encode(mealType)}/$dayOffset"
    }
    object AddSample : Screen("add_sample")
    object AddSampleScan : Screen("add_sample_scan")
    object Settings : Screen("settings")
    object FaceEnroll : Screen("face_enroll/{userId}") {
        fun createRoute(userId: Int) = "face_enroll/$userId"
    }
    object BarcodeScan : Screen("barcode_scan/{mealType}/{dayOffset}") {
        fun createRoute(mealType: String, dayOffset: Int) =
            "barcode_scan/${Uri.encode(mealType)}/$dayOffset"
    }
    object SampleTable : Screen("sample_table/{mealType}/{dayOffset}/{barcode}/{foodName}/{weightGrams}/{scanTime}/{scanMealType}") {
        fun createRoute(
            mealType: String,
            dayOffset: Int,
            barcode: String,
            foodName: String,
            weightGrams: Float = 0f,
            scanTime: Long = 0L,
            scanMealType: String = "",
        ) = "sample_table/${Uri.encode(mealType)}/$dayOffset/${Uri.encode(barcode)}/${Uri.encode(foodName)}/$weightGrams/$scanTime/${Uri.encode(scanMealType)}"
    }
}
