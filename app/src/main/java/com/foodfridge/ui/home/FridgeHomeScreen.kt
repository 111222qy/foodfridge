package com.foodfridge.ui.home

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.data.camera.CameraCoordinator
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.ui.facecheck.FaceGateCameraPreview
import com.foodfridge.ui.theme.AuthExitRed
import com.foodfridge.ui.theme.CardDispose
import com.foodfridge.ui.theme.CardStoring
import com.foodfridge.ui.theme.CardWaiting
import com.foodfridge.ui.theme.DarkBg
import com.foodfridge.ui.theme.TagBreakfast
import com.foodfridge.ui.theme.TagDinner
import com.foodfridge.ui.theme.TagLunch
import com.foodfridge.ui.theme.TempAlarmEnd
import com.foodfridge.ui.theme.TempAlarmStart
import com.foodfridge.ui.theme.TempCircleGradientEnd
import com.foodfridge.ui.theme.TempCircleGradientStart
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun FridgeHomeScreen(
    authUserId: Int = -1,
    onAuthHandled: () -> Unit = {},
    onNavigateToFaceRecognition: (Boolean, List<AuthUser>) -> Unit,
    onNavigateToDetail: (String, Int) -> Unit,
    onNavigateToAddSample: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBarcodeScan: (String, Int) -> Unit,
    cameraCoordinator: CameraCoordinator? = null,
    viewModel: FridgeHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettingsAuthDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshAuthState()
    }

    // 从设置页返回时恢复人脸检测
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.setSettingsOpen(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
            // 等待 CameraCoordinator 确认首页相机已释放，避免与 Gate 页面抢摄像头
            var waitCount = 0
            while (
                cameraCoordinator?.getCurrentPurpose() != CameraCoordinator.CameraPurpose.IDLE &&
                waitCount < 50
            ) {
                delay(100)
                waitCount++
            }
            if (waitCount >= 50) {
                Log.w("FridgeHome", "等待相机释放超时，强制继续")
                cameraCoordinator?.forceRelease()
            }
            onNavigateToFaceRecognition(uiState.dualFaceAuthEnabled, uiState.authUsers)
            viewModel.onAuthDismiss()
        }
    }

    // 双人脸模式下，只有一个人认证时，自动再次弹出人脸识别
    LaunchedEffect(uiState.authUsers, uiState.dualFaceAuthEnabled) {
        if (uiState.dualFaceAuthEnabled &&
            uiState.authUsers.size == 1 &&
            (uiState.authUsers[0].role == "SUPERVISOR" || uiState.authUsers[0].role == "SAMPLER")
        ) {
            delay(3000) // 延长到 3 秒，给第一个 Gate 的相机资源完全释放
            viewModel.onAuthDismiss()
            onNavigateToFaceRecognition(uiState.dualFaceAuthEnabled, uiState.authUsers)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 16.dp),
    ) {
        // 顶部栏：左下角退出认证 + 右上角设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 退出认证按钮（仅认证后显示）
            if (uiState.isAuthenticated) {
                Button(
                    onClick = { viewModel.logout() },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuthExitRed,
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                ) {
                    Text(
                        text = "退出认证",
                        fontSize = 13.sp,
                        color = Color.White,
                    )
                }
            } else {
                // 占位保持布局
                Spacer(modifier = Modifier.width(80.dp))
            }

            // 设置按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clickable { showSettingsAuthDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    modifier = Modifier.size(22.dp),
                    tint = Color.White,
                )
                Text(
                    text = "设置",
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }

        // 隐藏的相机预览（用于人脸检测，1x1dp 完全不可见）
        val isBarcodeScanActive = cameraCoordinator?.getCurrentPurpose() == CameraCoordinator.CameraPurpose.BARCODE_SCAN
        val shouldShowHiddenCamera = !uiState.isAuthenticated && !uiState.showAuthGate && !uiState.isProcessingAuth && !uiState.isSettingsOpen && !isBarcodeScanActive
        if (shouldShowHiddenCamera) {
            Box(
                modifier = Modifier.size(1.dp),
            ) {
                FaceGateCameraPreview(
                    onFrame = { frame ->
                        viewModel.onFaceDetectionFrame(frame)
                    },
                    onCameraError = {},
                    onCameraBoundChanged = {},
                    enabled = true,
                    cameraCoordinator = cameraCoordinator,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 中间温度大圆形
        TemperatureCircle(
            temperature = uiState.temperature,
            isTemperatureAlarm = uiState.isTemperatureAlarm,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (showSettingsAuthDialog) {
            SettingsAuthDialog(
                onDismiss = { showSettingsAuthDialog = false },
                onConfirm = {
                    showSettingsAuthDialog = false
                    viewModel.setSettingsOpen(true)
                    onNavigateToSettings()
                },
            )
        }

        // 三列并排显示三天
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 第一天
            DayColumn(
                dayLabel = formatDayDate(-2),
                cards = uiState.day1Cards,
                isAuthenticated = uiState.isAuthenticated,
                onCardClick = { mealType ->
                    onNavigateToDetail(mealType.name, 0)
                },
                onAuthRequired = {
                    Toast.makeText(context, "请先认证", Toast.LENGTH_SHORT).show()
                    viewModel.openAuthGate()
                },
                modifier = Modifier.weight(1f),
            )
            // 第二天
            DayColumn(
                dayLabel = formatDayDate(-1),
                cards = uiState.day2Cards,
                isAuthenticated = uiState.isAuthenticated,
                onCardClick = { mealType ->
                    onNavigateToDetail(mealType.name, 1)
                },
                onAuthRequired = {
                    Toast.makeText(context, "请先认证", Toast.LENGTH_SHORT).show()
                    viewModel.openAuthGate()
                },
                modifier = Modifier.weight(1f),
            )
            // 第三天
            DayColumn(
                dayLabel = formatDayDate(0),
                cards = uiState.day3Cards,
                isAuthenticated = uiState.isAuthenticated,
                onCardClick = { mealType ->
                    onNavigateToDetail(mealType.name, 2)
                },
                onAuthRequired = {
                    Toast.makeText(context, "请先认证", Toast.LENGTH_SHORT).show()
                    viewModel.openAuthGate()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TemperatureCircle(temperature: Float?, isTemperatureAlarm: Boolean) {
    val gradientColors = if (isTemperatureAlarm) {
        listOf(TempAlarmStart, TempAlarmEnd)
    } else {
        listOf(TempCircleGradientStart, TempCircleGradientEnd)
    }

    val tempText = when {
        temperature == null -> "--"
        temperature <= -100f || temperature >= 100f -> "异常"
        else -> "%.1f".format(temperature)
    }
    val unitText = if (temperature == null) "" else "°C"
    val labelText = if (temperature == null) "温度获取中..." else "冰箱温度"

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 圆圈直径至少占据可用高度的一半，同时不超过宽度
        val circleSize = minOf(maxWidth, maxHeight * 0.9f)
        // 根据圆圈大小计算字体尺寸
        val density = LocalDensity.current
        val tempFontSize = with(density) { (circleSize * 0.38f).toSp() }
        val unitFontSize = with(density) { (circleSize * 0.12f).toSp() }
        val labelFontSize = with(density) { (circleSize * 0.08f).toSp() }

        Box(
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .background(Brush.verticalGradient(colors = gradientColors)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = tempText,
                        fontSize = tempFontSize,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        lineHeight = tempFontSize,
                    )
                    Text(
                        text = unitText,
                        fontSize = unitFontSize,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = with(density) { (circleSize * 0.04f).roundToPx() }.dp),
                    )
                }
                Text(
                    text = labelText,
                    fontSize = labelFontSize,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun SettingsAuthDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    label = { Text("账号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFDC2626),
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username == "admin" && password == "admin") {
                        onConfirm()
                    } else {
                        errorMessage = "账号或密码错误"
                    }
                },
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun DayColumn(
    dayLabel: String,
    cards: List<MealCardState>,
    isAuthenticated: Boolean,
    onCardClick: (MealType) -> Unit,
    onAuthRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = dayLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )

        cards.forEach { card ->
            MealStatusCard(
                mealType = card.mealType,
                status = card.status,
                onClick = {
                    if (isAuthenticated) {
                        onCardClick(card.mealType)
                    } else {
                        onAuthRequired()
                    }
                },
            )
        }
    }
}

@Composable
private fun MealStatusCard(
    mealType: MealType,
    status: SampleStatus,
    onClick: () -> Unit,
) {
    val (tagColor, tagText) = when (mealType) {
        MealType.BREAKFAST -> TagBreakfast to "早餐"
        MealType.LUNCH -> TagLunch to "中餐"
        MealType.DINNER -> TagDinner to "晚餐"
    }

    val cardBg = when (status) {
        SampleStatus.WAITING -> CardWaiting
        SampleStatus.STORING -> CardStoring
        SampleStatus.WAITING_DISPOSE -> CardDispose
    }

    val textColor = when (status) {
        SampleStatus.WAITING -> Color(0xFF374151)
        SampleStatus.STORING -> Color.White
        SampleStatus.WAITING_DISPOSE -> Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(tagColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tagText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Text(
                text = status.displayName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

private fun formatDayDate(dayOffsetFromToday: Int): String {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_MONTH, dayOffsetFromToday)
    return SimpleDateFormat("M月d日", Locale.getDefault()).format(calendar.time)
}
