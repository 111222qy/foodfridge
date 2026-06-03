package com.foodfridge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
fun AppNavigation(startDestination: String = Screen.Home.route) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
            val authUserIdFlow = savedStateHandle?.getStateFlow("auth_user_id", -1) ?: flowOf(-1)
            val authUserId by authUserIdFlow.collectAsStateWithLifecycle(initialValue = -1)

            FridgeHomeScreen(
                authUserId = authUserId,
                onAuthHandled = {
                    navController.currentBackStackEntry?.savedStateHandle?.set("auth_user_id", -1)
                },
                onNavigateToFaceRecognition = {
                    navController.navigate(Screen.FaceRecognitionGate.route) {
                        launchSingleTop = true
                    }
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
            )
        }

        composable(Screen.FaceRecognitionGate.route) {
            FaceRecognitionGateScreen(
                onVerified = { matchedUserId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("auth_user_id", matchedUserId)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.SampleDetail.route) { backStackEntry ->
            val mealType = backStackEntry.arguments
                ?.getString("mealType") ?: "BREAKFAST"
            val dayOffset = backStackEntry.arguments
                ?.getString("dayOffset")?.toIntOrNull() ?: 0
            SampleDetailScreen(
                mealType = mealType,
                dayOffset = dayOffset,
                onNavigateBack = { navController.popBackStack() },
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
                ?.getString("mealType") ?: "BREAKFAST"
            val dayOffset = backStackEntry.arguments
                ?.getString("dayOffset")?.toIntOrNull() ?: 0
            BarcodeScanScreen(
                mealType = mealType,
                dayOffset = dayOffset,
                onNavigateBack = { navController.popBackStack() },
                onScanComplete = { barcode, foodName ->
                    navController.navigate(
                        Screen.SampleTable.createRoute(mealType, dayOffset, barcode, foodName)
                    ) {
                        launchSingleTop = true
                        popUpTo(Screen.BarcodeScan.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.SampleTable.route) { backStackEntry ->
            val mealType = backStackEntry.arguments
                ?.getString("mealType") ?: "BREAKFAST"
            val dayOffset = backStackEntry.arguments
                ?.getString("dayOffset")?.toIntOrNull() ?: 0
            val barcode = backStackEntry.arguments
                ?.getString("barcode") ?: ""
            val foodName = backStackEntry.arguments
                ?.getString("foodName") ?: ""
            SampleTableScreen(
                mealType = mealType,
                dayOffset = dayOffset,
                barcode = barcode,
                foodName = foodName,
                onNavigateBack = { navController.popBackStack() },
                onSaveComplete = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
            )
        }
    }
}
