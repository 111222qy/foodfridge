package com.foodfridge.ui.settings

import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.data.hardware.HardwareManager
import com.foodfridge.data.hardware.ModbusByteOrder
import com.foodfridge.data.hardware.ModbusTemperatureValueMode
import com.foodfridge.data.hardware.ModbusValueType
import com.foodfridge.data.hardware.ModbusWordOrder
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.interceptor.ApiKeyInterceptor
import com.foodfridge.data.remote.device.interceptor.DynamicBaseUrlInterceptor
import com.foodfridge.domain.face.FaceEngine
import com.foodfridge.domain.model.User
import com.foodfridge.domain.repository.DeviceUploadRepository
import com.foodfridge.domain.repository.FoodSampleRepository
import com.foodfridge.domain.repository.UserRepository
import com.foodfridge.util.FileLoggingTree
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

private const val LOG_EXPORT_AUTHORITY = "com.foodfridge.fileprovider"

data class SettingsUiState(
    val users: List<User> = emptyList(),
    val isLoading: Boolean = false,
    val dualFaceAuthEnabled: Boolean = false,
    val currentUserName: String = "admin",
    val adminPassword: String? = null,
    val apiBaseUrl: String = "",
    val apiDeviceKeyConfigured: Boolean = false,
    val apiConnectionSaveResult: String? = null,
    val heartbeatTestResult: String? = null,
    val isTestingHeartbeat: Boolean = false,
    val exportLogResult: String? = null,
    val isExportingLogs: Boolean = false,
    val errorMessage: String? = null,
    val clearSamplesResult: String? = null,
    // Modbus 温度配置
    val modbusDevicePath: String = "/dev/ttyS2",
    val modbusBaudRate: Int = 115200,
    val modbusParity: Int = 0,
    val modbusStopBits: Int = 1,
    val modbusSlaveAddress: Int = 0xFF,
    val modbusFunctionCode: Int = 0x03,
    val modbusRegisterAddress: Int = 0x0000,
    val modbusRegisterCount: Int = 2,
    val modbusTemperatureRegisterOffset: Int = 1,
    val modbusValueType: ModbusValueType = ModbusValueType.INT16,
    val modbusByteOrder: ModbusByteOrder = ModbusByteOrder.BIG_ENDIAN,
    val modbusWordOrder: ModbusWordOrder = ModbusWordOrder.HIGH_WORD_FIRST,
    val modbusValueMode: ModbusTemperatureValueMode = ModbusTemperatureValueMode.DIRECT_CELSIUS,
    val modbusTemperatureScale: Float = 0.1f,
    val modbusCalibrationOffset: Float = 0f,
    val modbusEnabled: Boolean = false,
    val modbusConfigSaved: Boolean = false,
    val modbusConnectionStatus: String = "未连接",
    val modbusCurrentTemperature: Float? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val userRepository: UserRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val hardwareManager: HardwareManager,
    private val dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
    private val apiKeyInterceptor: ApiKeyInterceptor,
    private val deviceUploadRepository: DeviceUploadRepository,
    private val foodSampleRepository: FoodSampleRepository,
    private val faceEngine: FaceEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadDualFaceAuthConfig()
        loadAdminPassword()
        loadApiConnection()
        loadModbusConfig()
        observeModbusStatus()
    }

    private fun loadDualFaceAuthConfig() {
        viewModelScope.launch {
            val enabled = userPreferencesRepository.dualFaceAuthEnabled.first()
            _uiState.value = _uiState.value.copy(dualFaceAuthEnabled = enabled)
        }
    }

    fun loadAdminPassword() {
        viewModelScope.launch {
            val password = userPreferencesRepository.adminPassword.first()
            _uiState.value = _uiState.value.copy(adminPassword = password)
        }
    }

    fun changeAdminPassword(newPassword: String) {
        viewModelScope.launch {
            try {
                userPreferencesRepository.saveAdminPassword(newPassword)
                _uiState.value = _uiState.value.copy(adminPassword = newPassword)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "修改密码失败: ${e.message}",
                )
            }
        }
    }

    fun loadUsers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                userRepository.getAllUsers().collect { users ->
                    _uiState.value = _uiState.value.copy(
                        users = users.sortedBy { it.fullName },
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "加载失败: ${e.message}",
                )
            }
        }
    }

    fun addUser(fullName: String, employeeId: String, role: String) {
        viewModelScope.launch {
            try {
                val user = User(
                    id = 0,
                    fullName = fullName,
                    employeeId = employeeId,
                    role = role,
                    isActive = true,
                )
                userRepository.insertUser(user)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "添加失败: ${e.message}",
                )
            }
        }
    }

    fun addUserAndGetId(fullName: String, employeeId: String, role: String, onUserCreated: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val user = User(
                    id = 0,
                    fullName = fullName,
                    employeeId = employeeId,
                    role = role,
                    isActive = true,
                )
                val userId = userRepository.insertUser(user)
                onUserCreated(userId.toInt())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "添加失败: ${e.message}",
                )
            }
        }
    }

    fun deleteUser(userId: Int) {
        viewModelScope.launch {
            try {
                userRepository.deleteUserById(userId)
                faceEngine.removeUserFromCache(userId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "删除失败: ${e.message}",
                )
            }
        }
    }

    fun deleteUsers(userIds: Set<Int>) {
        viewModelScope.launch {
            try {
                userIds.forEach { userId ->
                    userRepository.deleteUserById(userId)
                    faceEngine.removeUserFromCache(userId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "批量删除失败: ${e.message}",
                )
            }
        }
    }

    fun updateUser(user: User) {
        viewModelScope.launch {
            try {
                userRepository.updateUser(user)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "更新用户失败: ${e.message}",
                )
            }
        }
    }

    /** 清空所有留样数据（需管理员密码确认） */
    fun clearAllSamples(adminPassword: String) {
        viewModelScope.launch {
            try {
                val savedPassword = userPreferencesRepository.adminPassword.first() ?: "admin"
                if (adminPassword != savedPassword) {
                    _uiState.value = _uiState.value.copy(
                        clearSamplesResult = "❌ 管理员密码错误",
                    )
                    return@launch
                }
                foodSampleRepository.deleteAllSamples()
                _uiState.value = _uiState.value.copy(
                    clearSamplesResult = "✅ 已清空所有留样数据",
                )
                Timber.w("所有留样数据已被管理员清空")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    clearSamplesResult = "❌ 清空失败: ${e.message}",
                )
            }
        }
    }

    fun clearSamplesResultAck() {
        _uiState.value = _uiState.value.copy(clearSamplesResult = null)
    }

    fun toggleDualFaceAuth(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userPreferencesRepository.setDualFaceAuthEnabled(enabled)
                _uiState.value = _uiState.value.copy(dualFaceAuthEnabled = enabled)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "设置失败: ${e.message}",
                )
            }
        }
    }

    // ── 平台连接配置 ──────────────────────────────────────────

    private fun loadApiConnection() {
        viewModelScope.launch {
            try {
                val url = userPreferencesRepository.apiBaseUrl.first() ?: ""
                val apiKey = userPreferencesRepository.apiDeviceKey.first()
                dynamicBaseUrlInterceptor.setBaseUrl(url.ifBlank { null })
                apiKeyInterceptor.setApiKey(apiKey)
                _uiState.value = _uiState.value.copy(
                    apiBaseUrl = url,
                    apiDeviceKeyConfigured = apiKey != null,
                    apiConnectionSaveResult = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    apiConnectionSaveResult = "❌ 加载平台连接配置失败: ${e.message}",
                )
            }
        }
    }

    fun saveApiConnection(url: String, apiKeyDraft: String) {
        _uiState.value = _uiState.value.copy(apiConnectionSaveResult = null)
        viewModelScope.launch {
            try {
                val normalizedUrl = url.trim()
                val newApiKey = apiKeyDraft.trim().takeIf { it.isNotEmpty() }
                val savedApiKey = userPreferencesRepository.apiDeviceKey.first()
                val effectiveApiKey = newApiKey ?: savedApiKey
                if (effectiveApiKey == null) {
                    _uiState.value = _uiState.value.copy(
                        apiConnectionSaveResult = "❌ 请填写平台 API Key",
                    )
                    return@launch
                }

                userPreferencesRepository.saveApiConnection(
                    url = normalizedUrl.ifBlank { null },
                    apiDeviceKey = newApiKey,
                )
                dynamicBaseUrlInterceptor.setBaseUrl(normalizedUrl.ifBlank { null })
                apiKeyInterceptor.setApiKey(effectiveApiKey)
                _uiState.value = _uiState.value.copy(
                    apiBaseUrl = normalizedUrl,
                    apiDeviceKeyConfigured = true,
                    apiConnectionSaveResult = "✅ 平台连接配置已保存",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    apiConnectionSaveResult = "❌ 保存平台连接配置失败: ${e.message}",
                )
            }
        }
    }

    // ── Modbus 温度配置 ────────────────────────────────────────

    private fun loadModbusConfig() {
        viewModelScope.launch {
            try {
                val config = userPreferencesRepository.temperaturePreferenceSnapshot.first()
                val devicePath = config.modbusDevicePath
                    ?: hardwareManager.preferredModbusPorts().first()
                _uiState.value = _uiState.value.copy(
                    modbusDevicePath = devicePath,
                    modbusBaudRate = config.modbusBaudRate,
                    modbusParity = config.modbusParity,
                    modbusStopBits = config.modbusStopBits,
                    modbusSlaveAddress = config.modbusSlaveAddress,
                    modbusFunctionCode = config.modbusFunctionCode,
                    modbusRegisterAddress = config.modbusRegisterAddress,
                    modbusRegisterCount = config.modbusRegisterCount,
                    modbusTemperatureRegisterOffset = config.modbusTemperatureRegisterOffset,
                    modbusValueType = config.modbusValueType,
                    modbusByteOrder = config.modbusByteOrder,
                    modbusWordOrder = config.modbusWordOrder,
                    modbusValueMode = config.modbusValueMode,
                    modbusTemperatureScale = config.modbusTemperatureScale,
                    modbusCalibrationOffset = config.modbusCalibrationOffset,
                    modbusEnabled = config.modbusEnabled,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "加载 Modbus 配置失败: ${e.message}",
                )
            }
        }
    }

    fun testHeartbeat() {
        if (_uiState.value.isTestingHeartbeat) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                heartbeatTestResult = "正在测试心跳接口...",
                isTestingHeartbeat = true,
            )
            try {
                val result = deviceUploadRepository.refresh(DeviceRefreshData())
                result.fold(
                    onSuccess = { response ->
                        _uiState.value = _uiState.value.copy(
                            heartbeatTestResult = buildString {
                                append("✅ 心跳成功")
                                append("  code=${response.code}")
                                response.data?.device_id?.let { append("\n设备：$it") }
                                response.data?.status?.let { append("  状态：$it") }
                                response.data?.last_heartbeat?.let { append("\n平台时间：$it") }
                            },
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            heartbeatTestResult = "❌ 心跳失败：${formatApiError(error)}",
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    heartbeatTestResult = "❌ 心跳异常：${formatApiError(e)}",
                )
            } finally {
                _uiState.value = _uiState.value.copy(isTestingHeartbeat = false)
            }
        }
    }

    private fun observeModbusStatus() {
        viewModelScope.launch {
            hardwareManager.modbusConnectionState.collect { state ->
                val status = when (state) {
                    is com.foodfridge.data.hardware.ModbusTemperatureReader.ConnectionState.Disconnected -> "未连接"
                    is com.foodfridge.data.hardware.ModbusTemperatureReader.ConnectionState.Connecting -> "正在打开串口"
                    is com.foodfridge.data.hardware.ModbusTemperatureReader.ConnectionState.WaitingForResponse -> "串口已打开，等待探头响应"
                    is com.foodfridge.data.hardware.ModbusTemperatureReader.ConnectionState.Validating -> "已收到温度，正在进行第二帧确认"
                    is com.foodfridge.data.hardware.ModbusTemperatureReader.ConnectionState.Connected -> "已连接"
                    is com.foodfridge.data.hardware.ModbusTemperatureReader.ConnectionState.Error ->
                        "错误：${state.message}"
                }
                _uiState.value = _uiState.value.copy(modbusConnectionStatus = status)
            }
        }
        viewModelScope.launch {
            hardwareManager.modbusReadings.collect { reading ->
                _uiState.value = _uiState.value.copy(
                    modbusCurrentTemperature = reading?.celsius,
                )
            }
        }
    }

    fun saveModbusConfig(
        devicePath: String,
        baudRate: Int,
        parity: Int,
        stopBits: Int,
        slaveAddress: Int,
        functionCode: Int,
        registerAddress: Int,
        registerCount: Int,
        temperatureRegisterOffset: Int,
        valueType: ModbusValueType,
        byteOrder: ModbusByteOrder,
        wordOrder: ModbusWordOrder,
        valueMode: ModbusTemperatureValueMode,
        temperatureScale: Float,
        calibrationOffset: Float,
        enabled: Boolean,
    ) {
        val normalizedPath = devicePath.trim()
        val validationError = validateModbusConfiguration(
            devicePath = normalizedPath,
            baudRate = baudRate,
            parity = parity,
            stopBits = stopBits,
            slaveAddress = slaveAddress,
            functionCode = functionCode,
            registerAddress = registerAddress,
            registerCount = registerCount,
            temperatureRegisterOffset = temperatureRegisterOffset,
            valueType = valueType,
            temperatureScale = temperatureScale,
            calibrationOffset = calibrationOffset,
            requireDevicePath = enabled,
        )
        if (validationError != null) {
            _uiState.value = _uiState.value.copy(errorMessage = validationError)
            return
        }

        viewModelScope.launch {
            try {
                userPreferencesRepository.saveModbusConfig(
                    devicePath = normalizedPath.ifBlank { null },
                    baudRate = baudRate,
                    parity = parity,
                    stopBits = stopBits,
                    slaveAddress = slaveAddress,
                    functionCode = functionCode,
                    registerAddress = registerAddress,
                    registerCount = registerCount,
                    temperatureRegisterOffset = temperatureRegisterOffset,
                    valueType = valueType,
                    byteOrder = byteOrder,
                    wordOrder = wordOrder,
                    valueMode = valueMode,
                    temperatureScale = temperatureScale,
                    calibrationOffset = calibrationOffset,
                    enabled = enabled,
                )
                _uiState.value = _uiState.value.copy(
                    modbusDevicePath = normalizedPath,
                    modbusBaudRate = baudRate,
                    modbusParity = parity,
                    modbusStopBits = stopBits,
                    modbusSlaveAddress = slaveAddress,
                    modbusFunctionCode = functionCode,
                    modbusRegisterAddress = registerAddress,
                    modbusRegisterCount = registerCount,
                    modbusTemperatureRegisterOffset = temperatureRegisterOffset,
                    modbusValueType = valueType,
                    modbusByteOrder = byteOrder,
                    modbusWordOrder = wordOrder,
                    modbusValueMode = valueMode,
                    modbusTemperatureScale = temperatureScale,
                    modbusCalibrationOffset = calibrationOffset,
                    modbusEnabled = enabled,
                    modbusConfigSaved = true,
                    errorMessage = null,
                )

                Timber.i("Modbus 配置已保存，温度监控器将自动应用新配置")

                // 3 秒后清除保存成功标志
                kotlinx.coroutines.delay(3000)
                _uiState.value = _uiState.value.copy(modbusConfigSaved = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "保存 Modbus 配置失败: ${e.message}",
                )
            }
        }
    }

    // ── 日志导出 ──────────────────────────────────────────────

    /**
     * 把最近几天的日志打包成 zip，保存到外部 Downloads 目录，并尝试系统分享。
     */
    fun exportLogs() {
        if (_uiState.value.isExportingLogs) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                exportLogResult = "正在打包日志...",
                isExportingLogs = true,
            )
            try {
                val exportedFiles = withContext(Dispatchers.IO) {
                    FileLoggingTree.flushPendingWrites()
                    val logFiles = FileLoggingTree.listLogFiles(appContext)
                        .filter { it.exists() && it.length() > 0 }
                    if (logFiles.isEmpty()) return@withContext null

                    val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                    val zipFile = File(exportDir, "foodfridge-logs-${System.currentTimeMillis()}.zip")

                    ZipOutputStream(zipFile.outputStream().buffered()).use { zipOut ->
                        logFiles.forEach { logFile ->
                            zipOut.putNextEntry(ZipEntry(logFile.name))
                            logFile.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }

                    val publicZipFile = File(appContext.getExternalFilesDir(null), zipFile.name)
                    zipFile.inputStream().use { input ->
                        publicZipFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    zipFile to publicZipFile
                }
                if (exportedFiles == null) {
                    _uiState.value = _uiState.value.copy(exportLogResult = "⚠️ 没有可导出的日志")
                    return@launch
                }
                val (zipFile, publicZipFile) = exportedFiles

                // 尝试系统分享（如果设备有安装邮件/蓝牙等应用）
                runCatching {
                    val uri = FileProvider.getUriForFile(appContext, LOG_EXPORT_AUTHORITY, zipFile)
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "FoodFridge 日志")
                        putExtra(android.content.Intent.EXTRA_TEXT, "日志文件附后")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(android.content.Intent.createChooser(shareIntent, "分享日志").apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }

                _uiState.value = _uiState.value.copy(
                    exportLogResult = "✅ 日志已导出\n内部: ${zipFile.absolutePath}\n" +
                        "外部应用目录: ${publicZipFile.absolutePath}"
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to export logs")
                _uiState.value = _uiState.value.copy(exportLogResult = "❌ 导出失败: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isExportingLogs = false)
            }
        }
    }

    private fun formatApiError(error: Throwable): String {
        val message = error.message ?: "未知错误"
        return when {
            message.contains("404") -> "404 接口不存在"
            message.contains("401") -> "401 认证失败"
            message.contains("403") -> "403 禁止访问"
            message.contains("422") -> "422 参数校验失败"
            message.contains("500") -> "500 服务器错误"
            message.contains("timeout", ignoreCase = true) -> "连接超时"
            message.contains("connect", ignoreCase = true) -> "连接失败"
            message.contains("Unable to resolve host", ignoreCase = true) -> "DNS 解析失败"
            else -> message.take(200)
        }
    }
}

internal fun validateModbusConfiguration(
    devicePath: String,
    baudRate: Int,
    parity: Int = 0,
    stopBits: Int = 1,
    slaveAddress: Int,
    functionCode: Int,
    registerAddress: Int,
    registerCount: Int,
    temperatureRegisterOffset: Int,
    valueType: ModbusValueType = ModbusValueType.INT16,
    temperatureScale: Float,
    calibrationOffset: Float,
    requireDevicePath: Boolean,
): String? = when {
    requireDevicePath && devicePath.isBlank() -> "启用 Modbus 时必须填写串口路径"
    baudRate <= 0 -> "波特率必须大于 0"
    parity !in 0..2 -> "校验位只能是 0（无）、1（奇）或 2（偶）"
    stopBits != 1 && stopBits != 2 -> "停止位只能是 1 或 2"
    slaveAddress !in 1..0xFF -> "从机地址必须在 1 到 255 之间"
    functionCode != 0x03 && functionCode != 0x04 -> "功能码只能是 3 或 4"
    registerAddress !in 0..0xFFFF -> "起始寄存器必须在 0 到 65535 之间"
    registerCount !in 1..125 -> "寄存器数量必须在 1 到 125 之间"
    registerAddress.toLong() + registerCount > 0x10000L ->
        "寄存器读取范围不能超过 0xFFFF"
    temperatureRegisterOffset < 0 ||
        temperatureRegisterOffset.toLong() + valueType.registerWidth > registerCount.toLong() ->
        "温度字段偏移和数据宽度必须位于读取寄存器范围内"
    !temperatureScale.isFinite() || temperatureScale <= 0f || temperatureScale > 100f ->
        "温度倍率必须大于 0 且不超过 100"
    !calibrationOffset.isFinite() || calibrationOffset !in -20f..20f ->
        "校准偏移必须在 -20°C 到 20°C 之间"
    else -> null
}
