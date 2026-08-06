package com.foodfridge.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.data.hardware.ModbusByteOrder
import com.foodfridge.data.hardware.ModbusTemperatureValueMode
import com.foodfridge.data.hardware.ModbusValueType
import com.foodfridge.data.hardware.ModbusWordOrder
import com.foodfridge.domain.auth.FaceAuthenticationPolicy
import com.foodfridge.domain.model.User

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFaceEnroll: (Int) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<User?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<User?>(null) }
    var showClearSamplesDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var previewUser by remember { mutableStateOf<User?>(null) }
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedUserIds by remember { mutableStateOf(setOf<Int>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showModbusConfig by remember { mutableStateOf(false) }
    var showAdvancedModbus by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadUsers()
        viewModel.loadAdminPassword()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 顶部返回 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    text = "设置",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        InfoRow(label = "当前认证人员：", value = uiState.currentUserName)

        Spacer(modifier = Modifier.height(12.dp))

        // 密码 + 修改按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "密码：",
                fontSize = 14.sp,
                color = Color(0xFF374151),
            )
            Text(
                text = "******",
                fontSize = 14.sp,
                color = Color(0xFF111827),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { showChangePasswordDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                ),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    text = "修改",
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 人员管理 + 删除按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isDeleteMode) "请选择要删除的员工" else "人员管理",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDeleteMode) Color(0xFFDC2626) else Color(0xFF374151),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!isDeleteMode && uiState.users.isNotEmpty()) {
                Button(
                    onClick = {
                        isDeleteMode = true
                        selectedUserIds = emptySet()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                    ),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(
                        text = "删除",
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 人员照片列表
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 已有人员
            items(uiState.users) { user ->
                PersonAvatar(
                    name = user.fullName,
                    role = user.role,
                    facePhotoPath = user.facePhotoPath,
                    isSelected = selectedUserIds.contains(user.id),
                    isDeleteMode = isDeleteMode,
                    onClick = {
                        if (isDeleteMode) {
                            selectedUserIds = if (selectedUserIds.contains(user.id)) {
                                selectedUserIds - user.id
                            } else {
                                selectedUserIds + user.id
                            }
                        } else if (user.facePhotoPath != null) {
                            previewUser = user
                        } else {
                            onNavigateToFaceEnroll(user.id)
                        }
                    },
                    onLongClick = {
                        if (!isDeleteMode) {
                            showEditDialog = user
                        }
                    },
                )
            }
            // 添加按钮（删除模式下隐藏）
            if (!isDeleteMode) {
                item {
                    AddPersonButton(onClick = { showAddDialog = true })
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 双人脸模式开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "是否开启双人脸模式",
                fontSize = 14.sp,
                color = Color(0xFF374151),
            )
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = uiState.dualFaceAuthEnabled,
                onCheckedChange = { viewModel.toggleDualFaceAuth(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2563EB),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD1D5DB),
                ),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 维护 ──────────────────────────────────
        Text(
            text = "维护",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF374151),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.exportLogs() },
            enabled = !uiState.isExportingLogs,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF59E0B),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isExportingLogs) "正在导出..." else "导出运行日志")
        }

        uiState.exportLogResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                fontSize = 12.sp,
                color = when {
                    result.startsWith("✅") -> Color(0xFF059669)
                    result.startsWith("❌") -> Color(0xFFDC2626)
                    else -> Color(0xFFD97706)
                },
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        var modbusPath by remember { mutableStateOf(uiState.modbusDevicePath) }
        var modbusBaud by remember { mutableStateOf(uiState.modbusBaudRate.toString()) }
        var modbusParity by remember { mutableStateOf(uiState.modbusParity) }
        var modbusStopBits by remember { mutableStateOf(uiState.modbusStopBits) }
        var modbusSlave by remember { mutableStateOf(uiState.modbusSlaveAddress.toString()) }
        var modbusFunction by remember { mutableStateOf(uiState.modbusFunctionCode.toString()) }
        var modbusRegisterAddress by remember { mutableStateOf(uiState.modbusRegisterAddress.toString()) }
        var modbusRegisterCount by remember { mutableStateOf(uiState.modbusRegisterCount.toString()) }
        var modbusTemperatureOffset by remember {
            mutableStateOf(uiState.modbusTemperatureRegisterOffset.toString())
        }
        var modbusValueType by remember { mutableStateOf(uiState.modbusValueType) }
        var modbusByteOrder by remember { mutableStateOf(uiState.modbusByteOrder) }
        var modbusWordOrder by remember { mutableStateOf(uiState.modbusWordOrder) }
        var modbusValueMode by remember { mutableStateOf(uiState.modbusValueMode) }
        var modbusTemperatureScale by remember { mutableStateOf(uiState.modbusTemperatureScale.toString()) }
        var modbusCalibrationOffset by remember { mutableStateOf(uiState.modbusCalibrationOffset.toString()) }

        LaunchedEffect(
            uiState.modbusDevicePath,
            uiState.modbusBaudRate,
            uiState.modbusParity,
            uiState.modbusStopBits,
            uiState.modbusSlaveAddress,
            uiState.modbusFunctionCode,
            uiState.modbusRegisterAddress,
            uiState.modbusRegisterCount,
            uiState.modbusTemperatureRegisterOffset,
            uiState.modbusValueType,
            uiState.modbusByteOrder,
            uiState.modbusWordOrder,
            uiState.modbusValueMode,
            uiState.modbusTemperatureScale,
            uiState.modbusCalibrationOffset,
        ) {
            modbusPath = uiState.modbusDevicePath
            modbusBaud = uiState.modbusBaudRate.toString()
            modbusParity = uiState.modbusParity
            modbusStopBits = uiState.modbusStopBits
            modbusSlave = uiState.modbusSlaveAddress.toString()
            modbusFunction = uiState.modbusFunctionCode.toString()
            modbusRegisterAddress = uiState.modbusRegisterAddress.toString()
            modbusRegisterCount = uiState.modbusRegisterCount.toString()
            modbusTemperatureOffset = uiState.modbusTemperatureRegisterOffset.toString()
            modbusValueType = uiState.modbusValueType
            modbusByteOrder = uiState.modbusByteOrder
            modbusWordOrder = uiState.modbusWordOrder
            modbusValueMode = uiState.modbusValueMode
            modbusTemperatureScale = uiState.modbusTemperatureScale.toString()
            modbusCalibrationOffset = uiState.modbusCalibrationOffset.toString()
        }

        Button(
            onClick = { showModbusConfig = !showModbusConfig },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2563EB),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text(
                text = "Modbus 温度传感器配置",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = if (showModbusConfig) {
                    Icons.Default.ExpandLess
                } else {
                    Icons.Default.ExpandMore
                },
                contentDescription = if (showModbusConfig) "收起配置" else "展开配置",
            )
        }

        if (showModbusConfig) {
            Spacer(modifier = Modifier.height(12.dp))

            // 启用开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "启用 Modbus 温度读取",
                fontSize = 14.sp,
                color = Color(0xFF374151),
            )
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = uiState.modbusEnabled,
                onCheckedChange = { enabled ->
                    viewModel.saveModbusConfig(
                        devicePath = modbusPath,
                        baudRate = modbusBaud.toIntOrNull() ?: 0,
                        parity = modbusParity,
                        stopBits = modbusStopBits,
                        slaveAddress = modbusSlave.toIntOrNull() ?: -1,
                        functionCode = parseFlexibleInt(modbusFunction) ?: -1,
                        registerAddress = parseFlexibleInt(modbusRegisterAddress) ?: -1,
                        registerCount = modbusRegisterCount.toIntOrNull() ?: -1,
                        temperatureRegisterOffset = modbusTemperatureOffset.toIntOrNull() ?: -1,
                        valueType = modbusValueType,
                        byteOrder = modbusByteOrder,
                        wordOrder = modbusWordOrder,
                        valueMode = modbusValueMode,
                        temperatureScale = modbusTemperatureScale.toFloatOrNull() ?: Float.NaN,
                        calibrationOffset = modbusCalibrationOffset.toFloatOrNull() ?: Float.NaN,
                        enabled = enabled,
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2563EB),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD1D5DB),
                ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = buildString {
                append("连接状态：${uiState.modbusConnectionStatus}")
                uiState.modbusCurrentTemperature?.let {
                    append("，当前温度：%.1f°C".format(it))
                }
            },
            fontSize = 13.sp,
            color = if (uiState.modbusConnectionStatus == "已连接") {
                Color(0xFF059669)
            } else {
                Color(0xFF6B7280)
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 串口路径
        OutlinedTextField(
            value = modbusPath,
            onValueChange = { modbusPath = it },
            label = { Text("串口路径（F28V2 通常为 /dev/ttyS2）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2563EB),
                focusedLabelColor = Color(0xFF2563EB),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 波特率
        OutlinedTextField(
            value = modbusBaud,
            onValueChange = { modbusBaud = it },
            label = { Text("波特率（如 115200）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2563EB),
                focusedLabelColor = Color(0xFF2563EB),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 从机地址
        OutlinedTextField(
            value = modbusSlave,
            onValueChange = { modbusSlave = it },
            label = { Text("从机地址（如 255）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2563EB),
                focusedLabelColor = Color(0xFF2563EB),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { showAdvancedModbus = !showAdvancedModbus },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (showAdvancedModbus) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("高级协议参数")
        }

        if (showAdvancedModbus) {
            IntegerOptionSelector(
                label = "校验位",
                options = listOf(0 to "无校验", 1 to "奇校验", 2 to "偶校验"),
                selectedValue = modbusParity,
                onValueSelected = { modbusParity = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            IntegerOptionSelector(
                label = "停止位",
                options = listOf(1 to "1 位", 2 to "2 位"),
                selectedValue = modbusStopBits,
                onValueSelected = { modbusStopBits = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            ValueOptionSelector(
                label = "数据类型",
                options = listOf(
                    ModbusValueType.INT16 to "I16",
                    ModbusValueType.UINT16 to "U16",
                    ModbusValueType.INT32 to "I32",
                    ModbusValueType.UINT32 to "U32",
                    ModbusValueType.FLOAT32 to "F32",
                ),
                selectedValue = modbusValueType,
                onValueSelected = { modbusValueType = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            ValueOptionSelector(
                label = "寄存器内字节序",
                options = listOf(
                    ModbusByteOrder.BIG_ENDIAN to "大端",
                    ModbusByteOrder.LITTLE_ENDIAN to "小端",
                ),
                selectedValue = modbusByteOrder,
                onValueSelected = { modbusByteOrder = it },
            )
            if (modbusValueType.registerWidth == 2) {
                Spacer(modifier = Modifier.height(8.dp))
                ValueOptionSelector(
                    label = "32 位字序",
                    options = listOf(
                        ModbusWordOrder.HIGH_WORD_FIRST to "高字优先",
                        ModbusWordOrder.LOW_WORD_FIRST to "低字优先",
                    ),
                    selectedValue = modbusWordOrder,
                    onValueSelected = { modbusWordOrder = it },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            ValueOptionSelector(
                label = "数值含义",
                options = listOf(
                    ModbusTemperatureValueMode.DIRECT_CELSIUS to "摄氏温度",
                    ModbusTemperatureValueMode.PT100_RESISTANCE to "PT100 Ω",
                    ModbusTemperatureValueMode.PT1000_RESISTANCE to "PT1000 Ω",
                ),
                selectedValue = modbusValueMode,
                onValueSelected = { modbusValueMode = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modbusFunction,
                onValueChange = { modbusFunction = it },
                label = { Text("功能码（3=保持寄存器，4=输入寄存器）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modbusRegisterAddress,
                onValueChange = { modbusRegisterAddress = it },
                label = { Text("起始寄存器（支持十进制或 0x 十六进制）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modbusRegisterCount,
                onValueChange = { modbusRegisterCount = it },
                label = { Text("读取寄存器数量") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modbusTemperatureOffset,
                onValueChange = { modbusTemperatureOffset = it },
                label = { Text("温度寄存器偏移（从 0 开始）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modbusTemperatureScale,
                onValueChange = { modbusTemperatureScale = it },
                label = { Text("数值倍率（示例为 0.1）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modbusCalibrationOffset,
                onValueChange = { modbusCalibrationOffset = it },
                label = { Text("校准偏移 °C（校准前保持 0）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 保存按钮
        Button(
            onClick = {
                val baud = modbusBaud.toIntOrNull() ?: 0
                val slave = modbusSlave.toIntOrNull() ?: -1
                viewModel.saveModbusConfig(
                    devicePath = modbusPath,
                    baudRate = baud,
                    parity = modbusParity,
                    stopBits = modbusStopBits,
                    slaveAddress = slave,
                    functionCode = parseFlexibleInt(modbusFunction) ?: -1,
                    registerAddress = parseFlexibleInt(modbusRegisterAddress) ?: -1,
                    registerCount = modbusRegisterCount.toIntOrNull() ?: -1,
                    temperatureRegisterOffset = modbusTemperatureOffset.toIntOrNull() ?: -1,
                    valueType = modbusValueType,
                    byteOrder = modbusByteOrder,
                    wordOrder = modbusWordOrder,
                    valueMode = modbusValueMode,
                    temperatureScale = modbusTemperatureScale.toFloatOrNull() ?: Float.NaN,
                    calibrationOffset = modbusCalibrationOffset.toFloatOrNull() ?: Float.NaN,
                    enabled = uiState.modbusEnabled,
                )
            },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2563EB),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存 Modbus 配置")
        }

            // 保存成功提示
            if (uiState.modbusConfigSaved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✅ Modbus 配置已保存",
                    fontSize = 12.sp,
                    color = Color(0xFF059669),
                )
            }
        }

        uiState.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                fontSize = 12.sp,
                color = Color(0xFFDC2626),
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 平台 API 地址配置 ──────────────────────────────────
        Text(
            text = "平台连接配置",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF374151),
        )

        Spacer(modifier = Modifier.height(8.dp))

        var apiUrl by remember { mutableStateOf(uiState.apiBaseUrl) }
        var apiKeyDraft by remember { mutableStateOf("") }

        LaunchedEffect(uiState.apiBaseUrl) {
            apiUrl = uiState.apiBaseUrl
        }
        LaunchedEffect(uiState.apiConnectionSaveResult) {
            if (uiState.apiConnectionSaveResult?.startsWith("✅") == true) {
                apiKeyDraft = ""
            }
        }

        OutlinedTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it },
            label = { Text("平台 API 地址（如 http://192.168.1.100:8000）") },
            placeholder = { Text("留空使用默认地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2563EB),
                focusedLabelColor = Color(0xFF2563EB),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            label = {
                Text(
                    if (uiState.apiDeviceKeyConfigured) {
                        "API Key（已配置）"
                    } else {
                        "API Key"
                    }
                )
            },
            placeholder = {
                Text(
                    if (uiState.apiDeviceKeyConfigured) {
                        "留空则保留当前密钥"
                    } else {
                        "请输入设备 API Key"
                    }
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2563EB),
                focusedLabelColor = Color(0xFF2563EB),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.saveApiConnection(apiUrl, apiKeyDraft) },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2563EB),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存平台配置")
        }

        uiState.apiConnectionSaveResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                fontSize = 12.sp,
                color = if (result.startsWith("✅")) Color(0xFF059669) else Color(0xFFDC2626),
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.testHeartbeat() },
            enabled = !uiState.isTestingHeartbeat,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF059669),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (uiState.isTestingHeartbeat) {
                    "正在测试心跳..."
                } else {
                    "测试心跳接口"
                }
            )
        }

        uiState.heartbeatTestResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                fontSize = 12.sp,
                color = when {
                    result.startsWith("✅") -> Color(0xFF059669)
                    result.startsWith("❌") -> Color(0xFFDC2626)
                    else -> Color(0xFF6B7280)
                },
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 危险操作区 ──────────────────────────────────
        Text(
            text = "危险操作",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFDC2626),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { showClearSamplesDialog = true },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFDC2626),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("清空所有留样数据")
        }

        uiState.clearSamplesResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                fontSize = 12.sp,
                color = if (result.startsWith("✅")) Color(0xFF059669) else Color(0xFFDC2626),
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 删除模式底部操作栏
        if (isDeleteMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        isDeleteMode = false
                        selectedUserIds = emptySet()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9CA3AF),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { showBatchDeleteConfirm = true },
                    enabled = selectedUserIds.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        disabledContainerColor = Color(0xFFFCA5A5),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("确认删除(${selectedUserIds.size})")
                }
            }
        }
    }

    // 添加用户对话框
    if (showAddDialog) {
        AddUserDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, employeeId, role ->
                viewModel.addUser(name, employeeId, role)
                showAddDialog = false
            },
            onEnrollFace = { name, employeeId, role ->
                showAddDialog = false
                viewModel.addUserAndGetId(name, employeeId, role) { userId ->
                    onNavigateToFaceEnroll(userId)
                }
            },
        )
    }

    // 编辑用户对话框
    showEditDialog?.let { user ->
        EditUserDialog(
            user = user,
            onDismiss = { showEditDialog = null },
            onConfirm = { name, employeeId, role ->
                viewModel.updateUser(
                    user.copy(
                        fullName = name,
                        employeeId = employeeId,
                        role = role,
                    )
                )
                showEditDialog = null
            },
            onEnrollFace = {
                showEditDialog = null
                onNavigateToFaceEnroll(user.id)
            },
        )
    }

    // 修改密码对话框
    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { newPassword ->
                viewModel.changeAdminPassword(newPassword)
                showChangePasswordDialog = false
            },
        )
    }

    // 图片预览对话框
    previewUser?.let { user ->
        ImagePreviewDialog(
            user = user,
            onDismiss = { previewUser = null },
        )
    }

    // 批量删除确认对话框
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("确认批量删除") },
            text = { Text("确定要删除选中的 ${selectedUserIds.size} 位员工吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteUsers(selectedUserIds)
                        isDeleteMode = false
                        selectedUserIds = emptySet()
                        showBatchDeleteConfirm = false
                    },
                ) {
                    Text("删除", color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 清空留样数据确认对话框
    if (showClearSamplesDialog) {
        ClearSamplesDialog(
            onDismiss = { showClearSamplesDialog = false },
            onConfirm = { password ->
                viewModel.clearAllSamples(password)
                showClearSamplesDialog = false
            },
        )
    }

    // 删除确认对话框
    showDeleteConfirm?.let { user ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除用户「${user.fullName}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteUser(user.id)
                        showDeleteConfirm = null
                    },
                ) {
                    Text("删除", color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun IntegerOptionSelector(
    label: String,
    options: List<Pair<Int, String>>,
    selectedValue: Int,
    onValueSelected: (Int) -> Unit,
) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = Color(0xFF4B5563),
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, text) ->
            val selected = value == selectedValue
            Button(
                onClick = { onValueSelected(value) },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color(0xFF2563EB) else Color(0xFFE5E7EB),
                    contentColor = if (selected) Color.White else Color(0xFF374151),
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(text = text, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun <T> ValueOptionSelector(
    label: String,
    options: List<Pair<T, String>>,
    selectedValue: T,
    onValueSelected: (T) -> Unit,
) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = Color(0xFF4B5563),
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, text) ->
            val selected = value == selectedValue
            Button(
                onClick = { onValueSelected(value) },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color(0xFF2563EB) else Color(0xFFE5E7EB),
                    contentColor = if (selected) Color.White else Color(0xFF374151),
                ),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(text = text, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFF374151),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = Color(0xFF111827),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonAvatar(
    name: String,
    role: String,
    facePhotoPath: String?,
    isSelected: Boolean = false,
    isDeleteMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (isDeleteMode) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
        },
    ) {
        // 选择复选框（删除模式下显示）
        if (isDeleteMode) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isSelected) Color(0xFF2563EB) else Color(0xFFD1D5DB)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选中",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        // 头像占位
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFE5E7EB))
                .then(
                    if (isSelected && isDeleteMode) {
                        Modifier.border(2.dp, Color(0xFFDC2626), RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (facePhotoPath != null) {
                val bitmap = remember(facePhotoPath) {
                    BitmapFactory.decodeFile(facePhotoPath)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = name.take(1),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B7280),
                    )
                }
            } else {
                Text(
                    text = name.take(1),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6B7280),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            fontSize = 12.sp,
            color = Color(0xFF374151),
        )
        Text(
            text = FaceAuthenticationPolicy.displayName(role),
            fontSize = 11.sp,
            color = Color(0xFF6B7280),
        )
    }
}

@Composable
private fun AddPersonButton(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(
                    BorderStroke(1.dp, Color(0xFFD1D5DB)),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加",
                modifier = Modifier.size(28.dp),
                tint = Color(0xFF2563EB),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "",
            fontSize = 12.sp,
            color = Color.Transparent,
        )
    }
}

@Composable
private fun AddUserDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onEnrollFace: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("SAMPLER") }
    val roles = listOf(
        "SUPERVISOR" to "监督员",
        "SAMPLER" to "留样员",
    )
    val isFormValid = name.isNotBlank() && employeeId.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加人员") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = { Text("工号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    text = "角色",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF374151),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    roles.forEach { (roleValue, roleLabel) ->
                        val isSelected = selectedRole == roleValue
                        val bgColor = if (isSelected) Color(0xFF2563EB) else Color(0xFFF3F4F6)
                        val textColor = if (isSelected) Color.White else Color(0xFF6B7280)
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = bgColor,
                            onClick = { selectedRole = roleValue },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    text = roleLabel,
                                    color = textColor,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
                // 注册人脸按钮
                Button(
                    onClick = {
                        if (isFormValid) {
                            onEnrollFace(name, employeeId, selectedRole)
                        }
                    },
                    enabled = isFormValid,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                    ),
                ) {
                    Text("注册人脸")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isFormValid) {
                        onConfirm(name, employeeId, selectedRole)
                    }
                },
                enabled = isFormValid,
            ) {
                Text("仅保存")
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
private fun EditUserDialog(
    user: User,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onEnrollFace: () -> Unit,
) {
    var name by remember { mutableStateOf(user.fullName) }
    var employeeId by remember { mutableStateOf(user.employeeId) }
    var selectedRole by remember { mutableStateOf(user.role) }
    val roles = listOf(
        "SUPERVISOR" to "监督员",
        "SAMPLER" to "留样员",
    )
    val isFormValid = name.isNotBlank() && employeeId.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑人员") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = { Text("工号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    text = "角色",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF374151),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    roles.forEach { (roleValue, roleLabel) ->
                        val isSelected = selectedRole == roleValue
                        val bgColor = if (isSelected) Color(0xFF2563EB) else Color(0xFFF3F4F6)
                        val textColor = if (isSelected) Color.White else Color(0xFF6B7280)
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = bgColor,
                            onClick = { selectedRole = roleValue },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    text = roleLabel,
                                    color = textColor,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
                // 注册/重录人脸按钮
                Button(
                    onClick = onEnrollFace,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                    ),
                ) {
                    Text(if (user.facePhotoPath != null) "重录人脸" else "注册人脸")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isFormValid) {
                        onConfirm(name, employeeId, selectedRole)
                    }
                },
                enabled = isFormValid,
            ) {
                Text("保存")
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
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前使用的是默认密码，为了安全请修改密码",
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        errorMessage = null
                    },
                    label = { Text("新密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text("确认新密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFDC2626),
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        newPassword.isBlank() -> errorMessage = "密码不能为空"
                        newPassword.length < 4 -> errorMessage = "密码至少4位"
                        newPassword != confirmPassword -> errorMessage = "两次输入的密码不一致"
                        else -> onConfirm(newPassword)
                    }
                },
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后再说")
            }
        },
    )
}

@Composable
private fun ClearSamplesDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空所有留样数据", color = Color(0xFFDC2626)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "此操作将永久删除所有留样记录，不可恢复！",
                    fontSize = 14.sp,
                    color = Color(0xFFDC2626),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "请输入管理员密码以确认操作：",
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("管理员密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFDC2626),
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.isBlank()) {
                        errorMessage = "请输入密码"
                    } else {
                        onConfirm(password)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDC2626),
                ),
            ) {
                Text("确认清空")
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
private fun ImagePreviewDialog(
    user: User,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(user.facePhotoPath) {
        user.facePhotoPath?.let(BitmapFactory::decodeFile)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人员信息") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "${user.fullName}的人脸照片",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            text = "无法加载图片",
                            color = Color(0xFFDC2626),
                        )
                    }
                }
                PersonInfoRow(label = "姓名", value = user.fullName)
                PersonInfoRow(label = "工号", value = user.employeeId)
                PersonInfoRow(
                    label = "角色",
                    value = FaceAuthenticationPolicy.displayName(user.role),
                )
                PersonInfoRow(label = "状态", value = if (user.isActive) "启用" else "停用")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun PersonInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label：",
            modifier = Modifier.width(64.dp),
            fontSize = 14.sp,
            color = Color(0xFF6B7280),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF111827),
        )
    }
}

internal fun parseFlexibleInt(value: String): Int? {
    val normalized = value.trim()
    return if (normalized.startsWith("0x", ignoreCase = true)) {
        normalized.substring(2).toIntOrNull(16)
    } else {
        normalized.toIntOrNull()
    }
}
