package com.foodfridge.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.domain.model.MealType

@Composable
fun AddSampleScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSerialScan: () -> Unit,
    viewModel: AddSampleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                    text = "新增留样",
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 串口扫码按钮
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !uiState.isSaving) {
                        onNavigateToSerialScan()
                    },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFEFF6FF),
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = Color(0xFF2563EB),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (uiState.barcode.isNotBlank()) "已扫描: ${uiState.foodName}" else "点击扫码",
                        color = Color(0xFF2563EB),
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                }
            }

            // 食品名称
            OutlinedTextField(
                value = uiState.foodName,
                onValueChange = viewModel::onFoodNameChange,
                label = { Text("食品名称") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2563EB),
                    focusedLabelColor = Color(0xFF2563EB),
                ),
                singleLine = true,
            )

            // 留样重量
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

            // 餐次选择
            Text(
                text = "选择餐次",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF374151),
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MealType.entries.forEach { mealType ->
                    MealTypeSelector(
                        mealType = mealType,
                        isSelected = uiState.mealType == mealType,
                        onClick = { viewModel.onMealTypeChange(mealType) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

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

            // 保存按钮
            Button(
                onClick = { viewModel.saveSample(onNavigateBack) },
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
private fun MealTypeSelector(
    mealType: MealType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isSelected) Color(0xFF2563EB) else Color(0xFFF3F4F6)
    val textColor = if (isSelected) Color.White else Color(0xFF6B7280)

    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = mealType.displayName,
                color = textColor,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp,
            )
        }
    }
}
