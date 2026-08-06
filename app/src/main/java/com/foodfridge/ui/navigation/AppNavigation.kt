package com.foodfridge.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.foodfridge.data.camera.CameraCoordinator
import com.foodfridge.data.camera.CameraCoordinatorEntryPoint
import com.foodfridge.data.hardware.SerialBarcodeScanner
import com.foodfridge.domain.model.MealType
import com.foodfridge.ui.activation.DeviceActivationScreen
import com.foodfridge.ui.add.AddSampleScreen
import com.foodfridge.ui.detail.SampleDetailScreen
import com.foodfridge.ui.facecheck.FaceRecognitionGateScreen
import com.foodfridge.ui.home.FridgeHomeScreen
import com.foodfridge.ui.scan.SerialBarcodeScanScreen
import com.foodfridge.ui.settings.FaceEnrollScreen
import com.foodfridge.ui.settings.SettingsScreen
import com.foodfridge.ui.table.SampleTableScreen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.flowOf

@Composable
fun rememberCameraCoordinator(): CameraCoordinator {
    val context = LocalContext.current
    return remember {
        EntryPointAccessors.fromApplication(context, CameraCoordinatorEntryPoint::class.java).cameraCoordinator()
    }
}

@Composable
fun AppNavigation(startDestination: String = Screen.DeviceActivation.route) {
    val navController = rememberNavController()
    val cameraCoordinator = rememberCameraCoordinator()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.DeviceActivation.route) {
            DeviceActivationScreen(
                onActivationSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.DeviceActivation.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Home.route) {
            val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
            val authUserIdFlow = savedStateHandle?.getStateFlow("auth_user_id", -1) ?: flowOf(-1)
            val authUserId by authUserIdFlow.collectAsStateWithLifecycle(initialValue = -1)

            FridgeHomeScreen(
                authUserId = authUserId,
                onAuthHandled = {
                    navController.currentBackStackEntry?.savedStateHandle?.set("auth_user_id", -1)
                },
                onNavigateToFaceRecognition = { dualEnabled, authUsers ->
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("dual_face_enabled", dualEnabled)
                        set("existing_auth_users", ArrayList(authUsers))
                    }
                    navController.navigate(Screen.FaceRecognitionGate.route)
                },
                onNavigateToDetail = { mealType, dayOffset ->
                    navController.navigate(Screen.SampleDetail.createRoute(mealType, dayOffset)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAddSample = {
                    navController.navigate(Screen.AddSample.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToBarcodeScan = { mealType, dayOffset ->
                    navController.navigate(Screen.BarcodeScan.createRoute(mealType, dayOffset)) {
                        launchSingleTop = true
                    }
                },
                cameraCoordinator = cameraCoordinator,
            )
        }

        composable(Screen.FaceRecognitionGate.route) {
            val dualEnabled = navController.previousBackStackEntry
                ?.savedStateHandle?.get<Boolean>("dual_face_enabled") ?: false
            val authUsers = navController.previousBackStackEntry
                ?.savedStateHandle?.get<java.util.ArrayList<com.foodfridge.ui.home.AuthUser>>("existing_auth_users")
                ?: emptyList()

            FaceRecognitionGateScreen(
                onVerified = { matchedUserId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("auth_user_id", matchedUserId)
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onBack = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("auth_user_id", 0)
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                dualFaceAuthEnabled = dualEnabled,
                existingAuthUsers = authUsers,
                cameraCoordinator = cameraCoordinator,
            )
        }

        composable(Screen.SampleDetail.route) { backStackEntry ->
            val mealType = backStackEntry.arguments
                ?.getString("mealType")?.let { Uri.decode(it) } ?: "BREAKFAST"
            val dayOffset = backStackEntry.arguments
                ?.getString("dayOffset")?.toIntOrNull() ?: 0
            SampleDetailScreen(
                mealType = mealType,
                dayOffset = dayOffset,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBarcodeScan = {
                    navController.navigate(Screen.BarcodeScan.createRoute(mealType, dayOffset)) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Screen.AddSample.route) { backStackEntry ->
            val viewModel: com.foodfridge.ui.add.AddSampleViewModel = hiltViewModel(backStackEntry)
            val barcode by backStackEntry.savedStateHandle
                .getStateFlow<String?>("add_sample_barcode", null)
                .collectAsStateWithLifecycle()
            val payload by backStackEntry.savedStateHandle
                .getStateFlow<com.foodfridge.domain.scan.BarcodePayload?>("add_sample_payload", null)
                .collectAsStateWithLifecycle()

            LaunchedEffect(barcode, payload) {
                val currentBarcode = barcode
                val currentPayload = payload
                if (currentBarcode != null && currentPayload != null) {
                    viewModel.onScanResult(currentBarcode, currentPayload)
                    backStackEntry.savedStateHandle.remove<String>("add_sample_barcode")
                    backStackEntry.savedStateHandle.remove<com.foodfridge.domain.scan.BarcodePayload>("add_sample_payload")
                }
            }

            AddSampleScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSerialScan = {
                    navController.navigate(Screen.AddSampleScan.route) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Screen.AddSampleScan.route) {
            SerialBarcodeScanScreen(
                mealType = MealType.BREAKFAST.name,
                dayOffset = 0,
                onNavigateBack = { navController.popBackStack() },
                onScanComplete = { barcode, payload ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("add_sample_barcode", barcode)
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("add_sample_payload", payload)
                    navController.popBackStack()
                },
                serialPort = com.foodfridge.data.hardware.SerialBarcodeScanner.DEFAULT_DEVICE_PATH,
                baudRate = com.foodfridge.data.hardware.SerialBarcodeScanner.DEFAULT_BAUD_RATE,
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFaceEnroll = { userId ->
                    navController.navigate(Screen.FaceEnroll.createRoute(userId)) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Screen.FaceEnroll.route) { backStackEntry ->
            val userId = backStackEntry.arguments
                ?.getString("userId")?.toIntOrNull() ?: 0
            FaceEnrollScreen(
                userId = userId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.BarcodeScan.route) { backStackEntry ->
            val mealType = backStackEntry.arguments
                ?.getString("mealType")?.let { Uri.decode(it) } ?: "BREAKFAST"
            val dayOffset = backStackEntry.arguments
                ?.getString("dayOffset")?.toIntOrNull() ?: 0
            val context = LocalContext.current
            SerialBarcodeScanScreen(
                mealType = mealType,
                dayOffset = dayOffset,
                onNavigateBack = { navController.popBackStack() },
                onScanComplete = { barcode, payload ->
                    navController.navigate(
                        Screen.SampleTable.createRoute(
                            mealType = mealType,
                            dayOffset = dayOffset,
                            barcode = barcode,
                            foodName = payload.dishName,
                            weightGrams = payload.weightGrams,
                            scanTime = payload.timestamp,
                            scanMealType = payload.mealType,
                        )
                    ) {
                        launchSingleTop = true
                        popUpTo(Screen.BarcodeScan.route) { inclusive = true }
                    }
                },
                serialPort = SerialBarcodeScanner.DEFAULT_DEVICE_PATH,
                baudRate = SerialBarcodeScanner.DEFAULT_BAUD_RATE,
            )
        }

        composable(Screen.SampleTable.route) { backStackEntry ->
            val mealType = backStackEntry.arguments
                ?.getString("mealType")?.let { Uri.decode(it) } ?: "BREAKFAST"
            val dayOffset = backStackEntry.arguments
                ?.getString("dayOffset")?.toIntOrNull() ?: 0
            val barcode = backStackEntry.arguments
                ?.getString("barcode")?.let { Uri.decode(it) } ?: ""
            val foodName = backStackEntry.arguments
                ?.getString("foodName")?.let { Uri.decode(it) } ?: ""
            val weightGrams = backStackEntry.arguments
                ?.getString("weightGrams")?.toFloatOrNull() ?: 0f
            val scanTime = backStackEntry.arguments
                ?.getString("scanTime")?.toLongOrNull() ?: 0L
            val scanMealType = backStackEntry.arguments
                ?.getString("scanMealType")?.let { Uri.decode(it) } ?: ""
            SampleTableScreen(
                mealType = mealType,
                dayOffset = dayOffset,
                barcode = barcode,
                foodName = foodName,
                weightGrams = weightGrams,
                scanTime = scanTime,
                scanMealType = scanMealType,
                onNavigateBack = { navController.popBackStack() },
                onSaveComplete = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
            )
        }
    }
}
