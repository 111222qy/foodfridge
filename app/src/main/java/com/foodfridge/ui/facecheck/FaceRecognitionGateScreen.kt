package com.foodfridge.ui.facecheck

import android.graphics.Bitmap
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
    viewModel: FaceRecognitionGateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var cameraBindError by remember { mutableStateOf<String?>(null) }
    var isCameraBound by remember { mutableStateOf(false) }
    val cameraEnabled = uiState.successToken <= 0
    var isNavigating by remember { mutableStateOf(false) }

    val isSuccess = uiState.successToken > 0

    // key 只依赖 isSuccess，避免 isNavigating 变化导致 LaunchedEffect restart
    LaunchedEffect(isSuccess) {
        Log.d("FaceRecognition", "LaunchedEffect triggered: isSuccess=$isSuccess, isNavigating=$isNavigating, matchedUserId=${uiState.matchedUserId}")
        if (isSuccess && !isNavigating) {
            isNavigating = true
            Log.d("FaceRecognition", "Auto-return starting, matchedUserId=${uiState.matchedUserId}")
            delay(420)
            Log.d("FaceRecognition", "Calling onVerified with userId=${uiState.matchedUserId ?: 0}")
            onVerified(uiState.matchedUserId ?: 0)
        } else {
            Log.d("FaceRecognition", "Auto-return skipped: isSuccess=$isSuccess, isNavigating=$isNavigating")
        }
    }

    // Stable callbacks using remember - only change when viewModel changes
    val onFrameCallback = remember(viewModel) {
        { frame: Bitmap ->
            Log.d("FaceRecognition", "Frame received, calling verifyAndContinue")
            viewModel.verifyAndContinue(frame, isAutoScan = true)
        }
    }

    val onCameraErrorCallback = remember {
        { message: String? ->
            if (message != null) {
                cameraBindError = message
            }
        }
    }

    val onCameraBoundChangedCallback = remember {
        { bound: Boolean ->
            isCameraBound = bound
            Log.d("FaceRecognition", "Camera bound: $bound")
            Unit
        }
    }

    // 拦截系统返回键，防止识别过程中返回导致状态混乱
    BackHandler(enabled = !isNavigating) {
        if (!isNavigating) {
            isNavigating = true
            onBack()
        }
    }

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
                    text = "留样员刷脸开门",
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
                onCameraBoundChanged = onCameraBoundChangedCallback,
                enabled = cameraEnabled,
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
