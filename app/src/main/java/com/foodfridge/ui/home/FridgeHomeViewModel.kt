package com.foodfridge.ui.home

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.domain.face.FaceEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.domain.repository.FoodSampleRepository
import com.foodfridge.domain.repository.TemperatureRepository
import com.foodfridge.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import java.util.Calendar
import javax.inject.Inject

data class MealCardState(
    val mealType: MealType,
    val status: SampleStatus,
    val dayOffset: Int,
    val latestSample: FoodSample? = null
)

data class AuthUser(
    val userId: String,
    val userName: String,
    val role: String,
)

data class FridgeHomeUiState(
    val temperature: Float = 4.0f,
    val isTemperatureAlarm: Boolean = false,
    val todayCards: List<MealCardState> = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, 0) },
    val yesterdayCards: List<MealCardState> = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, -1) },
    val isAuthenticated: Boolean = false,
    val authUsers: List<AuthUser> = emptyList(),
    val currentUserName: String? = null,
    val dualFaceAuthEnabled: Boolean = false,
    val authPromptMessage: String = "未认证 - 注视屏幕自动识别",
    val isLoading: Boolean = false,
    val pendingMealType: String? = null,
    val pendingDayOffset: Int = 0,
    val faceDetectionFrames: Int = 0,
    val showAuthGate: Boolean = false,
)

@HiltViewModel
class FridgeHomeViewModel @Inject constructor(
    private val foodSampleRepository: FoodSampleRepository,
    private val temperatureRepository: TemperatureRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val userRepository: UserRepository,
    private val faceEngine: FaceEngine,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FridgeHomeUiState())
    val uiState: StateFlow<FridgeHomeUiState> = _uiState.asStateFlow()

    private var temperatureJob: Job? = null
    private var expiryCheckJob: Job? = null
    private var authExpiryJob: Job? = null
    private var faceDetectionCount = 0
    private var lastFaceDetectionAt = 0L
    private val viewModelCreatedAt = System.currentTimeMillis()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            Log.i("FridgeHome", "正在初始化FaceEngine...")
            faceEngine.init(appContext)
            Log.i("FridgeHome", "FaceEngine初始化完成")
        }
        loadAuthConfig()
        loadUserAuthState()
        startTemperatureSimulation()
        startExpiryCheck()
        loadMealStates()
    }

    private fun loadAuthConfig() {
        viewModelScope.launch {
            val dualEnabled = userPreferencesRepository.dualFaceAuthEnabled.first()
            _uiState.value = _uiState.value.copy(dualFaceAuthEnabled = dualEnabled)
        }
    }

    private fun loadUserAuthState() {
        viewModelScope.launch {
            val isLoggedIn = userPreferencesRepository.isLoggedIn.first()
            val userName = userPreferencesRepository.currentUserName.first()
            val dualEnabled = userPreferencesRepository.dualFaceAuthEnabled.first()
            val currentAuthUsers = _uiState.value.authUsers

            // 构建提示消息
            val promptMsg = when {
                !isLoggedIn -> "未认证 - 注视屏幕自动识别"
                !dualEnabled -> "已认证: ${userName ?: ""}"
                currentAuthUsers.size >= 2 -> "双认证通过: ${currentAuthUsers[0].userName}(监督员) + ${currentAuthUsers[1].userName}(留样员)"
                currentAuthUsers.size == 1 -> buildSingleAuthPrompt(currentAuthUsers[0])
                else -> "未认证 - 注视屏幕自动识别"
            }

            _uiState.value = _uiState.value.copy(
                isAuthenticated = isLoggedIn && (!dualEnabled || currentAuthUsers.size >= 2),
                currentUserName = userName,
                dualFaceAuthEnabled = dualEnabled,
                authPromptMessage = promptMsg,
            )
            if (isLoggedIn) {
                startAuthExpiryTimer()
            }
        }
    }

    private fun buildSingleAuthPrompt(authUser: AuthUser): String {
        val roleDisplay = when (authUser.role) {
            "SUPERVISOR" -> "监督员"
            "SAMPLER" -> "留样员"
            else -> authUser.role
        }
        val needRole = when (authUser.role) {
            "SUPERVISOR" -> "留样员"
            "SAMPLER" -> "监督员"
            else -> "另一个角色"
        }
        return "已认证: ${authUser.userName}($roleDisplay)，请$needRole 认证"
    }

    private fun startAuthExpiryTimer() {
        authExpiryJob?.cancel()
        authExpiryJob = viewModelScope.launch {
            delay(60_000) // 认证有效期1分钟
            userPreferencesRepository.clearSession()
            _uiState.value = _uiState.value.copy(
                isAuthenticated = false,
                authUsers = emptyList(),
                currentUserName = null,
                authPromptMessage = "未认证 - 注视屏幕自动识别",
            )
            faceDetectionCount = 0
            Log.i("FridgeHome", "认证已过期，自动退出登录")
        }
    }

    /**
     * 处理新认证的用户（从人脸识别页面返回）
     */
    fun onUserAuthenticated(userId: Int) {
        viewModelScope.launch {
            val user = userRepository.getUserById(userId)
            if (user == null || !user.isActive) {
                Log.w("FridgeHome", "认证用户不存在或已停用: $userId")
                return@launch
            }

            val dualEnabled = userPreferencesRepository.dualFaceAuthEnabled.first()
            val currentUsers = _uiState.value.authUsers.toMutableList()

            // 避免重复添加同一用户
            if (currentUsers.any { it.userId == userId.toString() }) {
                Log.i("FridgeHome", "用户已认证，跳过重复: ${user.fullName}")
                return@launch
            }

            if (!dualEnabled) {
                // 单人脸模式：直接通过
                val authUser = AuthUser(userId.toString(), user.fullName, user.role)
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = true,
                    authUsers = listOf(authUser),
                    currentUserName = user.fullName,
                    authPromptMessage = "已认证: ${user.fullName}",
                )
                startAuthExpiryTimer()
                Log.i("FridgeHome", "单人脸认证通过: ${user.fullName} (${user.role})")
                return@launch
            }

            // 双人脸模式
            if (currentUsers.isEmpty()) {
                // 第一个认证
                val authUser = AuthUser(userId.toString(), user.fullName, user.role)
                currentUsers.add(authUser)
                val prompt = buildSingleAuthPrompt(authUser)
                _uiState.value = _uiState.value.copy(
                    authUsers = currentUsers,
                    currentUserName = user.fullName,
                    authPromptMessage = prompt,
                    isAuthenticated = false,
                )
                startAuthExpiryTimer()
                // 如果不是监督员/留样员，提示需要正确角色
                if (user.role != "SUPERVISOR" && user.role != "SAMPLER") {
                    _uiState.value = _uiState.value.copy(
                        authPromptMessage = "双人脸模式需要监督员+留样员，当前角色不符合"
                    )
                }
                Log.i("FridgeHome", "双人脸第一认证: ${user.fullName} (${user.role})")
            } else {
                // 第二个认证
                val firstUser = currentUsers.first()
                val isComplementary = (firstUser.role == "SUPERVISOR" && user.role == "SAMPLER") ||
                        (firstUser.role == "SAMPLER" && user.role == "SUPERVISOR")

                if (isComplementary) {
                    val authUser = AuthUser(userId.toString(), user.fullName, user.role)
                    currentUsers.add(authUser)
                    _uiState.value = _uiState.value.copy(
                        isAuthenticated = true,
                        authUsers = currentUsers,
                        authPromptMessage = "双认证通过: ${firstUser.userName}(监督员) + ${user.fullName}(留样员)",
                    )
                    startAuthExpiryTimer()
                    Log.i("FridgeHome", "双人脸认证全部通过")
                } else {
                    // 角色不互补，需要重新认证
                    val needRole = when (firstUser.role) {
                        "SUPERVISOR" -> "留样员"
                        "SAMPLER" -> "监督员"
                        else -> "另一个角色"
                    }
                    _uiState.value = _uiState.value.copy(
                        authPromptMessage = "需要$needRole 认证，请重新识别"
                    )
                    Log.i("FridgeHome", "双人脸角色不互补: first=${firstUser.role}, second=${user.role}")
                }
            }
        }
    }

    private fun loadMealStates() {
        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayStart = calendar.timeInMillis
            val todayEnd = todayStart + 24 * 60 * 60 * 1000
            val yesterdayStart = todayStart - 24 * 60 * 60 * 1000

            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, todayStart, todayEnd
                    ).collect { samples ->
                        val latest = samples.firstOrNull()
                        val status = latest?.status ?: SampleStatus.WAITING
                        updateTodayCard(mealType, status, latest)
                    }
                }
            }

            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, yesterdayStart, todayStart
                    ).collect { samples ->
                        val latest = samples.firstOrNull()
                        val status = latest?.status ?: SampleStatus.WAITING
                        updateYesterdayCard(mealType, status, latest)
                    }
                }
            }
        }
    }

    private fun updateTodayCard(mealType: MealType, status: SampleStatus, sample: FoodSample?) {
        val currentCards = _uiState.value.todayCards.toMutableList()
        val index = currentCards.indexOfFirst { it.mealType == mealType }
        if (index >= 0) {
            currentCards[index] = MealCardState(mealType, status, 0, sample)
            _uiState.value = _uiState.value.copy(todayCards = currentCards)
        }
    }

    private fun updateYesterdayCard(mealType: MealType, status: SampleStatus, sample: FoodSample?) {
        val currentCards = _uiState.value.yesterdayCards.toMutableList()
        val index = currentCards.indexOfFirst { it.mealType == mealType }
        if (index >= 0) {
            currentCards[index] = MealCardState(mealType, status, -1, sample)
            _uiState.value = _uiState.value.copy(yesterdayCards = currentCards)
        }
    }

    private fun startTemperatureSimulation() {
        temperatureJob = viewModelScope.launch {
            while (isActive) {
                val temp = (40 + kotlin.random.Random.nextInt(0, 90)) / 10f
                temperatureRepository.insertTemperature(
                    com.foodfridge.domain.model.TemperatureRecord(
                        id = 0,
                        temperature = temp,
                        recordedAt = System.currentTimeMillis()
                    )
                )
                _uiState.value = _uiState.value.copy(
                    temperature = temp,
                    isTemperatureAlarm = temp > 8.0f
                )
                delay(30_000)
            }
        }
    }

    private fun startExpiryCheck() {
        expiryCheckJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val expired = foodSampleRepository.getExpiredSamples(now)
                expired.forEach { sample ->
                    foodSampleRepository.updateStatus(sample.id, SampleStatus.WAITING_DISPOSE.name)
                    Log.i("FridgeHome", "留样已过期: ${sample.foodName}, 自动标记为待消样")
                }
                delay(60_000)
            }
        }
    }

    fun onFaceDetectionFrame(bitmap: Bitmap) {
        // 已认证或正在认证，忽略
        if (_uiState.value.isAuthenticated || _uiState.value.showAuthGate) {
            recycleBitmap(bitmap)
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (!faceEngine.isReady()) {
                    Log.i("FridgeHome", "FaceEngine not ready, initializing synchronously")
                    faceEngine.init(appContext)
                    if (!faceEngine.isReady()) {
                        Log.e("FridgeHome", "FaceEngine initialization failed")
                        recycleBitmap(bitmap)
                        return@launch
                    }
                }
                
                val hasFace = faceEngine.detect(bitmap)
                Log.d("FridgeHome", "人脸检测结果: hasFace=$hasFace, count=$faceDetectionCount")

                if (!hasFace) {
                    // 未检测到人脸，重置计数
                    faceDetectionCount = 0
                    _uiState.value = _uiState.value.copy(faceDetectionFrames = 0)
                    recycleBitmap(bitmap)
                    return@launch
                }

                // 检测到人脸，增加计数
                val now = System.currentTimeMillis()
                if (now - lastFaceDetectionAt > 2000) {
                    faceDetectionCount = 0
                }
                lastFaceDetectionAt = now
                faceDetectionCount++

                _uiState.value = _uiState.value.copy(faceDetectionFrames = faceDetectionCount)
                Log.i("FridgeHome", "检测到人脸，计数=$faceDetectionCount/3")

                if (faceDetectionCount >= 3) {
                    Log.i("FridgeHome", "连续3帧检测到人脸，自动弹出人脸识别")
                    _uiState.value = _uiState.value.copy(showAuthGate = true)
                    faceDetectionCount = 0
                }
            } catch (e: Exception) {
                Log.e("FridgeHome", "人脸检测过程出错", e)
                faceDetectionCount = 0
            } finally {
                recycleBitmap(bitmap)
            }
        }
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            runCatching { bitmap.recycle() }
        }
    }

    fun onAuthSuccess() {
        loadUserAuthState()
        _uiState.value = _uiState.value.copy(showAuthGate = false, faceDetectionFrames = 0)
        faceDetectionCount = 0
    }

    fun onAuthDismiss() {
        _uiState.value = _uiState.value.copy(showAuthGate = false, faceDetectionFrames = 0)
        faceDetectionCount = 0
    }

    fun clearPendingNavigation() {
        _uiState.value = _uiState.value.copy(
            pendingMealType = null,
            pendingDayOffset = 0,
        )
    }

    fun refreshAuthState() {
        loadUserAuthState()
    }

    fun logout() {
        viewModelScope.launch {
            userPreferencesRepository.clearSession()
            _uiState.value = _uiState.value.copy(
                isAuthenticated = false,
                authUsers = emptyList(),
                currentUserName = null,
                authPromptMessage = "未认证 - 注视屏幕自动识别",
            )
            faceDetectionCount = 0
            authExpiryJob?.cancel()
            Log.i("FridgeHome", "用户手动退出登录")
        }
    }

    override fun onCleared() {
        super.onCleared()
        temperatureJob?.cancel()
        expiryCheckJob?.cancel()
        authExpiryJob?.cancel()
    }
}
