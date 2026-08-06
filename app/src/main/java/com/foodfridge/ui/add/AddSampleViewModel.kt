package com.foodfridge.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.data.remote.device.dto.SamplingUploadData
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.domain.repository.DeviceUploadRepository
import com.foodfridge.domain.repository.FoodSampleRepository
import com.foodfridge.domain.scan.BarcodePayload
import com.foodfridge.utils.DeviceInfoProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class AddSampleUiState(
    val foodName: String = "",
    val weightGrams: String = "",
    val mealType: MealType = MealType.BREAKFAST,
    val barcode: String = "",
    val isScanning: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class AddSampleViewModel @Inject constructor(
    private val foodSampleRepository: FoodSampleRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val deviceUploadRepository: DeviceUploadRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddSampleUiState())
    val uiState: StateFlow<AddSampleUiState> = _uiState.asStateFlow()

    fun onFoodNameChange(value: String) {
        _uiState.value = _uiState.value.copy(foodName = value, errorMessage = null)
    }

    fun onWeightChange(value: String) {
        // 只允许数字和小数点
        val filtered = value.filter { it.isDigit() || it == '.' }
        _uiState.value = _uiState.value.copy(weightGrams = filtered, errorMessage = null)
    }

    fun onMealTypeChange(mealType: MealType) {
        _uiState.value = _uiState.value.copy(mealType = mealType)
    }

    /**
     * 从串口扫码器返回的真实条码结果回填表单。
     */
    fun onScanResult(rawBarcode: String, payload: BarcodePayload) {
        val matchedMealType = MealType.entries.firstOrNull {
            it.displayName == payload.mealType
        } ?: MealType.BREAKFAST

        _uiState.value = _uiState.value.copy(
            foodName = payload.dishName,
            weightGrams = String.format(java.util.Locale.US, "%.1f", payload.weightGrams),
            mealType = matchedMealType,
            barcode = rawBarcode,
            errorMessage = null,
        )
    }

    fun saveSample(onSuccess: () -> Unit) {
        val state = _uiState.value

        if (state.foodName.isBlank()) {
            _uiState.value = state.copy(errorMessage = "请输入食品名称")
            return
        }

        val weight = state.weightGrams.toFloatOrNull()
        if (weight == null || weight <= 0) {
            _uiState.value = state.copy(errorMessage = "请输入有效的留样重量")
            return
        }

        _uiState.value = state.copy(isSaving = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val userId = userPreferencesRepository.currentUserId.first()?.toIntOrNull() ?: 0
                val userName = userPreferencesRepository.currentUserName.first() ?: "未知用户"

                val now = System.currentTimeMillis()
                val expireTime = now + 48 * 60 * 60 * 1000 // 48小时后

                val sample = FoodSample(
                    id = 0,
                    operatorId = userId,
                    operatorName = userName,
                    foodName = state.foodName,
                    weightGrams = weight,
                    mealType = state.mealType,
                    barcode = state.barcode.ifBlank { "MANUAL_${now}" },
                    status = SampleStatus.STORING,
                    storeTime = now,
                    expireTime = expireTime,
                    createdAt = now,
                )

                foodSampleRepository.insertSample(sample)
                uploadSampling(sample)

                _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "保存失败: ${e.message}"
                )
            }
        }
    }

    private fun uploadSampling(sample: FoodSample) {
        viewModelScope.launch {
            try {
                val deviceId = DeviceInfoProvider.getDeviceNumber(appContext)
                val result = deviceUploadRepository.uploadSampling(
                    SamplingUploadData(
                        device_id = deviceId,
                        timestamp = sample.storeTime,
                        dish_name = sample.foodName,
                        meal_type = sample.mealType.name,
                        operator_name = sample.operatorName,
                        weight = sample.weightGrams,
                    )
                )
                result.fold(
                    onSuccess = { response ->
                        Timber.i("留样上报成功: code=${response.code}, message=${response.message}")
                    },
                    onFailure = { error ->
                        Timber.e(error, "留样上报失败")
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "留样上报异常")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
