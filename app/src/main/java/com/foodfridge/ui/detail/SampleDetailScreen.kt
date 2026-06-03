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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SampleDetailScreen(
    mealType: String,
    dayOffset: Int,
    onNavigateBack: () -> Unit,
    viewModel: SampleDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(mealType, dayOffset) {
        viewModel.loadSamples(mealType, dayOffset)
    }

    val dayLabel = if (uiState.dayOffset == 0) "今天" else "昨天"

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
                    text = "$dayLabel · ${uiState.mealType.displayName} - 留样详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // 留样列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.samples.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$dayLabel 暂无留样记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFF9CA3AF),
                        )
                    }
                }
            } else {
                items(
                    count = uiState.samples.size,
                    key = { index -> uiState.samples[index].id },
                ) { index ->
                    val sample = uiState.samples[index]
                    SampleDetailCard(sample = sample)
                }
            }
        }
    }
}

@Composable
private fun SampleDetailCard(sample: com.foodfridge.domain.model.FoodSample) {
    val cardBg = when (sample.status) {
        SampleStatus.STORING -> Color(0xFFDBEAFE)
        SampleStatus.WAITING_DISPOSE -> Color(0xFFFFEDD5)
        SampleStatus.WAITING -> Color(0xFFFFFFFF)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 食品名称 + 状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sample.foodName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                )
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when (sample.status) {
                        SampleStatus.STORING -> Color(0xFF3B82F6)
                        SampleStatus.WAITING_DISPOSE -> Color(0xFFF97316)
                        SampleStatus.WAITING -> Color(0xFFF3F4F6)
                    },
                ) {
                    Text(
                        text = sample.status.displayName,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = when (sample.status) {
                            SampleStatus.STORING -> Color.White
                            SampleStatus.WAITING_DISPOSE -> Color.White
                            SampleStatus.WAITING -> Color(0xFF6B7280)
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            }

            // 留样人
            InfoRow(
                icon = Icons.Default.Person,
                label = "留样人",
                value = sample.operatorName,
            )

            // 留样时间
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(java.util.Date(sample.storeTime))
            InfoRow(
                icon = Icons.Default.CalendarToday,
                label = "留样时间",
                value = timeStr,
            )

            // 留样重量
            InfoRow(
                icon = Icons.Default.Scale,
                label = "留样重量",
                value = "${sample.weightGrams}g",
            )
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color(0xFF6B7280),
        )
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6B7280),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF111827),
        )
    }
}
