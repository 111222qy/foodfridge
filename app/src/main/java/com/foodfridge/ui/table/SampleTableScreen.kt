package com.foodfridge.ui.table

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SampleTableScreen(
    mealType: String,
    dayOffset: Int,
    barcode: String,
    foodName: String,
    weightGrams: Float,
    scanTime: Long,
    scanMealType: String,
    onNavigateBack: () -> Unit,
    onSaveComplete: () -> Unit,
    viewModel: SampleTableViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    LaunchedEffect(mealType, dayOffset, barcode, foodName, weightGrams) {
        viewModel.init(mealType, dayOffset, barcode, foodName, weightGrams)
    }

    val mealTypeEnum = MealType.valueOf(mealType)
    val dayLabel = when (dayOffset) {
        0 -> "第一天"
        1 -> "第二天"
        2 -> "第三天"
        else -> "今天"
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
                    text = "$dayLabel · ${mealTypeEnum.displayName} - 留样记录",
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 扫描结果摘要
            ScanResultCard(
                barcode = barcode,
                foodName = foodName,
                weightGrams = weightGrams,
                scanTime = scanTime,
                scanMealType = scanMealType,
            )

            // 留样记录表格
            Text(
                text = "$dayLabel 留样记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827),
            )

            if (uiState.samples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$dayLabel 暂无留样记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF9CA3AF),
                    )
                }
            } else {
                SampleTable(samples = uiState.samples)
            }

            // 重量输入
            OutlinedTextField(
                value = uiState.weightGrams,
                onValueChange = viewModel::onWeightChange,
                label = { Text("留样重量 (g)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2563EB),
                    focusedLabelColor = Color(0xFF2563EB),
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            // 错误提示
            if (uiState.errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFEF2F2),
                ) {
                    Text(
                        text = uiState.errorMessage!!,
                        color = Color(0xFFDC2626),
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 确认存样按钮
            Button(
                onClick = { viewModel.saveSample(onSaveComplete) },
                enabled = !uiState.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                ),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "确认存样",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanResultCard(
    barcode: String,
    foodName: String,
    weightGrams: Float,
    scanTime: Long,
    scanMealType: String,
) {
    val timeStr = remember(scanTime) {
        if (scanTime > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(java.util.Date(scanTime))
        } else {
            ""
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "扫描结果",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2563EB),
            )

            // 第一行：菜品名 + 餐次标签
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = foodName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                    modifier = Modifier.weight(1f),
                )
                if (scanMealType.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2563EB).copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = scanMealType,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF2563EB),
                        )
                    }
                }
            }

            // 第二行：重量 + 时间
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (weightGrams > 0) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "重量",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280),
                        )
                        Text(
                            text = "${weightGrams}g",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF111827),
                        )
                    }
                }
                if (timeStr.isNotBlank()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "留样时间",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280),
                        )
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF111827),
                        )
                    }
                }
            }

            // 条形码（较小字体）
            Text(
                text = "条码: $barcode",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SampleTable(samples: List<com.foodfridge.domain.model.FoodSample>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 表头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2563EB))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                TableHeaderCell("食品名称", Modifier.weight(2f))
                TableHeaderCell("留样人", Modifier.weight(1.5f))
                TableHeaderCell("时间", Modifier.weight(1.5f))
                TableHeaderCell("重量", Modifier.weight(1f))
                TableHeaderCell("状态", Modifier.weight(1.2f))
            }

            // 数据行
            samples.forEachIndexed { index, sample ->
                val bgColor = if (index % 2 == 0) Color.White else Color(0xFFF8FAFC)
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(java.util.Date(sample.storeTime))
                val statusColor = when (sample.status) {
                    SampleStatus.STORING -> Color(0xFF2563EB)
                    SampleStatus.WAITING_DISPOSE -> Color(0xFFF97316)
                    SampleStatus.WAITING -> Color(0xFF6B7280)
                }
                val statusBg = when (sample.status) {
                    SampleStatus.STORING -> Color(0xFFDBEAFE)
                    SampleStatus.WAITING_DISPOSE -> Color(0xFFFFEDD5)
                    SampleStatus.WAITING -> Color(0xFFF3F4F6)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell(sample.foodName, Modifier.weight(2f))
                    TableCell(sample.operatorName, Modifier.weight(1.5f))
                    TableCell(timeStr, Modifier.weight(1.5f))
                    TableCell("${sample.weightGrams.toInt()}g", Modifier.weight(1f))
                    Box(modifier = Modifier.weight(1.2f)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusBg,
                        ) {
                            Text(
                                text = sample.status.displayName,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                if (index < samples.size - 1) {
                    Divider(color = Color(0xFFE2E8F0), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TableCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Color(0xFF374151),
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}
