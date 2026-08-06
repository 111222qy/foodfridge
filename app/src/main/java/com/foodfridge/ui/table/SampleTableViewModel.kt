package com.foodfridge.ui.table

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.foodfridge.data.remote.device.dto.SamplingUploadData
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.domain.repository.DeviceUploadRepository
import com.foodfridge.domain.repository.FoodSampleRepository
import com.foodfridge.ui.home.DateRollingLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import com.foodfridge.utils.DeviceInfoProvider
import com.foodfridge.data.local.UserPreferencesRepository
import javax.inject.Inject

data class SampleTableUiState(
    val mealType: String = "BREAKFAST",
    val dayOffset: Int = 0,
    val barcode: String = "",
    val foodName: String = "",
    val weightGrams: String = "",
    val samples: List<FoodSample> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class SampleTableViewModel @Inject constructor(
    private val foodSampleRepository: FoodSampleRepository,
    private val deviceUploadRepository: DeviceUploadRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SampleTableUiState())
    val uiState: StateFlow<SampleTableUiState> = _uiState.asStateFlow()

    private var loadSamplesJob: kotlinx.coroutines.Job? = null

    fun init(
        mealType: String,
        dayOffset: Int,
        barcode: String,
        foodName: String,
        weightGrams: Float = 0f,
    ) {
        val weightString = if (weightGrams > 0) {
            String.format(java.util.Locale.US, "%.1f", weightGrams)
        } else {
            ""
        }
        _uiState.value = _uiState.value.copy(
            mealType = mealType,
            dayOffset = dayOffset,
            barcode = barcode,
            foodName = foodName,
            weightGrams = weightString,
        )
        loadSamples()
    }

    private fun loadSamples() {
        // 取消之前的 collector，防止多个 collector 同时运行
        loadSamplesJob?.cancel()
        loadSamplesJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // 使用与首页一致的基准日期：最早活跃样品的日期（无活跃样品时为今天）
                val activeSamples = foodSampleRepository.getActiveSamples()
                val firstColumnDate = DateRollingLogic.firstColumnDate(
                    activeSampleStoreTimes = activeSamples.map { it.storeTime },
                    todayMillis = System.currentTimeMillis(),
                )

                // dayOffset: 0=第一列日期, 1=第一列+1, 2=第一列+2
                val dayStart = firstColumnDate + _uiState.value.dayOffset * 24L * 60 * 60 * 1000
                val dayEnd = dayStart + 24 * 60 * 60 * 1000

                foodSampleRepository.getSamplesByMealAndDate(
                    _uiState.value.mealType, dayStart, dayEnd
                ).collect { samples ->
                    // 同一餐品只保留最新记录，不同餐品正常叠加
                    val deduplicated = samples
                        .groupBy { it.foodName }
                        .map { (_, sameNameSamples) ->
                            sameNameSamples.maxByOrNull { it.createdAt }!!
                        }
                        .sortedByDescending { it.createdAt }

                    _uiState.value = _uiState.value.copy(
                        samples = deduplicated,
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

    fun onWeightChange(value: String) {
        _uiState.value = _uiState.value.copy(weightGrams = value)
    }

    fun saveSample(onComplete: () -> Unit) {
        val state = _uiState.value
        val weight = state.weightGrams.toFloatOrNull()

        if (weight == null || weight <= 0) {
            _uiState.value = state.copy(errorMessage = "请输入有效的留样重量")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, errorMessage = null)
            try {
                val userId = userPreferencesRepository.currentUserId.first()?.toIntOrNull() ?: 0
                val userName = userPreferencesRepository.currentUserName.first() ?: "未知用户"
                val now = System.currentTimeMillis()

                val activeSamples = foodSampleRepository.getActiveSamples()
                val firstColumnDate = DateRollingLogic.firstColumnDate(
                    activeSampleStoreTimes = activeSamples.map { it.storeTime },
                    todayMillis = now,
                )
                if (!DateRollingLogic.isTodayColumn(firstColumnDate, state.dayOffset, now)) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        errorMessage = "只能保存当天的留样",
                    )
                    return@launch
                }

                val storeTime = now
                val expireTime = storeTime + 48 * 60 * 60 * 1000

                val sample = FoodSample(
                    id = 0,
                    operatorId = userId,
                    operatorName = userName,
                    foodName = state.foodName,
                    weightGrams = weight,
                    mealType = MealType.valueOf(state.mealType),
                    barcode = state.barcode,
                    status = SampleStatus.STORING,
                    storeTime = storeTime,
                    expireTime = expireTime,
                    createdAt = now,
                )

                foodSampleRepository.insertSample(sample)
                uploadSampling(sample)

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true,
                    weightGrams = "",
                )
                Timber.i("留样保存成功: ${state.foodName}")
                onComplete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "保存失败: ${e.message}",
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
}
