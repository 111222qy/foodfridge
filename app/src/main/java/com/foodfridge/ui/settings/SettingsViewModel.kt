package com.foodfridge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.data.hardware.HardwareManager
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.interceptor.DynamicBaseUrlInterceptor
import com.foodfridge.domain.model.User
import com.foodfridge.domain.repository.DeviceUploadRepository
import com.foodfridge.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val users: List<User> = emptyList(),
    val isLoading: Boolean = false,
    val dualFaceAuthEnabled: Boolean = false,
    val currentUserName: String = "admin",
    val adminPassword: String? = null,
    val thermalOverridePath: String = "",
    val thermalScale: Int = -1,
    val thermalDiagResult: String? = null,
    val apiBaseUrl: String = "",
    val apiDebugResult: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val userRepository: UserRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val hardwareManager: HardwareManager,
    private val dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
    private val deviceUploadRepository: DeviceUploadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadDualFaceAuthConfig()
        loadAdminPassword()
        loadThermalOverride()
        loadApiBaseUrl()
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
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "批量删除失败: ${e.message}",
                )
            }
        }
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

    private fun loadThermalOverride() {
        viewModelScope.launch {
            try {
                val path = userPreferencesRepository.thermalZoneOverride.first() ?: ""
                val scale = userPreferencesRepository.thermalZoneScale.first()
                _uiState.value = _uiState.value.copy(
                    thermalOverridePath = path,
                    thermalScale = scale,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "加载温度配置失败: ${e.message}",
                )
            }
        }
    }

    fun saveThermalOverride(path: String, scale: Int) {
        viewModelScope.launch {
            try {
                userPreferencesRepository.saveThermalZoneOverride(
                    path = path.trim().takeIf { it.isNotBlank() },
                    scale = scale,
                )
                _uiState.value = _uiState.value.copy(
                    thermalOverridePath = path.trim(),
                    thermalScale = scale,
                    thermalDiagResult = "已保存: $path (scale=$scale)",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "保存温度配置失败: ${e.message}",
                )
            }
        }
    }

    fun runThermalDiagnostics() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(thermalDiagResult = "正在诊断...")
            try {
                val result = hardwareManager.dumpThermalDiagnostics()
                _uiState.value = _uiState.value.copy(thermalDiagResult = result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    thermalDiagResult = "诊断失败: ${e.message}",
                )
            }
        }
    }

    /**
     * 运行 MCU 温度探测：向 MCU 发送各种可能的温度命令，记录响应。
     * 结果在 logcat 中搜索 "MCU_PROBE"。
     */
    fun runMcuProbe() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(thermalDiagResult = "MCU 探测中，请查看 logcat (tag=HardwareManager, keyword=MCU_PROBE)...")
            try {
                hardwareManager.probeMcuTemperature()
                val adcResult = hardwareManager.probeAdcChannels()
                _uiState.value = _uiState.value.copy(
                    thermalDiagResult = "MCU 探测已启动，请查看 logcat。\n\n$adcResult",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    thermalDiagResult = "MCU 探测失败: ${e.message}",
                )
            }
        }
    }

    // ── API 地址配置 ──────────────────────────────────────────

    private fun loadApiBaseUrl() {
        viewModelScope.launch {
            try {
                val url = userPreferencesRepository.apiBaseUrl.first() ?: ""
                _uiState.value = _uiState.value.copy(apiBaseUrl = url)
                // 启动时同步到拦截器
                if (url.isNotBlank()) {
                    dynamicBaseUrlInterceptor.setBaseUrl(url)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "加载 API 地址失败: ${e.message}",
                )
            }
        }
    }

    fun saveApiBaseUrl(url: String) {
        viewModelScope.launch {
            try {
                userPreferencesRepository.saveApiBaseUrl(url)
                dynamicBaseUrlInterceptor.setBaseUrl(url.ifBlank { null })
                _uiState.value = _uiState.value.copy(apiBaseUrl = url.trim())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "保存 API 地址失败: ${e.message}",
                )
            }
        }
    }

    // ── 调试接口 ──────────────────────────────────────────────

    /**
     * 调用心跳保活接口，返回响应信息。
     */
    fun debugApiConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(apiDebugResult = "心跳测试中...")
            try {
                val result = deviceUploadRepository.refresh(DeviceRefreshData())
                result.fold(
                    onSuccess = { response ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "✅ 心跳成功  code=${response.code}  message=${response.message}\ndata: device_id=${response.data?.device_id}, status=${response.data?.status}"
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "❌ 心跳失败  ${parseErrorMessage(error)}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    apiDebugResult = "❌ 心跳异常  ${e.message}"
                )
            }
        }
    }

    /**
     * 测试温度上报接口。
     */
    fun debugTemperatureUpload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(apiDebugResult = "温度上报测试中...")
            try {
                val deviceId = com.foodfridge.utils.DeviceInfoProvider.getDeviceNumber(
                    appContext as android.content.Context
                )
                val data = com.foodfridge.data.remote.device.dto.TemperatureUploadData(
                    device_id = deviceId,
                    timestamp = System.currentTimeMillis(),
                    temperature = 6.5f,
                )
                val result = deviceUploadRepository.uploadTemperature(data)
                result.fold(
                    onSuccess = { response ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "✅ 温度上报成功  code=${response.code}  message=${response.message}\nrecordId=${response.data?.recordId}"
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "❌ 温度上报失败  ${parseErrorMessage(error)}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    apiDebugResult = "❌ 温度上报异常  ${e.message}"
                )
            }
        }
    }

    /**
     * 测试开关门记录上报接口。
     */
    fun debugDoorRecordUpload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(apiDebugResult = "开关门上报测试中...")
            try {
                val deviceId = com.foodfridge.utils.DeviceInfoProvider.getDeviceNumber(
                    appContext as android.content.Context
                )
                val now = System.currentTimeMillis()
                val data = com.foodfridge.data.remote.device.dto.DoorRecordData(
                    device_id = deviceId,
                    timestamp = now,
                    operator_name = "测试员",
                    open_timestamp = now - 10_000,
                    close_timestamp = now,
                )
                val result = deviceUploadRepository.uploadDoorRecord(data)
                result.fold(
                    onSuccess = { response ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "✅ 开关门上报成功  code=${response.code}  message=${response.message}\nrecordId=${response.data?.recordId}"
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "❌ 开关门上报失败  ${parseErrorMessage(error)}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    apiDebugResult = "❌ 开关门上报异常  ${e.message}"
                )
            }
        }
    }

    /**
     * 测试留样上报接口。
     */
    fun debugSamplingUpload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(apiDebugResult = "留样上报测试中...")
            try {
                val deviceId = com.foodfridge.utils.DeviceInfoProvider.getDeviceNumber(
                    appContext as android.content.Context
                )
                val data = com.foodfridge.data.remote.device.dto.SamplingUploadData(
                    device_id = deviceId,
                    timestamp = System.currentTimeMillis(),
                    dish_name = "测试菜品",
                    stall_name = "测试档口",
                    operator_name = "测试员",
                    weight = 125.5f,
                )
                val result = deviceUploadRepository.uploadSampling(data)
                result.fold(
                    onSuccess = { response ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "✅ 留样上报成功  code=${response.code}  message=${response.message}\nrecordId=${response.data?.recordId}"
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            apiDebugResult = "❌ 留样上报失败  ${parseErrorMessage(error)}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    apiDebugResult = "❌ 留样上报异常  ${e.message}"
                )
            }
        }
    }

    private fun parseErrorMessage(error: Throwable): String {
        val msg = error.message ?: "未知错误"
        return when {
            msg.contains("404") -> "404 接口不存在"
            msg.contains("401") -> "401 认证失败"
            msg.contains("403") -> "403 禁止访问"
            msg.contains("422") -> "422 参数校验失败"
            msg.contains("500") -> "500 服务器错误"
            msg.contains("timeout", true) -> "连接超时"
            msg.contains("connect", true) -> "连接失败"
            msg.contains("Unable to resolve host", true) -> "DNS 解析失败"
            else -> msg.take(200)
        }
    }
}
