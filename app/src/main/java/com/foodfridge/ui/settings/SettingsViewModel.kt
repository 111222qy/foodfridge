package com.foodfridge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.domain.model.User
import com.foodfridge.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val errorMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadDualFaceAuthConfig()
    }

    private fun loadDualFaceAuthConfig() {
        viewModelScope.launch {
            val enabled = userPreferencesRepository.dualFaceAuthEnabled.first()
            _uiState.value = _uiState.value.copy(dualFaceAuthEnabled = enabled)
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
}
