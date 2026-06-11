package com.foodfridge.ui.detail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.domain.model.MealType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SampleDetailScreen(
    mealType: String,
    dayOffset: Int,
    onNavigateBack: () -> Unit,
    onNavigateToBarcodeScan: () -> Unit,
    viewModel: SampleDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(mealType, dayOffset) {
        viewModel.loadSamples(mealType, dayOffset)
    }

    val mealTypeEnum = MealType.valueOf(mealType)
    val dayDateStr = formatDateFromOffset(dayOffset)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        // 顶部返回 + 标题
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
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$dayDateStr${mealTypeEnum.displayName}留样",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                )
            }
            // 占位保持居中
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = Color.Transparent,
                )
            }
        }

        // 表格内容
        if (uiState.samples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无留样记录",
                    fontSize = 16.sp,
                    color = Color(0xFF9CA3AF),
                )
            }
        } else {
            // 表头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F8FF))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                TableHeaderCell("留样人", Modifier.weight(1f))
                TableHeaderCell("留样时间", Modifier.weight(1.2f))
                TableHeaderCell("留样名称", Modifier.weight(1f))
                TableHeaderCell("留样重量", Modifier.weight(1f))
                TableHeaderCell("留样餐次", Modifier.weight(1f))
            }

            // 数据行
            LazyColumn {
                items(uiState.samples) { sample ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        TableCell(sample.operatorName, Modifier.weight(1f))
                        TableCell(
                            formatDate(sample.storeTime),
                            Modifier.weight(1.2f)
                        )
                        TableCell(sample.foodName, Modifier.weight(1f))
                        TableCell("${sample.weightGrams.toInt()}", Modifier.weight(1f))
                        TableCell(
                            mealTypeEnum.displayName,
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 底部扫描条形码按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onNavigateToBarcodeScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "扫描条形码",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF374151),
    )
}

@Composable
private fun TableCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 13.sp,
        color = Color(0xFF111827),
    )
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("yyyy.M.d", Locale.getDefault()).format(Date(timestamp))
}

private fun formatDateFromOffset(dayOffset: Int): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
    return SimpleDateFormat("M月d日", Locale.getDefault()).format(calendar.time)
}
