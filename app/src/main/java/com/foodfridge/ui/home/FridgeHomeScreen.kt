package com.foodfridge.ui.home

import com.foodfridge.domain.model.FoodSample
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.ui.facecheck.FaceGateCameraPreview
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun FridgeHomeScreen(
    authUserId: Int = -1,
    onAuthHandled: () -> Unit = {},
    onNavigateToFaceRecognition: () -> Unit,
    onNavigateToDetail: (String, Int) -> Unit,
    onNavigateToAddSample: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBarcodeScan: (String, Int) -> Unit,
    viewModel: FridgeHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshAuthState()
    }

    // 处理从人脸识别页面返回的认证用户
    LaunchedEffect(authUserId) {
        if (authUserId > 0) {
            viewModel.onUserAuthenticated(authUserId)
            onAuthHandled()
        }
    }

    // 人脸检测自动触发人脸识别
    LaunchedEffect(uiState.showAuthGate) {
        if (uiState.showAuthGate) {
            onNavigateToFaceRecognition()
            viewModel.onAuthDismiss()
        }
    }

    // 双人脸模式下，只有一个人认证时，自动再次弹出人脸识别
    LaunchedEffect(uiState.authUsers, uiState.dualFaceAuthEnabled) {
        if (uiState.dualFaceAuthEnabled &&
            uiState.authUsers.size == 1 &&
            (uiState.authUsers[0].role == "SUPERVISOR" || uiState.authUsers[0].role == "SAMPLER")
        ) {
            delay(800)
            onNavigateToFaceRecognition()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F8FF)),
    ) {
        // 顶部温度栏（含人脸检测小窗口）
        TemperatureHeaderWithFaceDetection(
            temperature = uiState.temperature,
            isAlarm = uiState.isTemperatureAlarm,
            faceDetectionFrames = uiState.faceDetectionFrames,
            isAuthenticated = uiState.isAuthenticated,
            showAuthGate = uiState.showAuthGate,
            onSettingsClick = onNavigateToSettings,
            onFaceFrame = viewModel::onFaceDetectionFrame,
        )

        // 内容区域 - 三列并排显示
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 第一天
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DaySectionTitle(title = "第一天", dateStr = getDayDateStr(-2))
                uiState.day1Cards.forEach { card ->
                    CompactMealStatusCard(
                        mealType = card.mealType,
                        status = card.status,
                        latestSample = card.latestSample,
                        isAuthenticated = uiState.isAuthenticated,
                        onClick = {
                            if (uiState.isAuthenticated) {
                                onNavigateToBarcodeScan(card.mealType.name, 0)
                            }
                        },
                    )
                }
            }

            // 第二天
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DaySectionTitle(title = "第二天", dateStr = getDayDateStr(-1))
                uiState.day2Cards.forEach { card ->
                    CompactMealStatusCard(
                        mealType = card.mealType,
                        status = card.status,
                        latestSample = card.latestSample,
                        isAuthenticated = uiState.isAuthenticated,
                        onClick = {
                            if (uiState.isAuthenticated) {
                                onNavigateToBarcodeScan(card.mealType.name, 1)
                            }
                        },
                    )
                }
            }

            // 第三天
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DaySectionTitle(title = "第三天", dateStr = getDayDateStr(0))
                uiState.day3Cards.forEach { card ->
                    CompactMealStatusCard(
                        mealType = card.mealType,
                        status = card.status,
                        latestSample = card.latestSample,
                        isAuthenticated = uiState.isAuthenticated,
                        onClick = {
                            if (uiState.isAuthenticated) {
                                onNavigateToBarcodeScan(card.mealType.name, 2)
                            }
                        },
                    )
                }
            }
        }

        // 底部操作栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 认证状态指示
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = if (uiState.isAuthenticated) Color(0xFFECFDF5) else Color(0xFFF3F4F6),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (uiState.isAuthenticated) Color(0xFF10B981) else Color(0xFF9CA3AF)
                                ),
                        )
                        Text(
                            text = uiState.authPromptMessage,
                            color = if (uiState.isAuthenticated) Color(0xFF065F46) else Color(0xFF6B7280),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // 退出登录按钮（仅认证后显示）
                if (uiState.isAuthenticated) {
                    Button(
                        onClick = { viewModel.logout() },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "退出",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // 存样按钮
                Button(
                    onClick = onNavigateToAddSample,
                    enabled = uiState.isAuthenticated,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2563EB),
                        disabledContainerColor = Color(0xFFD1D5DB),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "存样",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TemperatureHeaderWithFaceDetection(
    temperature: Float,
    isAlarm: Boolean,
    faceDetectionFrames: Int,
    isAuthenticated: Boolean,
    showAuthGate: Boolean,
    onSettingsClick: () -> Unit,
    onFaceFrame: (android.graphics.Bitmap) -> Unit,
) {
    val bgColor = if (isAlarm) Color(0xFFFEF2F2) else Color(0xFFFFFFFF)
    val textColor = if (isAlarm) Color(0xFFDC2626) else Color(0xFF111827)
    val tempColor = if (isAlarm) Color(0xFFDC2626) else Color(0xFF2563EB)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bgColor,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "留样冰箱",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 人脸检测小窗口（未认证且未跳转识别页面时显示）
                if (!isAuthenticated && !showAuthGate) {
                    FaceDetectionMiniWindow(
                        faceDetectionFrames = faceDetectionFrames,
                        onFaceFrame = onFaceFrame,
                    )
                }

                // 温度显示
                Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isAlarm) Color(0xFFDC2626) else Color(0xFF10B981)),
                        )
                        Text(
                            text = if (isAlarm) "温度异常" else "温度正常",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAlarm) Color(0xFFDC2626) else Color(0xFF10B981),
                        )
                    }
                    Text(
                        text = "%.1f°C".format(temperature),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = tempColor,
                    )
                }

                // 设置按钮
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        modifier = Modifier.size(24.dp),
                        tint = Color(0xFF6B7280),
                    )
                }
            }
        }
    }
}

@Composable
private fun FaceDetectionMiniWindow(
    faceDetectionFrames: Int,
    onFaceFrame: (android.graphics.Bitmap) -> Unit,
) {
    val isDetected = faceDetectionFrames >= 2

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(Color(0xFF1F2937)),
        contentAlignment = Alignment.Center,
    ) {
        // 隐藏的相机预览（尺寸为1x1避免占用太多资源，只用于捕获帧）
        Box(
            modifier = Modifier.size(1.dp),
        ) {
            FaceGateCameraPreview(
                onFrame = { frame ->
                    onFaceFrame(frame)
                },
                onCameraError = {},
                onCameraBoundChanged = {},
                enabled = true,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 状态指示圆点
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDetected -> Color(0xFF10B981)
                        faceDetectionFrames > 0 -> Color(0xFFF59E0B)
                        else -> Color(0xFFEF4444)
                    }
                ),
        )
    }
}

@Composable
private fun DaySectionTitle(title: String, dateStr: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827),
        )
        Text(
            text = dateStr,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF),
        )
    }
}

@Composable
private fun CompactMealStatusCard(
    mealType: MealType,
    status: SampleStatus,
    latestSample: FoodSample?,
    isAuthenticated: Boolean,
    onClick: () -> Unit,
) {
    val cardBg = when (status) {
        SampleStatus.WAITING -> Color(0xFFFFFFFF)
        SampleStatus.STORING -> Color(0xFFDBEAFE)
        SampleStatus.WAITING_DISPOSE -> Color(0xFFFFEDD5)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isAuthenticated, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 餐次图标
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (mealType) {
                    MealType.BREAKFAST -> Color(0xFFFEF3C7)
                    MealType.LUNCH -> Color(0xFFDBEAFE)
                    MealType.DINNER -> Color(0xFFE9D5FF)
                },
                modifier = Modifier.size(40.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = when (mealType) {
                            MealType.BREAKFAST -> Color(0xFFD97706)
                            MealType.LUNCH -> Color(0xFF2563EB)
                            MealType.DINNER -> Color(0xFF7C3AED)
                        },
                    )
                }
            }

            // 餐次名称
            Text(
                text = mealType.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827),
            )

            // 食品信息
            if (latestSample != null) {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(java.util.Date(latestSample.storeTime))
                Text(
                    text = "${latestSample.foodName}\n${timeStr}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280),
                    maxLines = 2,
                )
            } else {
                Text(
                    text = "暂无留样",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF),
                )
            }

            // 状态标签
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when (status) {
                    SampleStatus.WAITING -> Color(0xFFF3F4F6)
                    SampleStatus.STORING -> Color(0xFF3B82F6)
                    SampleStatus.WAITING_DISPOSE -> Color(0xFFF97316)
                },
            ) {
                Text(
                    text = status.displayName,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = when (status) {
                        SampleStatus.WAITING -> Color(0xFF6B7280)
                        SampleStatus.STORING -> Color.White
                        SampleStatus.WAITING_DISPOSE -> Color.White
                    },
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun MealStatusCard(
    mealType: MealType,
    status: SampleStatus,
    latestSample: FoodSample?,
    isAuthenticated: Boolean,
    onClick: () -> Unit,
) {
    val cardBg = when (status) {
        SampleStatus.WAITING -> Color(0xFFFFFFFF)
        SampleStatus.STORING -> Color(0xFFDBEAFE)
        SampleStatus.WAITING_DISPOSE -> Color(0xFFFFEDD5)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isAuthenticated, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 餐次图标
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when (mealType) {
                    MealType.BREAKFAST -> Color(0xFFFEF3C7)
                    MealType.LUNCH -> Color(0xFFDBEAFE)
                    MealType.DINNER -> Color(0xFFE9D5FF)
                },
                modifier = Modifier.size(56.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = when (mealType) {
                            MealType.BREAKFAST -> Color(0xFFD97706)
                            MealType.LUNCH -> Color(0xFF2563EB)
                            MealType.DINNER -> Color(0xFF7C3AED)
                        },
                    )
                }
            }

            // 餐次名称
            Text(
                text = mealType.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827),
            )

            // 食品信息
            if (latestSample != null) {
                val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(java.util.Date(latestSample.storeTime))
                Text(
                    text = "${latestSample.foodName} · ${timeStr}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF6B7280),
                )
            } else {
                Text(
                    text = "暂无留样",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9CA3AF),
                )
            }

            // 状态标签
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = when (status) {
                    SampleStatus.WAITING -> Color(0xFFF3F4F6)
                    SampleStatus.STORING -> Color(0xFF3B82F6)
                    SampleStatus.WAITING_DISPOSE -> Color(0xFFF97316)
                },
            ) {
                Text(
                    text = status.displayName,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = when (status) {
                        SampleStatus.WAITING -> Color(0xFF6B7280)
                        SampleStatus.STORING -> Color.White
                        SampleStatus.WAITING_DISPOSE -> Color.White
                    },
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

private fun getDayDateStr(dayOffsetFromToday: Int): String {
    return SimpleDateFormat("MM月dd日", Locale.getDefault())
        .format(java.util.Date(System.currentTimeMillis() + dayOffsetFromToday * 24L * 60 * 60 * 1000))
}
