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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.ui.home.DateRollingLogic
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
    // 使用与首页一致的日期显示：从 uiState 中获取 baseDate，如果没有则使用今天
    val baseDate = uiState.baseDate.takeIf { it > 0 } ?: System.currentTimeMillis()
    val dayDateStr = formatDateFromBaseDate(baseDate, dayOffset)
    val canStoreToday = DateRollingLogic.isTodayColumn(
        firstColumnDate = baseDate,
        dayOffset = dayOffset,
        now = System.currentTimeMillis(),
    )

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

        // 搜索框
        if (uiState.samples.isNotEmpty()) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("搜索菜品名或操作员", fontSize = 13.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2563EB),
                    focusedLabelColor = Color(0xFF2563EB),
                ),
            )
        }

        // 表格内容
        val displaySamples = uiState.filteredSamples
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
        } else if (displaySamples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "未找到匹配的留样记录",
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableHeaderCell("选择", Modifier.weight(0.7f))
                TableHeaderCell("留样人", Modifier.weight(1f))
                TableHeaderCell("留样时间", Modifier.weight(1.2f))
                TableHeaderCell("留样名称", Modifier.weight(1f))
                TableHeaderCell("留样重量", Modifier.weight(1f))
                TableHeaderCell("状态", Modifier.weight(1f))
                TableHeaderCell("消样信息", Modifier.weight(1.3f))
            }

            // 数据行
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(displaySamples) { sample ->
                    val selectable = sample.status == SampleStatus.STORING ||
                        sample.status == SampleStatus.WAITING_DISPOSE
                    val checked = uiState.selectedSampleIds.contains(sample.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(0.7f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (selectable) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { viewModel.toggleSampleSelection(sample.id) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFF2563EB),
                                    ),
                                )
                            }
                        }
                        TableCell(sample.operatorName, Modifier.weight(1f))
                        TableCell(
                            formatDate(sample.storeTime),
                            Modifier.weight(1.2f)
                        )
                        TableCell(sample.foodName, Modifier.weight(1f))
                        TableCell("${sample.weightGrams.toInt()}g", Modifier.weight(1f))
                        TableCell(
                            sample.status.displayName,
                            Modifier.weight(1f),
                            color = when (sample.status) {
                                SampleStatus.STORING -> Color(0xFF2563EB)
                                SampleStatus.WAITING_DISPOSE -> Color(0xFFF97316)
                                SampleStatus.DISPOSED -> Color(0xFF22C55E)
                                SampleStatus.WAITING -> Color(0xFF6B7280)
                            }
                        )
                        val disposalInfo = if (sample.disposedAt != null && sample.disposedByName != null) {
                            "${sample.disposedByName}\n${formatDateTime(sample.disposedAt)}"
                        } else {
                            "--"
                        }
                        TableCell(disposalInfo, Modifier.weight(1.3f))
                    }
                }
            }

            // 全选 + 批量消样
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewModel.toggleSelectAll() }) {
                    Text(
                        text = "全选",
                        fontSize = 14.sp,
                        color = Color(0xFF2563EB),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "已选 ${uiState.selectedSampleIds.size} 条",
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280),
                )
            }
        }

        // 底部按钮：批量消样 + 扫描二维码
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.samples.isNotEmpty()) {
                Button(
                    onClick = { viewModel.requestDisposeSelected() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = uiState.selectedSampleIds.isNotEmpty() && !uiState.isDisposing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        disabledContainerColor = Color(0xFFE5E7EB),
                    ),
                ) {
                    Text(
                        text = if (uiState.isDisposing) {
                            "正在消样..."
                        } else {
                            "消样选中记录 (${uiState.selectedSampleIds.size})"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            uiState.errorMessage?.takeIf { !uiState.showAdminAuthDialog }?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    color = Color(0xFFDC2626),
                    fontSize = 13.sp,
                )
            }
            Button(
                onClick = onNavigateToBarcodeScan,
                enabled = canStoreToday,
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
                    text = if (canStoreToday) "扫描二维码" else "仅可在当天留样",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    // 管理员验证对话框
    if (uiState.showAdminAuthDialog) {
        AdminAuthDialog(
            onDismiss = { viewModel.dismissAdminAuthDialog() },
            onConfirm = { username, password ->
                viewModel.confirmDispose(username, password)
            },
            earlySampleCount = uiState.adminRequiredSampleCount,
            isDisposing = uiState.isDisposing,
            errorMessage = uiState.errorMessage,
        )
    }

    // 消样成功提示
    if (uiState.disposeSuccess) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("消样成功") },
            text = {
                Text("选中的留样已消样，操作人：${uiState.lastDisposeOperatorName ?: "未知"}")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDisposeSuccess() }) {
                    Text("确定")
                }
            },
        )
    }
}

@Composable
private fun AdminAuthDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    earlySampleCount: Int,
    isDisposing: Boolean,
    errorMessage: String?,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理员验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "选中记录中有 $earlySampleCount 条留样未满48小时，需要管理员权限验证",
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("管理员账号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF2563EB),
                        focusedLabelColor = Color(0xFF2563EB),
                    ),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF2563EB),
                        focusedLabelColor = Color(0xFF2563EB),
                    ),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFDC2626),
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(username, password) },
                enabled = !isDisposing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDC2626),
                ),
            ) {
                Text(if (isDisposing) "正在验证..." else "确认消样")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
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
private fun TableCell(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF111827),
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 13.sp,
        color = color,
    )
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("yyyy.M.d", Locale.getDefault()).format(Date(timestamp))
}

private fun formatDateTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy.M.d HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatDateFromBaseDate(baseDate: Long, offset: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        timeInMillis = baseDate
        add(java.util.Calendar.DAY_OF_MONTH, offset)
    }
    return SimpleDateFormat("M月d日", Locale.getDefault()).format(calendar.time)
}
