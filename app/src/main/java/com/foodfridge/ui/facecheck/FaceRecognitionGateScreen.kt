package com.foodfridge.ui.facecheck

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.data.camera.CameraCoordinator
import com.foodfridge.domain.auth.FaceAuthenticationPolicy
import com.foodfridge.ui.home.AuthUser
import com.foodfridge.ui.theme.DarkBg
import com.foodfridge.ui.theme.DarkCard
import kotlinx.coroutines.delay

@Composable
fun FaceRecognitionGateScreen(
    onVerified: (Int) -> Unit,
    onBack: () -> Unit,
    dualFaceAuthEnabled: Boolean = false,
    existingAuthUsers: List<AuthUser> = emptyList(),
    cameraCoordinator: CameraCoordinator? = null,
    viewModel: FaceRecognitionGateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var cameraBindError by remember { mutableStateOf<String?>(null) }
    var isNavigating by remember { mutableStateOf(false) }
    var isBackPending by remember { mutableStateOf(false) }
    var hasBackDispatched by remember { mutableStateOf(false) }
    var isCameraBound by remember { mutableStateOf(false) }
    val cameraEnabled = uiState.successToken <= 0 && !isBackPending

    val isSuccess = uiState.successToken > 0
    val authenticatedRoles = existingAuthUsers.map { it.role }
    val allowedRoles = FaceAuthenticationPolicy.allowedRoles(
        dualFaceEnabled = dualFaceAuthEnabled,
        authenticatedRoles = authenticatedRoles,
    )
    val gateTitle = when {
        !dualFaceAuthEnabled -> "留样员刷脸开门"
        existingAuthUsers.isEmpty() -> "双人认证：请监督员或留样员刷脸"
        else -> {
            val requiredRole = allowedRoles.singleOrNull()
            "双人认证：请${requiredRole?.let(FaceAuthenticationPolicy::displayName) ?: "另一位人员"}刷脸"
        }
    }

    // key 只依赖 isSuccess，避免 isNavigating 变化导致 LaunchedEffect restart
    LaunchedEffect(isSuccess) {
        Log.d("FaceRecognition", "LaunchedEffect triggered: isSuccess=$isSuccess, isNavigating=$isNavigating, matchedUserId=${uiState.matchedUserId}")
        if (isSuccess && !isNavigating) {
            isNavigating = true
            Log.d("FaceRecognition", "Auto-return starting, matchedUserId=${uiState.matchedUserId}")
            delay(420)
            Log.d("FaceRecognition", "Calling onVerified with userId=${uiState.matchedUserId ?: 0}")
            // 摄像头释放统一交给 FaceGateCameraPreview.DisposableEffect.onDispose 处理
            onVerified(uiState.matchedUserId ?: 0)
        } else {
            Log.d("FaceRecognition", "Auto-return skipped: isSuccess=$isSuccess, isNavigating=$isNavigating")
        }
    }

    // 返回流程只启动一次；等待当前用例解绑，同时用超时保证页面不会卡住。
    LaunchedEffect(isBackPending) {
        if (isBackPending && !hasBackDispatched) {
            val releaseDeadline = SystemClock.elapsedRealtime() + 1_000L
            while (isCameraBound && SystemClock.elapsedRealtime() < releaseDeadline) {
                delay(16)
            }
            if (isCameraBound) {
                Log.w("FaceRecognition", "Timed out waiting for camera release; returning to home")
            } else {
                Log.d("FaceRecognition", "Camera released; returning to home")
            }
            hasBackDispatched = true
            onBack()
        }
    }

    // Stable callbacks using remember - only change when viewModel changes
    val onFrameCallback = remember(viewModel, allowedRoles) {
        { frame: Bitmap ->
            Log.d("FaceRecognition", "Frame received, calling verifyAndContinue")
            viewModel.verifyAndContinue(
                frame = frame,
                isAutoScan = true,
                allowedRoles = allowedRoles,
            )
        }
    }

    val onCameraErrorCallback = remember {
        { message: String? ->
            if (message != null) {
                cameraBindError = message
            }
        }
    }

    // 拦截系统返回键，防止识别过程中返回导致状态混乱
    BackHandler(enabled = !isNavigating) {
        if (!isNavigating) {
            isNavigating = true
            isBackPending = true
            Log.d("FaceRecognition", "Back requested; waiting for camera release")
        }
    }

    // 页面进入时获取摄像头使用权，离开时释放
//    LaunchedEffect(Unit) {
//        cameraCoordinator?.acquire(CameraCoordinator.CameraPurpose.FACE_RECOGNITION)
//    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部标题按钮
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF2563EB),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = gateTitle,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // 相机预览框
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard),
        ) {
            FaceGateCameraPreview(
                onFrame = onFrameCallback,
                onCameraError = onCameraErrorCallback,
                onCameraBoundChanged = { isCameraBound = it },
                enabled = cameraEnabled,
                cameraCoordinator = cameraCoordinator,
                modifier = Modifier.fillMaxSize(),
            )

            // 扫描线动画
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.85f)
                    .height(2.dp)
                    .background(Color(0xFF2563EB)),
            )

            // 相机绑定错误提示
            if (cameraBindError != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFB91C1C).copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "相机初始化失败: $cameraBindError",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 状态提示（底部）
            if (uiState.message.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = uiState.message,
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // 双人脸模式下显示底部认证状态
        if (dualFaceAuthEnabled) {
            DualFaceStatusBar(existingAuthUsers = existingAuthUsers)
        }
    }
}

@Composable
private fun DualFaceStatusBar(existingAuthUsers: List<AuthUser>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 留样员状态
        val samplerDone = existingAuthUsers.any { it.role == "SAMPLER" }
        FaceStatusItem(
            label = "留样员刷脸",
            isDone = samplerDone,
        )
        // 监督员状态
        val supervisorDone = existingAuthUsers.any { it.role == "SUPERVISOR" }
        FaceStatusItem(
            label = "监督员刷脸",
            isDone = supervisorDone,
        )
    }
}

@Composable
private fun FaceStatusItem(label: String, isDone: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 圆形图标
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (isDone) Color(0xFF10B981) else Color(0xFFD1D5DB)),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White.copy(alpha = 0.5f),
                )
            }
        }
        // 标签
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}
