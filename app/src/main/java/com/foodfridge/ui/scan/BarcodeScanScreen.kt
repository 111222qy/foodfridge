package com.foodfridge.ui.scan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.camera.core.CameraSelector
import com.foodfridge.data.camera.CameraCoordinator
import com.foodfridge.domain.scan.BarcodeDecoder
import com.foodfridge.domain.scan.BarcodePayload
import com.foodfridge.ui.theme.DarkBg
import kotlinx.coroutines.delay
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun BarcodeScanScreen(
    mealType: String,
    dayOffset: Int,
    onNavigateBack: () -> Unit,
    onScanComplete: (String, BarcodePayload) -> Unit,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
    cameraCoordinator: CameraCoordinator? = null,
) {
    @Suppress("UNUSED_PARAMETER")
    val _mealType = mealType
    @Suppress("UNUSED_PARAMETER")
    val _dayOffset = dayOffset

    var scanState by remember { mutableStateOf<ScanState>(ScanState.Scanning) }
    var scanKey by remember { mutableStateOf(0) }

    val resetScanning = {
        scanKey++
        scanState = ScanState.Scanning
    }

    LaunchedEffect(scanState) {
        when (scanState) {
            is ScanState.Success -> {
                delay(1200)
                val success = scanState as ScanState.Success
                onScanComplete(success.rawBarcode, success.payload)
            }
            is ScanState.Error -> {
                delay(2000)
                scanState = ScanState.Scanning
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部返回按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "扫描条形码",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // 扫描框（相机预览）- 提取为独立 composable 避免 ColumnScope 冲突
        ScanFrame(
            scanState = scanState,
            scanKey = scanKey,
            onBarcodeDetected = { rawValue ->
                Timber.i("Raw barcode detected: $rawValue")
                val payload = BarcodeDecoder.decode(rawValue)
                if (payload != null) {
                    scanState = ScanState.Success(rawValue, payload)
                } else {
                    val message = if (rawValue.startsWith("${BarcodeDecoder.MAGIC}|")) {
                        "条码格式错误，请检查小票"
                    } else {
                        "检测到非留样条码：$rawValue"
                    }
                    scanState = ScanState.Error(message)
                }
            },
            onCameraError = { error ->
                if (error != null) {
                    scanState = ScanState.Error(error)
                }
            },
            onRescan = resetScanning,
            cameraSelector = cameraSelector,
            cameraCoordinator = cameraCoordinator,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 底部提示
        when (scanState) {
            is ScanState.Scanning -> {
                Text(
                    text = "请对准留样小票上的条形码\n（保持 10-15 厘米距离，避免反光）",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            is ScanState.Success -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFF22C55E),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "解析成功，正在跳转...",
                        fontSize = 14.sp,
                        color = Color(0xFF22C55E),
                    )
                }
            }
            is ScanState.Error -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color(0xFFF97316),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = (scanState as ScanState.Error).message,
                        fontSize = 14.sp,
                        color = Color(0xFFF97316),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private sealed class ScanState {
    object Scanning : ScanState()
    data class Success(val rawBarcode: String, val payload: BarcodePayload) : ScanState()
    data class Error(val message: String) : ScanState()
}

@Composable
private fun ScanCornerMarks() {
    val cornerColor = Color(0xFF2563EB)
    val cornerSize = 24.dp
    val cornerThickness = 3.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // 左上角
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(cornerSize)
                .height(cornerThickness)
                .background(cornerColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(cornerThickness)
                .height(cornerSize)
                .background(cornerColor)
        )
        // 右上角
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(cornerSize)
                .height(cornerThickness)
                .background(cornerColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(cornerThickness)
                .height(cornerSize)
                .background(cornerColor)
        )
        // 左下角
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(cornerSize)
                .height(cornerThickness)
                .background(cornerColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(cornerThickness)
                .height(cornerSize)
                .background(cornerColor)
        )
        // 右下角
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(cornerSize)
                .height(cornerThickness)
                .background(cornerColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(cornerThickness)
                .height(cornerSize)
                .background(cornerColor)
        )
    }
}

@Composable
private fun SuccessOverlay(payload: BarcodePayload, onRescan: () -> Unit) {
    val timeStr = remember(payload.timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(java.util.Date(payload.timestamp))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = payload.dishName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    InfoTag("${payload.weightGrams}g", Color(0xFF2563EB))
                    InfoTag(payload.mealType, Color(0xFF7C3AED))
                }
                Text(
                    text = timeStr,
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280),
                )
                androidx.compose.material3.TextButton(
                    onClick = onRescan,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重新扫描", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRescan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = message,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFDC2626),
                )
                Text(
                    text = "请重新扫描",
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280),
                )
                androidx.compose.material3.TextButton(
                    onClick = onRescan,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重新扫描", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ScanFrame(
    scanState: ScanState,
    scanKey: Int,
    onBarcodeDetected: (String) -> Unit,
    onCameraError: (String?) -> Unit,
    onRescan: () -> Unit,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
    cameraCoordinator: CameraCoordinator? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 相机预览：有权限时始终保留，避免 Success/Error 覆盖层下黑屏或反复释放摄像头
            hasPermission -> {
                BarcodeCameraPreview(
                    onBarcodeDetected = onBarcodeDetected,
                    onCameraError = onCameraError,
                    cameraSelector = cameraSelector,
                    cameraCoordinator = cameraCoordinator,
                    scanKey = scanKey,
                    modifier = Modifier.fillMaxSize(),
                )

                if (scanState == ScanState.Scanning) {
                    // 扫描框边框
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent),
                    ) {
                        ScanCornerMarks()
                    }

                    // 扫描线动画
                    AnimatedVisibility(
                        visible = scanState == ScanState.Scanning,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(0.75f)
                                .height(2.dp)
                                .background(Color(0xFF2563EB)),
                        )
                    }
                }
            }

            // 权限请求提示
            !hasPermission -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "需要相机权限以扫描条形码",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    androidx.compose.material3.Button(
                        onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2563EB),
                        ),
                    ) {
                        Text("授权相机权限")
                    }
                }
            }
        }

        // 扫描成功覆盖层
        AnimatedVisibility(
            visible = scanState is ScanState.Success,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val success = scanState as? ScanState.Success
            if (success != null) {
                SuccessOverlay(
                    payload = success.payload,
                    onRescan = onRescan,
                )
            }
        }

        // 错误覆盖层
        AnimatedVisibility(
            visible = scanState is ScanState.Error,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val error = scanState as? ScanState.Error
            if (error != null) {
                ErrorOverlay(
                    message = error.message,
                    onRescan = onRescan,
                )
            }
        }
    }
}

@Composable
private fun InfoTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}
