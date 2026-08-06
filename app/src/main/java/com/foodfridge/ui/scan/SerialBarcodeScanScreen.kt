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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodfridge.data.hardware.KeyboardBarcodeCollector
import com.foodfridge.data.hardware.SerialBarcodeScanner
import com.foodfridge.domain.scan.BarcodeDecoder
import com.foodfridge.domain.scan.BarcodePayload
import com.foodfridge.ui.theme.DarkBg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 串口扫码扫描页面
 *
 * 用于通过串口连接的扫码模组（如右下角圆形摄像头）进行二维码扫描。
 * 由于没有摄像头预览画面，UI 显示扫描状态和触发按钮。
 */
@Composable
fun SerialBarcodeScanScreen(
    mealType: String,
    @Suppress("UNUSED_PARAMETER") dayOffset: Int,
    onNavigateBack: () -> Unit,
    onScanComplete: (String, BarcodePayload) -> Unit,
    serialPort: String = SerialBarcodeScanner.DEFAULT_DEVICE_PATH,
    baudRate: Int = SerialBarcodeScanner.DEFAULT_BAUD_RATE,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { SerialBarcodeScanner(serialPort, baudRate, context = context) }
    val scanState by scanner.scanState.collectAsState()

    var scanResult by remember { mutableStateOf<ScanResult?>(null) }
    var isInitializing by remember { mutableStateOf(true) }
    var initError by remember { mutableStateOf<String?>(null) }
    var isContinuousMode by remember { mutableStateOf(false) }

    fun processBarcode(barcode: String) {
        Timber.i("Scan detected: '$barcode'")
        if (barcode.isBlank()) {
            Timber.w("Received empty barcode")
            scanResult = ScanResult.Error("扫码失败：未检测到二维码")
            return
        }
        val payload = BarcodeDecoder.decode(barcode)
        if (payload == null) {
            val isSmartScaleFormat = barcode.startsWith("${BarcodeDecoder.MAGIC}|")
            scanResult = ScanResult.Error(
                if (isSmartScaleFormat) {
                    "二维码格式错误，请检查小票"
                } else {
                    "检测到非留样二维码：$barcode"
                }
            )
            return
        }
        val currentMealDisplay = when (mealType) {
            "BREAKFAST" -> "早餐"
            "LUNCH" -> "午餐"
            "DINNER" -> "晚餐"
            else -> mealType
        }
        if (payload.mealType != currentMealDisplay) {
            scanResult = ScanResult.Error(
                "餐次不匹配：当前为${currentMealDisplay}，标签是${payload.mealType}"
            )
            return
        }
        scanResult = ScanResult.Success(barcode, payload)
    }

    fun startContinuousScan() {
        isContinuousMode = true
        KeyboardBarcodeCollector.setActive(true)
        scope.launch {
            // 如果串口未连接，尝试打开；已连接则直接使用
            val opened = if (!scanner.isConnected()) {
                try {
                    scanner.open()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to open serial scanner")
                    false
                }
            } else {
                true
            }
            if (!opened) {
                scanResult = ScanResult.Error("无法打开串口，请重试")
                isContinuousMode = false
                KeyboardBarcodeCollector.setActive(false)
                return@launch
            }
            scanner.startContinuousScanning { barcode ->
                processBarcode(barcode)
            }
        }
    }

    fun stopContinuousScan() {
        isContinuousMode = false
        KeyboardBarcodeCollector.setActive(false)
        scanner.stopContinuousScanning()
    }

    // 初始化串口
    LaunchedEffect(Unit) {
        try {
            val opened = scanner.open()
            if (opened) {
                isInitializing = false
                Timber.i("Serial scanner opened on $serialPort")
            } else {
                initError = "无法打开串口 $serialPort"
                isInitializing = false
            }
        } catch (e: Exception) {
            initError = "串口初始化失败: ${e.message}"
            isInitializing = false
            Timber.e(e, "Failed to open serial scanner")
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            KeyboardBarcodeCollector.setActive(false)
            Timber.i("Serial scanner closed")
        }
    }

    // 监听键盘输入（HID键盘模式的扫码器）
    LaunchedEffect(Unit) {
        KeyboardBarcodeCollector.detectedBarcode.collect { barcode ->
            if (barcode != null) {
                Timber.i("Keyboard barcode received: '$barcode'")
                processBarcode(barcode)
                KeyboardBarcodeCollector.consumeBarcode()
            }
        }
    }

    // 自动重置：错误状态显示 2 秒后自动恢复
    LaunchedEffect(scanState) {
        if (scanState is SerialBarcodeScanner.ScanState.Error) {
            delay(2000)
            scanResult = null
            scanner.resetState()
        }
    }

    // 扫码结果处理：成功后自动导航，错误后自动重置以便重新扫描
    LaunchedEffect(scanResult) {
        when (scanResult) {
            is ScanResult.Success -> {
                delay(800)
                val success = scanResult as ScanResult.Success
                onScanComplete(success.barcode, success.payload)
            }
            is ScanResult.Error -> {
                delay(2000)
                scanResult = null
                scanner.resetState()
                if (isContinuousMode) {
                    startContinuousScan()
                }
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
                    text = "扫描二维码",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // 扫描区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1F2937)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isInitializing -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF2563EB),
                            strokeWidth = 3.dp,
                        )
                        Text(
                            text = "正在初始化串口扫码器...",
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Text(
                            text = "串口: $serialPort @ ${baudRate}bps",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }

                initError != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = initError!!,
                            fontSize = 16.sp,
                            color = Color(0xFFDC2626),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                initError = null
                                isInitializing = true
                                scope.launch {
                                    try {
                                        val opened = scanner.open()
                                        isInitializing = false
                                        if (!opened) {
                                            initError = "无法打开串口 $serialPort"
                                        }
                                    } catch (e: Exception) {
                                        isInitializing = false
                                        initError = "串口初始化失败: ${e.message}"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2563EB),
                            ),
                        ) {
                            Text("重试")
                        }
                    }
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        // 扫描状态图标
                        when (scanState) {
                            is SerialBarcodeScanner.ScanState.Idle,
                            is SerialBarcodeScanner.ScanState.Scanning -> {
                                Box(
                                    modifier = Modifier.size(120.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (scanState is SerialBarcodeScanner.ScanState.Scanning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.fillMaxSize(),
                                            color = Color(0xFF2563EB),
                                            strokeWidth = 4.dp,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = if (scanState is SerialBarcodeScanner.ScanState.Scanning)
                                            Color(0xFF2563EB)
                                        else
                                            Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(48.dp),
                                    )
                                }
                            }

                            is SerialBarcodeScanner.ScanState.Success -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF22C55E),
                                    modifier = Modifier.size(80.dp),
                                )
                            }

                            is SerialBarcodeScanner.ScanState.Error -> {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = Color(0xFFF97316),
                                    modifier = Modifier.size(80.dp),
                                )
                            }
                        }

                        // 状态文字
                        Text(
                            text = when (scanState) {
                                is SerialBarcodeScanner.ScanState.Idle -> "准备就绪"
                                is SerialBarcodeScanner.ScanState.Scanning -> "正在扫描..."
                                is SerialBarcodeScanner.ScanState.Success -> "扫描成功"
                                is SerialBarcodeScanner.ScanState.Error -> (scanState as SerialBarcodeScanner.ScanState.Error).message
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = when (scanState) {
                                is SerialBarcodeScanner.ScanState.Success -> Color(0xFF22C55E)
                                is SerialBarcodeScanner.ScanState.Error -> Color(0xFFF97316)
                                else -> Color.White.copy(alpha = 0.9f)
                            },
                        )

                        // 扫描按钮
                        when (scanState) {
                            is SerialBarcodeScanner.ScanState.Scanning -> {
                                Button(
                                    onClick = { stopContinuousScan() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFDC2626),
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        text = "停止扫描",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            is SerialBarcodeScanner.ScanState.Idle,
                            is SerialBarcodeScanner.ScanState.Error -> {
                                Button(
                                    onClick = { startContinuousScan() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2563EB),
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        text = "点击扫描",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            is SerialBarcodeScanner.ScanState.Success -> { }
                        }

                        // 扫描结果覆盖层
                        AnimatedVisibility(
                            visible = scanResult != null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            when (val result = scanResult) {
                                is ScanResult.Success -> {
                                    SuccessCard(
                                        payload = result.payload,
                                        onRescan = {
                                            scanResult = null
                                            scanner.resetState()
                                            if (isContinuousMode) {
                                                startContinuousScan()
                                            }
                                        },
                                    )
                                }

                                is ScanResult.Error -> {
                                    ErrorCard(
                                        message = result.message,
                                        onRescan = {
                                            scanResult = null
                                            scanner.resetState()
                                            if (isContinuousMode) {
                                                startContinuousScan()
                                            }
                                        },
                                    )
                                }

                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 底部提示
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "请将二维码对准设备右下角的扫描摄像头",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = "请把二维码放至右下角摄像头10~20cm处",
                fontSize = 13.sp,
                color = Color(0xFFF59E0B),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

private sealed class ScanResult {
    data class Success(val barcode: String, val payload: BarcodePayload) : ScanResult()
    data class Error(val message: String) : ScanResult()
}

@Composable
private fun SuccessCard(
    payload: BarcodePayload,
    onRescan: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.padding(vertical = 16.dp),
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

@Composable
private fun ErrorCard(
    message: String,
    onRescan: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.padding(vertical = 16.dp),
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
