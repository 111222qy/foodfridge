package com.foodfridge.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodfridge.domain.model.MealType
import com.foodfridge.ui.theme.DarkBg
import com.foodfridge.ui.theme.ScanFrameBg
import kotlinx.coroutines.delay

@Composable
fun BarcodeScanScreen(
    mealType: String,
    dayOffset: Int,
    onNavigateBack: () -> Unit,
    onScanComplete: (String, String) -> Unit,
) {
    var isScanning by remember { mutableStateOf(false) }
    var triggerScan by remember { mutableStateOf(false) }
    val mealTypeEnum = remember { MealType.valueOf(mealType) }

    // 扫描逻辑
    LaunchedEffect(triggerScan) {
        if (triggerScan) {
            isScanning = true
            delay(1500)
            isScanning = false
            val demoBarcodes = listOf(
                "6901234567890" to "红烧肉",
                "6901234567891" to "清蒸鱼",
                "6901234567892" to "麻婆豆腐",
                "6901234567893" to "宫保鸡丁",
                "6901234567894" to "西红柿炒蛋",
            )
            val (barcode, foodName) = demoBarcodes.random()
            onScanComplete(barcode, foodName)
            triggerScan = false
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
                    text = "扫码",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.weight(1f))
        // 扫描框
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ScanFrameBg)
                .clickable { if (!isScanning) triggerScan = true },
            contentAlignment = Alignment.Center,
        ) {
            // 内部扫描区域
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ScanFrameBg.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color(0xFF2563EB),
                        strokeWidth = 3.dp,
                    )
                }
            }

            // 扫描线
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.7f)
                    .height(2.dp)
                    .background(Color(0xFF2563EB)),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 提示文字
        Text(
            text = "请扫码标签二维码",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}
