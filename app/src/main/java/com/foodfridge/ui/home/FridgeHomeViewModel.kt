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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : java.io.Serializable

data class FridgeHomeUiState(
    val temperature: Float = 4.0f,
    val isTemperatureAlarm: Boolean = false,
    val day1Cards: List<MealCardState> = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, 0) },
    val day2Cards: List<MealCardState> = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, 1) },
    val day3Cards: List<MealCardState> = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, 2) },
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
    val isProcessingAuth: Boolean = false,
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
    private val faceDetectionMutex = Mutex()
    private val viewModelCreatedAt = System.currentTimeMillis()
    // 认证门关闭后的冷却时间戳，防止从Gate返回后立即被首页人脸检测重新触发
    private var authGateCooldownUntil = 0L

    // 三天显示的基准日期（初始化为当天00:00），第一天 = baseDate - 2天
    private var baseDate: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

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
            val currentState = _uiState.value
            Log.d("FridgeHome", "onUserAuthenticated 开始: userId=$userId, isAuthenticated=${currentState.isAuthenticated}, authUsers=${currentState.authUsers.size}, dualEnabled=${currentState.dualFaceAuthEnabled}, isProcessingAuth=${currentState.isProcessingAuth}")

            // 防重复处理：如果已认证且 authUsers 非空（单人脸）或已有 2 人（双人脸），跳过
            if (currentState.isAuthenticated &&
                (!currentState.dualFaceAuthEnabled && currentState.authUsers.isNotEmpty() ||
                 currentState.dualFaceAuthEnabled && currentState.authUsers.size >= 2)
            ) {
                Log.i("FridgeHome", "用户已认证，跳过重复处理: $userId")
                _uiState.value = _uiState.value.copy(isProcessingAuth = false)
                return@launch
            }

            // 先立即标记为已认证，阻止相机检测（即使后续验证失败也会回滚）
            _uiState.value = _uiState.value.copy(isAuthenticated = true)
            try {
                val user = userRepository.getUserById(userId)
                if (user == null || !user.isActive) {
                    Log.w("FridgeHome", "认证用户不存在或已停用: $userId")
                    _uiState.value = _uiState.value.copy(isAuthenticated = false)
                    return@launch
                }

                // 使用 UI state 中的 dualFaceAuthEnabled，避免 DataStore 异步读取延迟/不一致
                val dualEnabled = _uiState.value.dualFaceAuthEnabled
                Log.d("FridgeHome", "使用 UI state 的 dualFaceAuthEnabled=$dualEnabled")
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
            } finally {
                // isAuthenticated 已在开头立即设置，可以直接清除处理中标志
                _uiState.value = _uiState.value.copy(isProcessingAuth = false)
                Log.i("FridgeHome", "认证处理完成，isProcessingAuth 已清除, 最终isAuthenticated=${_uiState.value.isAuthenticated}")
            }
        }
    }

    private fun loadMealStates() {
        viewModelScope.launch {
            val day1Start = baseDate - 2 * DAY_MILLIS
            val day1End = baseDate - DAY_MILLIS
            val day2Start = baseDate - DAY_MILLIS
            val day2End = baseDate
            val day3Start = baseDate
            val day3End = baseDate + DAY_MILLIS

            // 第一天
            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, day1Start, day1End
                    ).collect { samples ->
                        val latest = samples.firstOrNull()
                        val status = latest?.status ?: SampleStatus.WAITING
                        updateDay1Card(mealType, status, latest)
                    }
                }
            }

            // 第二天
            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, day2Start, day2End
                    ).collect { samples ->
                        val latest = samples.firstOrNull()
                        val status = latest?.status ?: SampleStatus.WAITING
                        updateDay2Card(mealType, status, latest)
                    }
                }
            }

            // 第三天
            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, day3Start, day3End
                    ).collect { samples ->
                        val latest = samples.firstOrNull()
                        val status = latest?.status ?: SampleStatus.WAITING
                        updateDay3Card(mealType, status, latest)
                    }
                }
            }
        }
    }

    private fun updateDay1Card(mealType: MealType, status: SampleStatus, sample: FoodSample?) {
        val currentCards = _uiState.value.day1Cards.toMutableList()
        val index = currentCards.indexOfFirst { it.mealType == mealType }
        if (index >= 0) {
            currentCards[index] = MealCardState(mealType, status, 0, sample)
            _uiState.value = _uiState.value.copy(day1Cards = currentCards)
        }
    }

    private fun updateDay2Card(mealType: MealType, status: SampleStatus, sample: FoodSample?) {
        val currentCards = _uiState.value.day2Cards.toMutableList()
        val index = currentCards.indexOfFirst { it.mealType == mealType }
        if (index >= 0) {
            currentCards[index] = MealCardState(mealType, status, 1, sample)
            _uiState.value = _uiState.value.copy(day2Cards = currentCards)
        }
    }

    private fun updateDay3Card(mealType: MealType, status: SampleStatus, sample: FoodSample?) {
        val currentCards = _uiState.value.day3Cards.toMutableList()
        val index = currentCards.indexOfFirst { it.mealType == mealType }
        if (index >= 0) {
            currentCards[index] = MealCardState(mealType, status, 2, sample)
            _uiState.value = _uiState.value.copy(day3Cards = currentCards)
        }
    }

    /**
     * 日期滚动：当第一天晚餐被消样后，baseDate增加1天，重新加载所有数据
     */
    private fun rollDayOffsets() {
        baseDate += DAY_MILLIS
        Log.i("FridgeHome", "日期滚动触发，新baseDate=${baseDate}")
        // 重置所有卡片为WAITING状态，然后重新加载
        _uiState.value = _uiState.value.copy(
            day1Cards = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, 0) },
            day2Cards = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, 1) },
            day3Cards = MealType.entries.map { MealCardState(it, SampleStatus.WAITING, 2) },
        )
        loadMealStates()
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
                var shouldRollDay = false

                expired.forEach { sample ->
                    foodSampleRepository.updateStatus(sample.id, SampleStatus.WAITING_DISPOSE.name)
                    Log.i("FridgeHome", "留样已过期: ${sample.foodName}, 自动标记为待消样")

                    // 检测是否是第一天的晚餐被消样，触发日期滚动
                    val day1Start = baseDate - 2 * DAY_MILLIS
                    val day1End = baseDate - DAY_MILLIS
                    if (sample.mealType == MealType.DINNER &&
                        sample.createdAt in day1Start until day1End &&
                        sample.status == SampleStatus.STORING
                    ) {
                        shouldRollDay = true
                        Log.i("FridgeHome", "检测到第一天晚餐消样，准备触发日期滚动")
                    }
                }

                if (shouldRollDay) {
                    rollDayOffsets()
                }

                delay(60_000)
            }
        }
    }

    fun onFaceDetectionFrame(bitmap: Bitmap) {
        // 已认证、正在显示认证门、认证处理中、或在冷却期内，忽略
        val state = _uiState.value
        if (state.isAuthenticated || state.showAuthGate || state.isProcessingAuth) {
            Log.d("FridgeHome", "忽略帧: isAuthenticated=${state.isAuthenticated}, showAuthGate=${state.showAuthGate}, isProcessingAuth=${state.isProcessingAuth}")
            recycleBitmap(bitmap)
            return
        }
        if (System.currentTimeMillis() < authGateCooldownUntil) {
            recycleBitmap(bitmap)
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            faceDetectionMutex.withLock {
                try {
                    // 再次检查状态（在获取锁后可能已变化）
                    val currentState = _uiState.value
                    if (currentState.isAuthenticated || currentState.showAuthGate || currentState.isProcessingAuth) {
                        Log.d("FridgeHome", "获取锁后状态已变，忽略帧")
                        recycleBitmap(bitmap)
                        return@withLock
                    }

                    if (!faceEngine.isReady()) {
                        Log.i("FridgeHome", "FaceEngine not ready, initializing synchronously")
                        faceEngine.init(appContext)
                        if (!faceEngine.isReady()) {
                            Log.e("FridgeHome", "FaceEngine initialization failed")
                            recycleBitmap(bitmap)
                            return@withLock
                        }
                    }

                    val hasFace = faceEngine.detect(bitmap)
                    Log.d("FridgeHome", "人脸检测结果: hasFace=$hasFace, count=$faceDetectionCount")

                    if (!hasFace) {
                        // 未检测到人脸，重置计数
                        faceDetectionCount = 0
                        _uiState.value = _uiState.value.copy(faceDetectionFrames = 0)
                        recycleBitmap(bitmap)
                        return@withLock
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
        // 设置冷却期 + 认证处理中标志，防止从Gate返回首页后、isAuthenticated更新前的窗口期内
        // 人脸检测再次触发重复认证（覆盖collectAsStateWithLifecycle的延迟 + onUserAuthenticated执行时间）
        authGateCooldownUntil = System.currentTimeMillis() + 3000
        _uiState.value = _uiState.value.copy(showAuthGate = false, faceDetectionFrames = 0, isProcessingAuth = true)
        faceDetectionCount = 0
        // 启动15秒超时保险，防止用户从Gate手动返回后 isProcessingAuth 永远不清除
        viewModelScope.launch {
            delay(15_000)
            if (_uiState.value.isProcessingAuth) {
                _uiState.value = _uiState.value.copy(isProcessingAuth = false)
                Log.i("FridgeHome", "认证处理超时，自动清除处理中标志")
            }
        }
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
                isProcessingAuth = false,
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
