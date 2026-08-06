package com.foodfridge.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.domain.repository.FoodSampleRepository
import com.foodfridge.domain.repository.UserRepository
import com.foodfridge.ui.home.DateRollingLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class SampleDetailUiState(
    val mealType: MealType = MealType.BREAKFAST,
    val dayOffset: Int = 0,
    val samples: List<FoodSample> = emptyList(),
    val selectedSampleIds: Set<Int> = emptySet(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val showAdminAuthDialog: Boolean = false,
    val adminRequiredSampleCount: Int = 0,
    val isDisposing: Boolean = false,
    val disposeSuccess: Boolean = false,
    val lastDisposeOperatorName: String? = null,
    val errorMessage: String? = null,
    val baseDate: Long = 0L,
) {
    /** 按搜索关键字过滤后的样品列表（菜品名或操作员匹配） */
    val filteredSamples: List<FoodSample>
        get() = if (searchQuery.isBlank()) {
            samples
        } else {
            val q = searchQuery.trim()
            samples.filter {
                it.foodName.contains(q, ignoreCase = true) ||
                    it.operatorName.contains(q, ignoreCase = true) ||
                    it.disposedByName?.contains(q, ignoreCase = true) == true
            }
        }
}

@HiltViewModel
class SampleDetailViewModel @Inject constructor(
    private val foodSampleRepository: FoodSampleRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SampleDetailUiState())
    val uiState: StateFlow<SampleDetailUiState> = _uiState.asStateFlow()

    fun loadSamples(mealTypeStr: String, dayOffset: Int) {
        val mealType = MealType.valueOf(mealTypeStr)
        _uiState.value = _uiState.value.copy(
            mealType = mealType,
            dayOffset = dayOffset,
            isLoading = true,
            disposeSuccess = false,
            lastDisposeOperatorName = null,
            errorMessage = null,
            selectedSampleIds = emptySet(),
        )

        viewModelScope.launch {
            // 使用与首页一致的基准日期：最早活跃样品的日期（无活跃样品时为今天）
            val activeSamples = foodSampleRepository.getActiveSamples()
            val firstColumnDate = DateRollingLogic.firstColumnDate(
                activeSampleStoreTimes = activeSamples.map { it.storeTime },
                todayMillis = System.currentTimeMillis(),
            )

            val targetDayStart = firstColumnDate + dayOffset * 24L * 60 * 60 * 1000
            val targetDayEnd = targetDayStart + 24 * 60 * 60 * 1000

            _uiState.value = _uiState.value.copy(baseDate = firstColumnDate)

            foodSampleRepository.getSamplesByMealAndDate(mealType.name, targetDayStart, targetDayEnd)
                .collect { samples ->
                    _uiState.value = _uiState.value.copy(
                        samples = samples.sortedByDescending { it.createdAt },
                        isLoading = false,
                    )
                }
        }
    }

    fun toggleSampleSelection(sampleId: Int) {
        val current = _uiState.value.selectedSampleIds
        val next = if (current.contains(sampleId)) current - sampleId else current + sampleId
        _uiState.value = _uiState.value.copy(selectedSampleIds = next)
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun toggleSelectAll() {
        val state = _uiState.value
        val selectableIds = state.filteredSamples
            .filter { it.status == SampleStatus.STORING || it.status == SampleStatus.WAITING_DISPOSE }
            .map { it.id }
            .toSet()
        val next = if (state.selectedSampleIds.containsAll(selectableIds) && selectableIds.isNotEmpty()) {
            emptySet()
        } else {
            selectableIds
        }
        _uiState.value = state.copy(selectedSampleIds = next)
    }

    fun requestDisposeSelected() {
        val state = _uiState.value
        if (state.isDisposing) return
        if (state.selectedSampleIds.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "请先勾选要消样的记录")
            return
        }

        val selectedSamples = selectedSamples(state)
        val now = System.currentTimeMillis()
        if (SampleDisposalPolicy.requiresAdmin(selectedSamples, now)) {
            val earlyCount = selectedSamples.count {
                it.status == SampleStatus.STORING &&
                    now < it.storeTime + SampleDisposalPolicy.RETENTION_DURATION_MS
            }
            _uiState.value = state.copy(
                showAdminAuthDialog = true,
                adminRequiredSampleCount = earlyCount,
                errorMessage = null,
            )
            return
        }

        _uiState.value = state.copy(isDisposing = true, errorMessage = null)
        viewModelScope.launch {
            val operator = resolveCurrentOperator()
            if (operator == null) {
                _uiState.value = _uiState.value.copy(
                    isDisposing = false,
                    errorMessage = "无法获取当前操作人，请重新进行人员认证",
                )
                return@launch
            }
            disposeSamples(selectedSamples, operator)
        }
    }

    fun dismissAdminAuthDialog() {
        if (_uiState.value.isDisposing) return
        _uiState.value = _uiState.value.copy(
            showAdminAuthDialog = false,
            adminRequiredSampleCount = 0,
            errorMessage = null,
        )
    }

    fun confirmDispose(adminUsername: String, adminPassword: String) {
        val state = _uiState.value
        if (state.isDisposing) return
        val selectedIds = state.selectedSampleIds
        if (selectedIds.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                showAdminAuthDialog = false,
                errorMessage = "请先勾选要消样的记录",
            )
            return
        }

        _uiState.value = state.copy(isDisposing = true, errorMessage = null)
        viewModelScope.launch {
            // 从 DataStore 读取真实的管理员账号密码，未设置时回退默认 admin/admin
            val savedPassword = userPreferencesRepository.adminPassword.first() ?: "admin"
            val normalizedUsername = adminUsername.trim()
            if (normalizedUsername != "admin" || adminPassword != savedPassword) {
                _uiState.value = _uiState.value.copy(
                    isDisposing = false,
                    errorMessage = "账号或密码错误",
                )
                return@launch
            }

            val adminUser = userRepository.getUserByEmployeeId(normalizedUsername)
            if (adminUser != null && adminUser.role != "ADMIN") {
                _uiState.value = _uiState.value.copy(
                    isDisposing = false,
                    errorMessage = "该账号不具备管理员权限",
                )
                return@launch
            }

            val operator = DisposalOperator(
                userId = adminUser?.id,
                employeeId = adminUser?.employeeId ?: normalizedUsername,
                name = adminUser?.fullName ?: "管理员",
                role = "ADMIN",
            )
            val toDispose = _uiState.value.samples.filter { it.id in selectedIds }
            disposeSamples(toDispose, operator)
        }
    }

    fun clearDisposeSuccess() {
        _uiState.value = _uiState.value.copy(
            disposeSuccess = false,
            lastDisposeOperatorName = null,
        )
    }

    private fun selectedSamples(state: SampleDetailUiState): List<FoodSample> {
        return state.samples.filter { sample ->
            sample.id in state.selectedSampleIds &&
                (sample.status == SampleStatus.STORING || sample.status == SampleStatus.WAITING_DISPOSE)
        }
    }

    private suspend fun resolveCurrentOperator(): DisposalOperator? {
        val userId = userPreferencesRepository.currentUserId.first()?.toIntOrNull() ?: return null
        val user = userRepository.getUserById(userId) ?: return null
        if (!user.isActive) return null
        return DisposalOperator(
            userId = user.id,
            employeeId = user.employeeId,
            name = user.fullName,
            role = user.role,
        )
    }

    private suspend fun disposeSamples(
        samples: List<FoodSample>,
        operator: DisposalOperator,
    ) {
        if (samples.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                showAdminAuthDialog = false,
                errorMessage = "所选记录已不可消样，请刷新后重试",
            )
            return
        }

        _uiState.value = _uiState.value.copy(isDisposing = true, errorMessage = null)
        try {
            val updatedCount = foodSampleRepository.disposeSamples(
                sampleIds = samples.map { it.id },
                disposedAt = System.currentTimeMillis(),
                disposedByUserId = operator.userId,
                disposedByEmployeeId = operator.employeeId,
                disposedByName = operator.name,
                disposedByRole = operator.role,
            )
            if (updatedCount != samples.size) {
                throw IllegalStateException("仅更新 $updatedCount/${samples.size} 条记录")
            }
            _uiState.value = _uiState.value.copy(
                showAdminAuthDialog = false,
                adminRequiredSampleCount = 0,
                selectedSampleIds = emptySet(),
                isDisposing = false,
                disposeSuccess = true,
                lastDisposeOperatorName = operator.name,
                errorMessage = null,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isDisposing = false,
                errorMessage = "消样失败: ${e.message}",
            )
        }
    }

    private data class DisposalOperator(
        val userId: Int?,
        val employeeId: String?,
        val name: String,
        val role: String,
    )
}
