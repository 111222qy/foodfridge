package com.foodfridge.ui.table

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.domain.repository.FoodSampleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SampleTableUiState())
    val uiState: StateFlow<SampleTableUiState> = _uiState.asStateFlow()

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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStart = calendar.timeInMillis
                // dayOffset: 0=第一天(baseDate-2天), 1=第二天(baseDate-1天), 2=第三天(baseDate当天)
                val actualOffset = _uiState.value.dayOffset - 2
                val dayStart = todayStart + actualOffset * 24L * 60 * 60 * 1000
                val dayEnd = dayStart + 24 * 60 * 60 * 1000

                foodSampleRepository.getSamplesByMealAndDate(
                    _uiState.value.mealType, dayStart, dayEnd
                ).collect { samples ->
                    _uiState.value = _uiState.value.copy(
                        samples = samples,
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
                val now = System.currentTimeMillis()
                val expireTime = now + 48 * 60 * 60 * 1000

                foodSampleRepository.insertSample(
                    FoodSample(
                        id = 0,
                        operatorId = 1, // 使用当前认证用户ID
                        operatorName = "当前用户", // 使用当前认证用户名
                        foodName = state.foodName,
                        weightGrams = weight,
                        mealType = MealType.valueOf(state.mealType),
                        barcode = state.barcode,
                        status = SampleStatus.STORING,
                        storeTime = now,
                        expireTime = expireTime,
                        createdAt = now,
                    )
                )
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
}
