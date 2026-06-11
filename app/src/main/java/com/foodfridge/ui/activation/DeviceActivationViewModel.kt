package com.foodfridge.ui.activation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.BuildConfig
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.domain.repository.DeviceRepository
import com.foodfridge.utils.DeviceInfoProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceActivationUiState(
    val isLoading: Boolean = false,
    val isActivated: Boolean = false,
    val message: String = "准备激活设备...",
    val errorMessage: String? = null,
    val deviceInfo: DeviceInfoDisplay? = null,
)

data class DeviceInfoDisplay(
    val deviceNumber: String,
    val deviceMac: String,
    val activationCode: String,
)

@HiltViewModel
class DeviceActivationViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val deviceRepository: DeviceRepository,
    private val userPrefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceActivationUiState())
    val uiState: StateFlow<DeviceActivationUiState> = _uiState.asStateFlow()

    init {
        loadDeviceInfo()
    }

    private fun loadDeviceInfo() {
        val deviceNumber = DeviceInfoProvider.getDeviceNumber(appContext)
        val deviceMac = DeviceInfoProvider.getMacAddress(appContext)
        val activationCode = DeviceInfoProvider.getActivationCode()

        _uiState.update {
            it.copy(
                deviceInfo = DeviceInfoDisplay(
                    deviceNumber = deviceNumber,
                    deviceMac = deviceMac,
                    activationCode = activationCode,
                ),
                message = "设备信息已获取，点击激活按钮开始激活",
            )
        }
    }

    fun activateDevice() {
        val deviceInfo = _uiState.value.deviceInfo ?: return

        if (deviceInfo.activationCode == "YOUR_ACTIVATION_CODE") {
            _uiState.update {
                it.copy(
                    errorMessage = "激活码未配置，请在build.gradle.kts中设置ACTIVATION_CODE",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                message = "正在激活设备，请稍候...",
            )
        }

        viewModelScope.launch {
            try {
                val result = deviceRepository.activateDevice(
                    apisix = BuildConfig.APISIX_HEADER,
                    token = "",
                    smKeys = "874512sdfwesa369",
                    deviceNumber = deviceInfo.deviceNumber,
                    deviceMac = deviceInfo.deviceMac,
                    activationCode = deviceInfo.activationCode,
                )

                result.fold(
                    onSuccess = { response ->
                        if (response.code == 200) {
                            val data = response.data
                            if (data != null) {
                                // 保存激活信息到本地
                                userPrefs.saveUserSession(
                                    userId = data.d_id.toString(),
                                    userName = data.device_name,
                                )

                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        isActivated = true,
                                        message = "设备激活成功！设备ID: ${data.d_id}, 组织ID: ${data.o_id}",
                                        errorMessage = null,
                                    )
                                }
                                Log.i("DeviceActivation", "Activation successful: d_id=${data.d_id}, o_id=${data.o_id}")
                            } else {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        message = "激活成功但返回数据为空",
                                        errorMessage = null,
                                    )
                                }
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    message = "激活失败",
                                    errorMessage = "错误码: ${response.code}, ${response.msg}",
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                message = "激活请求失败",
                                errorMessage = error.message ?: "未知错误",
                            )
                        }
                        Log.e("DeviceActivation", "Activation failed", error)
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "激活过程发生异常",
                        errorMessage = e.message ?: "未知异常",
                    )
                }
                Log.e("DeviceActivation", "Activation exception", e)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
