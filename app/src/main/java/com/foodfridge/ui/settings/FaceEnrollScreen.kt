package com.foodfridge.ui.settings

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foodfridge.ui.facecheck.FaceGateCameraPreview
import kotlinx.coroutines.delay

@Composable
fun FaceEnrollScreen(
    userId: Int,
    onNavigateBack: () -> Unit,
    viewModel: FaceEnrollViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.init(context, userId)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.reset()
        }
    }

    // Stable callbacks using remember - only change when viewModel changes
    val onFrameCallback = remember(viewModel) {
        { frame: Bitmap ->
            viewModel.captureFrame(frame)
        }
    }

    val onCameraErrorCallback = remember {
        { message: String? ->
            if (message != null) {
                Log.w("FaceEnroll", "人脸注册相机错误: $message")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F8FF)),
    ) {
        // 顶部导航栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = Color(0xFF111827),
                    )
                }
                Text(
                    text = "人脸注册",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 状态提示
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = when {
                    viewModel.success -> Color(0xFFECFDF5)
                    viewModel.isRegistering -> Color(0xFFDBEAFE)
                    else -> Color(0xFFF3F4F6)
                },
            ) {
                Text(
                    text = viewModel.message,
                    modifier = Modifier.padding(16.dp),
                    color = when {
                        viewModel.success -> Color(0xFF065F46)
                        viewModel.isRegistering -> Color(0xFF1D4ED8)
                        else -> Color(0xFF6B7280)
                    },
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }

            // 相机预览
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black, RoundedCornerShape(20.dp)),
            ) {
                if (!viewModel.isEngineReady && !viewModel.success) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                text = "人脸引擎初始化中...",
                                color = Color.White,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                } else {
                    FaceGateCameraPreview(
                        onFrame = onFrameCallback,
                        onCameraError = onCameraErrorCallback,
                        onCameraBoundChanged = {},
                        enabled = !viewModel.success && viewModel.isEngineReady,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 捕获计数 / 注册状态
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = when {
                            viewModel.success -> "注册成功"
                            viewModel.isRegistering -> "正在注册..."
                            !viewModel.isEngineReady -> "初始化中..."
                            else -> "已捕获 ${viewModel.capturedCount}/3 帧"
                        },
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.reset()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B7280),
                    ),
                ) {
                    Text("重置", color = Color.White)
                }

                // 注册成功后自动返回
                if (viewModel.success) {
                    LaunchedEffect(Unit) {
                        delay(1500)
                        onNavigateBack()
                    }
                }
            }
        }
    }
}
