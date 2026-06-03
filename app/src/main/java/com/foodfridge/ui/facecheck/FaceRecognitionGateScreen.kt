package com.foodfridge.ui.facecheck

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun FaceRecognitionGateScreen(
    onVerified: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: FaceRecognitionGateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var cameraBindError by remember { mutableStateOf<String?>(null) }
    var isCameraBound by remember { mutableStateOf(false) }
    val cameraEnabled = uiState.successToken <= 0
    var isNavigating by remember { mutableStateOf(false) }

    val statusBg = when {
        uiState.errorMessage != null -> Color(0xFFFEE2E2)
        uiState.isRecognizing -> Color(0xFFDBEAFE)
        uiState.successToken > 0 -> Color(0xFFDCFCE7)
        else -> Color(0xFFE2E8F0)
    }
    val statusFg = when {
        uiState.errorMessage != null -> Color(0xFFB91C1C)
        uiState.isRecognizing -> Color(0xFF1D4ED8)
        uiState.successToken > 0 -> Color(0xFF166534)
        else -> Color(0xFF334155)
    }

    LaunchedEffect(uiState.successToken) {
        if (uiState.successToken > 0 && !isNavigating) {
            isNavigating = true
            delay(420)
            onVerified(uiState.matchedUserId ?: 0)
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
            .background(Color(0xFFF6F8FB))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 170.dp, max = 170.dp),
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "人脸登录", color = Color(0xFF0F172A))
                Text(text = "请将面部对准摄像头，系统将在全画面中检测人脸", color = Color(0xFF64748B))
                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = uiState.message,
                        color = statusFg,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    if (uiState.errorMessage != null) {
                        Text(text = uiState.errorMessage.orEmpty(), color = Color(0xFFDC2626))
                    }
                }
                Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    if (cameraBindError != null) {
                        Text(text = cameraBindError.orEmpty(), color = Color(0xFFF97316))
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.Black, RoundedCornerShape(20.dp)),
        ) {
            FaceGateCameraPreview(
                onFrame = onFrameCallback,
                onCameraError = onCameraErrorCallback,
                onCameraBoundChanged = onCameraBoundChangedCallback,
                enabled = cameraEnabled,
                modifier = Modifier.fillMaxSize(),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = when {
                        uiState.isRecognizing -> "实时扫描中..."
                        isCameraBound -> "实时画面已就绪，自动识别中"
                        else -> "正在初始化摄像头"
                    },
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    if (!isNavigating) {
                        isNavigating = true
                        onBack()
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
            ) {
                Text("返回", color = Color.White)
            }
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            ) {
                val actionText = if (isCameraBound) "自动扫描识别" else "相机初始化中..."
                Text(actionText, color = Color.White)
            }
        }
    }
}
