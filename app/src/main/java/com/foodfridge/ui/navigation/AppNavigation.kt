package com.foodfridge.ui.navigation

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.foodfridge.data.camera.CameraCoordinator
import com.foodfridge.data.camera.CameraCoordinatorEntryPoint
import com.foodfridge.ui.activation.DeviceActivationScreen
import dagger.hilt.android.EntryPointAccessors
import com.foodfridge.ui.facecheck.FaceRecognitionGateScreen
import com.foodfridge.ui.home.FridgeHomeScreen
import com.foodfridge.ui.detail.SampleDetailScreen
import com.foodfridge.ui.add.AddSampleScreen
import com.foodfridge.ui.settings.SettingsScreen
import com.foodfridge.ui.settings.FaceEnrollScreen
import com.foodfridge.ui.scan.BarcodeScanScreen
import com.foodfridge.ui.table.SampleTableScreen
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
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
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

        composable(Screen.AddSample.route) {
            AddSampleScreen(
                onNavigateBack = { navController.popBackStack() },
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
            val cameraSelector = remember {
                val recommended = cameraCoordinator.getRecommendedBarcodeCameraSelector()
                // 如果推荐的前置不可用且设备有后置，回退到后置；反之亦然
                when {
                    recommended == CameraSelector.DEFAULT_FRONT_CAMERA && !cameraCoordinator.hasFrontCamera() &&
                        cameraCoordinator.hasBackCamera() -> CameraSelector.DEFAULT_BACK_CAMERA
                    recommended == CameraSelector.DEFAULT_BACK_CAMERA && !cameraCoordinator.hasBackCamera() &&
                        cameraCoordinator.hasFrontCamera() -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> recommended
                }
            }
            BarcodeScanScreen(
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
                cameraSelector = cameraSelector,
                cameraCoordinator = cameraCoordinator,
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
