package com.foodfridge.ui.navigation

sealed class Screen(val route: String) {
    object DeviceActivation : Screen("device_activation")
    object FaceRecognitionGate : Screen("face_recognition_gate")
    object Home : Screen("home")
    object SampleDetail : Screen("sample_detail/{mealType}/{dayOffset}") {
        fun createRoute(mealType: String, dayOffset: Int) = "sample_detail/$mealType/$dayOffset"
    }
    object AddSample : Screen("add_sample")
    object Settings : Screen("settings")
    object FaceEnroll : Screen("face_enroll/{userId}") {
        fun createRoute(userId: Int) = "face_enroll/$userId"
    }
    object BarcodeScan : Screen("barcode_scan/{mealType}/{dayOffset}") {
        fun createRoute(mealType: String, dayOffset: Int) = "barcode_scan/$mealType/$dayOffset"
    }
    object SampleTable : Screen("sample_table/{mealType}/{dayOffset}/{barcode}/{foodName}") {
        fun createRoute(mealType: String, dayOffset: Int, barcode: String, foodName: String) =
            "sample_table/$mealType/$dayOffset/$barcode/$foodName"
    }
}
