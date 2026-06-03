package com.foodfridge.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.repository.FoodSampleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class SampleDetailUiState(
    val mealType: MealType = MealType.BREAKFAST,
    val dayOffset: Int = 0,
    val samples: List<FoodSample> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class SampleDetailViewModel @Inject constructor(
    private val foodSampleRepository: FoodSampleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SampleDetailUiState())
    val uiState: StateFlow<SampleDetailUiState> = _uiState.asStateFlow()

    fun loadSamples(mealTypeStr: String, dayOffset: Int) {
        val mealType = MealType.valueOf(mealTypeStr)
        _uiState.value = _uiState.value.copy(
            mealType = mealType,
            dayOffset = dayOffset,
            isLoading = true
        )

        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val targetDayStart = calendar.timeInMillis + dayOffset * 24 * 60 * 60 * 1000
            val targetDayEnd = targetDayStart + 24 * 60 * 60 * 1000

            foodSampleRepository.getSamplesByMealAndDate(mealType.name, targetDayStart, targetDayEnd)
                .collect { samples ->
                    _uiState.value = _uiState.value.copy(
                        samples = samples,
                        isLoading = false,
                    )
                }
        }
    }
}
