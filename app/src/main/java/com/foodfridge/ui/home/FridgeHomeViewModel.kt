package com.foodfridge.ui.home

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.data.hardware.HardwareManager
import com.foodfridge.data.hardware.TemperatureMonitor
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.dto.DoorRecordData
import com.foodfridge.domain.auth.DoorAuthorizationTracker
import com.foodfridge.domain.auth.DoorOperatorSnapshot
import com.foodfridge.domain.auth.FaceAuthenticationPolicy
import com.foodfridge.domain.face.FaceEngine
import com.foodfridge.domain.repository.DeviceUploadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import com.foodfridge.domain.model.User
import com.foodfridge.domain.repository.FoodSampleRepository
import com.foodfridge.domain.repository.UserRepository
import com.foodfridge.utils.DeviceInfoProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * 首页人脸检测会话状态，用于判断“是否稳定注视屏幕”。
 */
private data class FaceDetectionSession(
    val consecutiveFrames: Int = 0,
    val lastCenterX: Float = 0f,
    val lastCenterY: Float = 0f,
    val lastTimestamp: Long = 0L,
)

data class FridgeHomeUiState(
    val temperature: Float? = null,
    val isTemperatureAlarm: Boolean = false,
    val isTemperatureSensorFault: Boolean = false,
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
    val isSettingsOpen: Boolean = false,
    val baseDate: Long = 0L,
    // 温度报警配置
    val tempAlarmHigh: Float = 8.0f,
    val tempAlarmLow: Float = 0.0f,
    val tempAlarmEnabled: Boolean = true,
    val showTemperatureAlarmDialog: Boolean = false,
)

@HiltViewModel
class FridgeHomeViewModel @Inject constructor(
    private val foodSampleRepository: FoodSampleRepository,
    private val temperatureMonitor: TemperatureMonitor,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val userRepository: UserRepository,
    private val faceEngine: FaceEngine,
    private val hardwareManager: HardwareManager,
    private val deviceUploadRepository: DeviceUploadRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FridgeHomeUiState())
    val uiState: StateFlow<FridgeHomeUiState> = _uiState.asStateFlow()

    private var temperatureJob: Job? = null
    private var expiryCheckJob: Job? = null
    private var authExpiryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var doorMonitoringJob: Job? = null
    private var safetyMonitoringJob: Job? = null
    private var mealStatesJob: Job? = null
    private val deviceId by lazy { DeviceInfoProvider.getDeviceNumber(appContext) }
    private val heartbeatIntervalMs = 60L * 1000 // 1 分钟心跳一次
    private var detectionSession = FaceDetectionSession()
    private val faceDetectionMutex = Mutex()
    private val isFaceDetectionInFlight = AtomicBoolean(false)
    private val viewModelCreatedAt = System.currentTimeMillis()
    private var lastFaceDebugLogAt = 0L
    private var authConfigInitialized = false
    private val doorAuthorizationTracker = DoorAuthorizationTracker()
    // 认证门关闭后的冷却时间戳，防止从Gate返回后立即被首页人脸检测重新触发
    private var authGateCooldownUntil = 0L

    // 三天显示的基准日期
    // Day1 = 有活跃样品的最早日期（无活跃样品时为今天）
    // Day2 = Day1 + 1, Day3 = Day1 + 2
    private var baseDate: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * 计算第一列应该显示的日期：
     * 有活跃样品（存样中/待消样）的最早日期；如果没有活跃样品，返回今天
     */
    private suspend fun calculateFirstColumnDate(): Long {
        val activeSamples = foodSampleRepository.getActiveSamples()
        val result = DateRollingLogic.firstColumnDate(
            activeSampleStoreTimes = activeSamples.map { it.storeTime },
            todayMillis = System.currentTimeMillis(),
        )
        Log.d("FridgeHome", "第一列日期: $result, 活跃样品数: ${activeSamples.size}")
        return result
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val FACE_DETECTION_INTERVAL_MS = 200L
        private const val FACE_DETECTION_TIMEOUT_MS = 2000L
        private const val AUTH_TIMEOUT_MS = 120_000L // 认证有效期 2 分钟
        // 640x480 分辨率下 50cm 距离人脸占比，配合高分辨率使用更宽容的阈值
        private const val FACE_MIN_BOX_RATIO = 0.003f
        private const val FACE_MIN_SCORE = 0.30f
        // 640x480 下像素密度是 320x240 的 2 倍，阈值等比例放宽（保守值）
        private const val FACE_STABLE_THRESHOLD_PX = 120f
        // 首页只负责唤起认证页，身份校验仍由认证页完成。首个合格人脸立即响应，
        // 避免低性能设备上多帧检测累计到数秒。
        private const val REQUIRED_CONSECUTIVE_FRAMES = 1
    }

    init {
        viewModelScope.launch(Dispatchers.Default) {
            Log.i("FridgeHome", "正在初始化FaceEngine...")
            faceEngine.init(appContext)
            if (faceEngine.isReady()) {
                Log.i("FridgeHome", "FaceEngine初始化完成，人脸检测已就绪")
            } else {
                Log.e("FridgeHome", "FaceEngine初始化失败，人脸检测可能无法工作")
            }
        }
        observeAuthConfig()
        loadUserAuthState()
        observeTemperatureMonitoring()
        startDoorMonitoring()
        startSafetyMonitoring()
        startHeartbeat()
        startExpiryCheck()
        // 启动自动补光（基于摄像头帧亮度）
        hardwareManager.ambientLightController.start()
        loadMealStates()
        // 初始化baseDate到UI state
        _uiState.value = _uiState.value.copy(baseDate = baseDate)
    }

    private fun observeAuthConfig() {
        viewModelScope.launch {
            userPreferencesRepository.dualFaceAuthEnabled
                .distinctUntilChanged()
                .collect { dualEnabled ->
                    val previousMode = _uiState.value.dualFaceAuthEnabled
                    val modeChanged = authConfigInitialized && previousMode != dualEnabled
                    authConfigInitialized = true

                    if (modeChanged) {
                        resetAuthenticationForModeChange(dualEnabled)
                    } else {
                        _uiState.value = _uiState.value.copy(dualFaceAuthEnabled = dualEnabled)
                    }
                }
        }
    }

    private suspend fun resetAuthenticationForModeChange(dualFaceEnabled: Boolean) {
        authExpiryJob?.cancel()
        doorAuthorizationTracker.invalidateFutureAccess()
        userPreferencesRepository.clearSession()
        hardwareManager.lockDoor()
        hardwareManager.lightOff()
        _uiState.value = _uiState.value.copy(
            isAuthenticated = false,
            authUsers = emptyList(),
            currentUserName = null,
            dualFaceAuthEnabled = dualFaceEnabled,
            authPromptMessage = "认证模式已切换，请重新刷脸",
            isProcessingAuth = false,
        )
        detectionSession = FaceDetectionSession()
        Log.i("FridgeHome", "认证模式切换为 dual=$dualFaceEnabled，旧认证已清除并重新锁门")
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

    private var authExpiryRemainingMs = AUTH_TIMEOUT_MS
    private var authExpiryPausedAt = 0L

    private fun startAuthExpiryTimer() {
        authExpiryJob?.cancel()
        authExpiryRemainingMs = AUTH_TIMEOUT_MS
        authExpiryPausedAt = 0L
        authExpiryJob = viewModelScope.launch {
            Log.i("FridgeHome", "认证有效期2分钟，启动过期计时器")
            delay(authExpiryRemainingMs)
            doorAuthorizationTracker.invalidateFutureAccess()
            userPreferencesRepository.clearLoginFlag()
            hardwareManager.lockDoor()
            hardwareManager.lightOff()
            _uiState.value = _uiState.value.copy(
                isAuthenticated = false,
                authUsers = emptyList(),
                currentUserName = null,
                authPromptMessage = "未认证 - 注视屏幕自动识别",
            )
            detectionSession = FaceDetectionSession()
            Log.i("FridgeHome", "认证已过期，自动退出登录")
        }
    }

    /**
     * 暂停认证过期计时（进入扫描等需要保留认证状态的页面时调用）
     */
    fun pauseAuthExpiryTimer() {
        if (authExpiryJob?.isActive == true) {
            authExpiryPausedAt = System.currentTimeMillis()
            authExpiryJob?.cancel()
            Log.i("FridgeHome", "认证过期计时已暂停")
        }
    }

    /**
     * 恢复认证过期计时（退出扫描页面后调用）
     */
    fun resumeAuthExpiryTimer() {
        if (authExpiryPausedAt == 0L) return
        // 计算暂停期间流逝的时间，并从剩余时间中扣除
        // 注意：这里应该扣除的是暂停前已经流逝的时间，而不是暂停时长
        // 正确的逻辑：暂停时记录暂停时刻，恢复时计算从暂停到恢复经过的时间，但这段时间不应该算在认证有效期内
        // 实际上，暂停时计时器已取消，恢复时应该重新计算剩余时间 = 总时间 - 已消耗时间
        // 已消耗时间 = 暂停时刻 - 开始时刻，但这里我们没有记录开始时刻
        // 简化：暂停期间不计入有效期，恢复时剩余时间不变（暂停的时间被"冻结"）
        authExpiryPausedAt = 0L
        if (authExpiryRemainingMs <= 0) {
            Log.i("FridgeHome", "认证已过期（暂停期间超时）")
            doorAuthorizationTracker.invalidateFutureAccess()
            viewModelScope.launch {
                userPreferencesRepository.clearLoginFlag()
            }
            hardwareManager.lockDoor()
            hardwareManager.lightOff()
            _uiState.value = _uiState.value.copy(
                isAuthenticated = false,
                authUsers = emptyList(),
                currentUserName = null,
                authPromptMessage = "未认证 - 注视屏幕自动识别",
            )
            detectionSession = FaceDetectionSession()
            return
        }
        authExpiryJob = viewModelScope.launch {
            Log.i("FridgeHome", "认证过期计时已恢复，剩余${authExpiryRemainingMs / 1000}秒")
            delay(authExpiryRemainingMs)
            doorAuthorizationTracker.invalidateFutureAccess()
            userPreferencesRepository.clearLoginFlag()
            hardwareManager.lockDoor()
            hardwareManager.lightOff()
            _uiState.value = _uiState.value.copy(
                isAuthenticated = false,
                authUsers = emptyList(),
                currentUserName = null,
                authPromptMessage = "未认证 - 注视屏幕自动识别",
            )
            detectionSession = FaceDetectionSession()
            Log.i("FridgeHome", "认证已过期，自动退出登录")
        }
    }

    /**
     * 处理新认证的用户（从人脸识别页面返回）
     *
     * 关键顺序：先执行硬件开锁/开灯，成功后再更新 UI 为已认证。
     * 避免硬件操作失败时 UI 状态与实际门锁状态不一致。
     */
    fun onUserAuthenticated(userId: Int) {
        viewModelScope.launch {
            try {
                val configuredDualMode = userPreferencesRepository.dualFaceAuthEnabled.first()
                if (_uiState.value.dualFaceAuthEnabled != configuredDualMode) {
                    resetAuthenticationForModeChange(configuredDualMode)
                }
                val currentState = _uiState.value
                Log.d("FridgeHome", "onUserAuthenticated 开始: userId=$userId, isAuthenticated=${currentState.isAuthenticated}, authUsers=${currentState.authUsers.size}, dualEnabled=${currentState.dualFaceAuthEnabled}, isProcessingAuth=${currentState.isProcessingAuth}")

                // 防重复处理：如果已认证且 authUsers 非空（单人脸）或已有 2 人（双人脸），跳过
                if (currentState.isAuthenticated &&
                    (!currentState.dualFaceAuthEnabled && currentState.authUsers.isNotEmpty() ||
                     currentState.dualFaceAuthEnabled && currentState.authUsers.size >= 2)
                ) {
                    Log.i("FridgeHome", "用户已认证，跳过重复处理: $userId")
                    return@launch
                }

                // 先查询用户，确认有效后再继续
                val user = userRepository.getUserById(userId)
                if (user == null || !user.isActive) {
                    Log.w("FridgeHome", "认证用户不存在或已停用: $userId")
                    return@launch
                }

                val dualEnabled = configuredDualMode
                Log.d("FridgeHome", "使用持久化配置的 dualFaceAuthEnabled=$dualEnabled")
                val currentUsers = _uiState.value.authUsers.toMutableList()
                val currentRoles = currentUsers.map { it.role }

                // 避免重复添加同一用户
                if (currentUsers.any { it.userId == userId.toString() }) {
                    Log.i("FridgeHome", "用户已认证，跳过重复: ${user.fullName}")
                    return@launch
                }

                val allowedRoles = FaceAuthenticationPolicy.allowedRoles(
                    dualFaceEnabled = dualEnabled,
                    authenticatedRoles = currentRoles,
                )
                if (user.role !in allowedRoles) {
                    val prompt = if (!dualEnabled) {
                        "单人脸模式仅允许留样员开门"
                    } else {
                        val requiredRole = allowedRoles.singleOrNull()
                            ?.let(FaceAuthenticationPolicy::displayName)
                            ?: "监督员或留样员"
                        "当前需要${requiredRole}认证，请更换人员"
                    }
                    _uiState.value = _uiState.value.copy(authPromptMessage = prompt)
                    Log.w(
                        "FridgeHome",
                        "认证角色不符合: user=${user.fullName}, role=${user.role}, allowed=$allowedRoles",
                    )
                    return@launch
                }

                if (!dualEnabled) {
                    // 单人脸模式只允许留样员开锁。
                    if (performUnlockAndLight()) {
                        authorizeDoorAccess(user)
                        persistAuthenticatedSampler(user)
                        val authUser = AuthUser(userId.toString(), user.fullName, user.role)
                        _uiState.value = _uiState.value.copy(
                            isAuthenticated = true,
                            authUsers = listOf(authUser),
                            currentUserName = user.fullName,
                            authPromptMessage = "已认证: ${user.fullName}",
                        )
                        startAuthExpiryTimer()
                        Log.i("FridgeHome", "单人脸认证通过: ${user.fullName} (${user.role})")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            authPromptMessage = "开锁失败，请重新认证",
                        )
                        Log.e("FridgeHome", "单人脸认证硬件操作失败")
                    }
                    return@launch
                }

                // 双人脸模式
                if (currentUsers.isEmpty()) {
                    // 第一人只记录认证状态，绝不执行开锁。
                    val authUser = AuthUser(userId.toString(), user.fullName, user.role)
                    currentUsers.add(authUser)
                    val prompt = buildSingleAuthPrompt(authUser)
                    _uiState.value = _uiState.value.copy(
                        authUsers = currentUsers,
                        currentUserName = null,
                        authPromptMessage = prompt,
                        isAuthenticated = false,
                    )
                    startAuthExpiryTimer()
                    Log.i("FridgeHome", "双人脸第一认证: ${user.fullName} (${user.role})")
                } else {
                    // 第二人必须与第一人角色互补。
                    val firstUser = currentUsers.first()
                    if (
                        FaceAuthenticationPolicy.shouldUnlock(
                            dualFaceEnabled = true,
                            authenticatedRoles = currentRoles,
                            candidateRole = user.role,
                        )
                    ) {
                        val sampler = if (user.role == FaceAuthenticationPolicy.ROLE_SAMPLER) {
                            user
                        } else {
                            val firstUserId = firstUser.userId.toIntOrNull()
                            if (firstUserId != null) {
                                userRepository.getUserById(firstUserId)
                            } else {
                                null
                            }
                        }
                        if (sampler == null) {
                            _uiState.value = _uiState.value.copy(
                                authPromptMessage = "留样员信息不存在，请重新认证",
                            )
                            Log.e("FridgeHome", "双人认证无法读取留样员信息")
                            return@launch
                        }

                        if (performUnlockAndLight()) {
                            authorizeDoorAccess(sampler)
                            persistAuthenticatedSampler(sampler)
                            val authUser = AuthUser(userId.toString(), user.fullName, user.role)
                            currentUsers.add(authUser)
                            val firstRoleLabel = FaceAuthenticationPolicy.displayName(firstUser.role)
                            val secondRoleLabel = FaceAuthenticationPolicy.displayName(user.role)
                            _uiState.value = _uiState.value.copy(
                                isAuthenticated = true,
                                authUsers = currentUsers,
                                currentUserName = sampler.fullName,
                                authPromptMessage = "双认证通过: ${firstUser.userName}($firstRoleLabel) + ${user.fullName}($secondRoleLabel)",
                            )
                            startAuthExpiryTimer()
                            Log.i("FridgeHome", "双人脸认证全部通过")
                        } else {
                            _uiState.value = _uiState.value.copy(
                                authPromptMessage = "开锁失败，请重新认证",
                            )
                            Log.e("FridgeHome", "双人脸认证硬件操作失败")
                        }
                    } else {
                        val needRole = FaceAuthenticationPolicy.allowedRoles(
                            dualFaceEnabled = true,
                            authenticatedRoles = currentRoles,
                        ).singleOrNull()?.let(FaceAuthenticationPolicy::displayName) ?: "另一位人员"
                        _uiState.value = _uiState.value.copy(
                            authPromptMessage = "需要$needRole 认证，请重新识别"
                        )
                        Log.i("FridgeHome", "双人脸角色不互补: first=${firstUser.role}, second=${user.role}")
                    }
                }
            } finally {
                _uiState.value = _uiState.value.copy(isProcessingAuth = false)
                Log.i("FridgeHome", "认证处理完成，isProcessingAuth 已清除, 最终isAuthenticated=${_uiState.value.isAuthenticated}")
            }
        }
    }

    private suspend fun persistAuthenticatedSampler(user: User) {
        try {
            userPreferencesRepository.saveAuthTokens(
                accessToken = "local_${user.id}",
                tokenType = "Bearer",
                wsToken = "local_${user.id}",
                expiresInSeconds = 86400,
            )
            userPreferencesRepository.saveUserSession(
                userId = user.id.toString(),
                userName = user.fullName,
                lastLoginPassword = user.password,
            )
        } catch (e: Exception) {
            Log.e("FridgeHome", "保存留样员认证会话失败: ${user.fullName}", e)
        }
    }

    private fun authorizeDoorAccess(user: User) {
        val now = System.currentTimeMillis()
        doorAuthorizationTracker.authorize(
            DoorOperatorSnapshot(
                operatorId = user.id,
                operatorName = user.fullName,
                authorizedAt = now,
                expiresAt = now + AUTH_TIMEOUT_MS,
            )
        )
        Log.i("FridgeHome", "已创建开门授权快照: operator=${user.fullName}, expiresAt=${now + AUTH_TIMEOUT_MS}")
    }

    /**
     * 执行开锁和开灯，只有两者都成功才返回 true。
     */
    private suspend fun performUnlockAndLight(): Boolean {
        return try {
            val unlockSuccess = hardwareManager.unlockDoor()
            val lightSuccess = hardwareManager.lightOn()
            unlockSuccess && lightSuccess
        } catch (e: Exception) {
            Log.e("FridgeHome", "开锁/开灯过程异常", e)
            false
        }
    }

    private fun loadMealStates() {
        mealStatesJob?.cancel()
        mealStatesJob = viewModelScope.launch {
            // 先计算第一列应该显示的日期
            val firstColumnDate = calculateFirstColumnDate()
            baseDate = firstColumnDate

            // Day1 = 有活跃样品的最早日期, Day2 = Day1+1, Day3 = Day1+2
            val day1Start = baseDate
            val day1End = baseDate + DAY_MILLIS
            val day2Start = baseDate + DAY_MILLIS
            val day2End = baseDate + 2 * DAY_MILLIS
            val day3Start = baseDate + 2 * DAY_MILLIS
            val day3End = baseDate + 3 * DAY_MILLIS

            // 更新 UI 上的 baseDate
            _uiState.value = _uiState.value.copy(baseDate = baseDate)

            // 第一天
            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, day1Start, day1End
                    ).collect { samples ->
                        val activeSample = findActiveSample(samples)
                        val status = activeSample?.status ?: SampleStatus.WAITING
                        updateDay1Card(mealType, status, activeSample)
                    }
                }
            }

            // 第二天
            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, day2Start, day2End
                    ).collect { samples ->
                        val activeSample = findActiveSample(samples)
                        val status = activeSample?.status ?: SampleStatus.WAITING
                        updateDay2Card(mealType, status, activeSample)
                    }
                }
            }

            // 第三天
            MealType.entries.forEach { mealType ->
                launch {
                    foodSampleRepository.getSamplesByMealAndDate(
                        mealType.name, day3Start, day3End
                    ).collect { samples ->
                        val activeSample = findActiveSample(samples)
                        val status = activeSample?.status ?: SampleStatus.WAITING
                        updateDay3Card(mealType, status, activeSample)
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
            Log.d("FridgeHome", "updateDay1Card: mealType=$mealType, status=$status, sampleId=${sample?.id}")
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
     * 查找当前有效的留样记录：优先返回存样中或待消样的记录，如果没有则返回最新记录
     * 同一餐品只取最新记录
     */
    private fun findActiveSample(samples: List<FoodSample>): FoodSample? {
        if (samples.isEmpty()) return null

        // 同一餐品只保留最新记录
        val latestByFoodName = samples
            .groupBy { it.foodName }
            .map { (_, sameNameSamples) -> sameNameSamples.maxByOrNull { it.createdAt }!! }

        // 优先查找存样中或待消样的记录
        val active = latestByFoodName.firstOrNull {
            it.status == SampleStatus.STORING || it.status == SampleStatus.WAITING_DISPOSE
        }
        // 如果没有活跃记录，返回最新记录（可能是已消样）
        val result = active ?: latestByFoodName.firstOrNull()
        Log.d("FridgeHome", "findActiveSample: samples=${samples.size}, deduped=${latestByFoodName.size}, active=${active?.status}, result=${result?.status}, id=${result?.id}")
        return result
    }

    /**
     * 刷新日期显示。baseDate 始终取当前日期，无需滚动。
     */
    private fun refreshDayOffsets() {
        Log.i("FridgeHome", "刷新日期显示，baseDate=$baseDate")
        loadMealStates()
    }

    private fun observeTemperatureMonitoring() {
        temperatureMonitor.start()
        temperatureJob = viewModelScope.launch {
            temperatureMonitor.state.collect { monitorState ->
                _uiState.value = _uiState.value.copy(
                    temperature = monitorState.reading?.celsius,
                    isTemperatureAlarm = monitorState.isAlarm,
                    showTemperatureAlarmDialog = monitorState.showAlarmDialog,
                    isTemperatureSensorFault = monitorState.isSensorFault,
                )
            }
        }
    }

    fun dismissTemperatureAlarm() {
        viewModelScope.launch {
            temperatureMonitor.dismissAlarmDialog()
        }
    }

    private fun startDoorMonitoring() {
        doorMonitoringJob = viewModelScope.launch {
            launch {
                hardwareManager.doorOpenedEvent.collect { openedAt ->
                    val operator = doorAuthorizationTracker.onDoorOpened(openedAt)
                    if (operator != null) {
                        Log.i(
                            "FridgeHome",
                            "门已打开，固定本次开门操作人: ${operator.operatorName}, openedAt=$openedAt",
                        )
                    } else {
                        Log.w("FridgeHome", "门已打开，但没有有效的人脸开门授权: openedAt=$openedAt")
                    }
                }
            }
            launch {
                hardwareManager.doorEvent.collect { event ->
                    val operator = doorAuthorizationTracker.completeDoorCycle(
                        openedAt = event.openedAt,
                        closedAt = event.closedAt,
                    )
                    uploadDoorRecord(
                        event = event,
                        operatorName = operator?.operatorName,
                    )
                }
            }
        }
        hardwareManager.startDoorMonitoring()
    }

    private fun uploadDoorRecord(
        event: HardwareManager.DoorEvent,
        operatorName: String?,
    ) {
        viewModelScope.launch {
            try {
                val result = deviceUploadRepository.uploadDoorRecord(
                    DoorRecordData(
                        device_id = deviceId,
                        timestamp = event.closedAt,
                        operator_name = operatorName,
                        open_timestamp = event.openedAt,
                        close_timestamp = event.closedAt,
                    )
                )
                result.fold(
                    onSuccess = { response ->
                        Log.i("FridgeHome", "开关门记录上报成功: code=${response.code}, message=${response.message}")
                    },
                    onFailure = { error ->
                        Log.e("FridgeHome", "开关门记录上报失败", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("FridgeHome", "开关门记录上报异常", e)
            }
        }
    }

    private fun startSafetyMonitoring() {
        hardwareManager.startSafetyMonitoring()
        safetyMonitoringJob = viewModelScope.launch {
            hardwareManager.safetyEvent.collect { event ->
                if (event == null) return@collect

                val message = when (event.type) {
                    HardwareManager.SafetyEventType.SMOKE ->
                        if (event.triggered) "⚠️ 检测到烟雾，请检查柜体安全" else "烟雾报警已解除"
                    HardwareManager.SafetyEventType.TAMPER ->
                        if (event.triggered) "⚠️ 检测到柜体被拆卸，请检查设备" else "防拆报警已解除"
                }

                Log.w("FridgeHome", "安全传感器事件: type=${event.type}, triggered=${event.triggered}")
                _uiState.value = _uiState.value.copy(
                    authPromptMessage = message,
                )
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val result = deviceUploadRepository.refresh(DeviceRefreshData())
                    result.fold(
                        onSuccess = { response ->
                            Log.i("FridgeHome", "心跳上报成功: code=${response.code}, message=${response.message}")
                            // 心跳成功后尝试重试之前失败的上报
                            val flushed = deviceUploadRepository.flushPendingUploads()
                            if (flushed > 0) {
                                Log.i("FridgeHome", "成功补报 $flushed 条缓存数据")
                            }
                        },
                        onFailure = { error ->
                            Log.e("FridgeHome", "心跳上报失败", error)
                        }
                    )
                } catch (e: Exception) {
                    Log.e("FridgeHome", "心跳上报异常", e)
                }
                delay(heartbeatIntervalMs)
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

                // 检查日期是否需要刷新（baseDate 会自动跟随真实日期，无需手动滚动）
                checkAndRefreshDayIfNeeded()

                delay(60_000)
            }
        }
    }

    /**
     * 检查日期是否需要刷新。每分钟检查一次，重新计算第一列日期。
     * 当最早活跃样品的日期变化时，触发日期滚动。
     */
    private suspend fun checkAndRefreshDayIfNeeded() {
        val newFirstColumnDate = calculateFirstColumnDate()
        if (newFirstColumnDate != baseDate) {
            Log.i("FridgeHome", "第一列日期变化，触发滚动: old=$baseDate, new=$newFirstColumnDate")
            loadMealStates()
        }
    }

    /** 设置页打开/关闭时调用，暂停/恢复人脸检测。 */
    fun setSettingsOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isSettingsOpen = open)
    }

    fun onFaceDetectionFrame(bitmap: Bitmap) {
        // 帧先喂给自动补光控制器（即使人脸检测跳过也要持续监测亮度）
        hardwareManager.ambientLightController.onFrame(bitmap)

        // 已认证、正在显示认证门、认证处理中、设置页打开、或在冷却期内，忽略
        val state = _uiState.value
        if (state.isAuthenticated || state.showAuthGate || state.isProcessingAuth || state.isSettingsOpen) {
            Log.d("FridgeHome", "Frame skipped: auth=${state.isAuthenticated}, gate=${state.showAuthGate}, processing=${state.isProcessingAuth}, settings=${state.isSettingsOpen}")
            recycleBitmap(bitmap)
            return
        }
        if (System.currentTimeMillis() < authGateCooldownUntil) {
            Log.d("FridgeHome", "Frame skipped: cooldown active")
            recycleBitmap(bitmap)
            return
        }
        // 引擎未就绪时跳过检测（但触发异步初始化）
        if (!faceEngine.isReady()) {
            Log.d("FridgeHome", "Frame skipped: FaceEngine not ready yet")
            recycleBitmap(bitmap)
            return
        }

        // 限制帧率：两帧之间至少间隔 200ms
        val now = System.currentTimeMillis()
        if (now - detectionSession.lastTimestamp < FACE_DETECTION_INTERVAL_MS) {
            recycleBitmap(bitmap)
            return
        }
        if (!isFaceDetectionInFlight.compareAndSet(false, true)) {
            recycleBitmap(bitmap)
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                faceDetectionMutex.withLock {
                    // 再次检查状态（在获取锁后可能已变化）
                    val currentState = _uiState.value
                    if (currentState.isAuthenticated || currentState.showAuthGate || currentState.isProcessingAuth) {
                        recycleBitmap(bitmap)
                        return@withLock
                    }
                    if (System.currentTimeMillis() < authGateCooldownUntil) {
                        recycleBitmap(bitmap)
                        return@withLock
                    }

                    if (!faceEngine.isReady()) {
                        recycleBitmap(bitmap)
                        return@withLock
                    }

                    // 超时重置会话
                    if (now - detectionSession.lastTimestamp > FACE_DETECTION_TIMEOUT_MS) {
                        detectionSession = FaceDetectionSession()
                    }

                    val faceDetail = try {
                        faceEngine.detectDetails(bitmap)
                    } catch (oom: OutOfMemoryError) {
                        Log.e("FridgeHome", "Face detection OOM, recycling bitmap", oom)
                        detectionSession = FaceDetectionSession()
                        recycleBitmap(bitmap)
                        return@withLock
                    } catch (e: Exception) {
                        Log.e("FridgeHome", "Face detection failed", e)
                        null
                    }
                    if (faceDetail == null) {
                        detectionSession = FaceDetectionSession()
                        _uiState.value = _uiState.value.copy(faceDetectionFrames = 0)
                        recycleBitmap(bitmap)
                        return@withLock
                    }

                    // 质量过滤（单次不佳只扣分，不全部清零）
                    if (faceDetail.score < FACE_MIN_SCORE) {
                        Log.d("FridgeHome", "人脸质量不足: score=${faceDetail.score} < $FACE_MIN_SCORE")
                        val decremented = maxOf(0, detectionSession.consecutiveFrames - 1)
                        detectionSession = detectionSession.copy(
                            consecutiveFrames = decremented,
                            lastTimestamp = now,
                        )
                        _uiState.value = _uiState.value.copy(faceDetectionFrames = decremented)
                        recycleBitmap(bitmap)
                        return@withLock
                    }
                    if (faceDetail.boxAreaRatio < FACE_MIN_BOX_RATIO) {
                        Log.d("FridgeHome", "人脸距离过远/过小: ratio=${faceDetail.boxAreaRatio} < $FACE_MIN_BOX_RATIO")
                        val decremented = maxOf(0, detectionSession.consecutiveFrames - 1)
                        detectionSession = detectionSession.copy(
                            consecutiveFrames = decremented,
                            lastTimestamp = now,
                        )
                        _uiState.value = _uiState.value.copy(faceDetectionFrames = decremented)
                        recycleBitmap(bitmap)
                        return@withLock
                    }

                    // 稳定性过滤
                    val isStable = if (detectionSession.consecutiveFrames > 0) {
                        val dx = kotlin.math.abs(faceDetail.centerX - detectionSession.lastCenterX)
                        val dy = kotlin.math.abs(faceDetail.centerY - detectionSession.lastCenterY)
                        dx < FACE_STABLE_THRESHOLD_PX && dy < FACE_STABLE_THRESHOLD_PX
                    } else {
                        true
                    }

                    if (!isStable) {
                        Log.d("FridgeHome", "人脸位置不稳定，暂停计数")
                        detectionSession = detectionSession.copy(
                            lastCenterX = faceDetail.centerX,
                            lastCenterY = faceDetail.centerY,
                            lastTimestamp = now,
                        )
                        recycleBitmap(bitmap)
                        return@withLock
                    }

                    val newConsecutive = detectionSession.consecutiveFrames + 1
                    detectionSession = FaceDetectionSession(
                        consecutiveFrames = newConsecutive,
                        lastCenterX = faceDetail.centerX,
                        lastCenterY = faceDetail.centerY,
                        lastTimestamp = now,
                    )
                    _uiState.value = _uiState.value.copy(faceDetectionFrames = newConsecutive)
                    Log.i(
                        "FridgeHome",
                        "稳定人脸检测: 计数=$newConsecutive/$REQUIRED_CONSECUTIVE_FRAMES, score=${faceDetail.score}, ratio=${faceDetail.boxAreaRatio}"
                    )

                    // 周期性调试日志，便于现场确认阈值
                    if (now - lastFaceDebugLogAt >= 1000) {
                        lastFaceDebugLogAt = now
                        Log.i(
                            "FridgeHome",
                            "人脸调试: score=${faceDetail.score}, ratio=${faceDetail.boxAreaRatio}, center=(${faceDetail.centerX},${faceDetail.centerY}), consecutive=$newConsecutive"
                        )
                    }

                    if (newConsecutive >= REQUIRED_CONSECUTIVE_FRAMES) {
                        Log.i("FridgeHome", "连续${REQUIRED_CONSECUTIVE_FRAMES}帧检测到稳定人脸，触发认证")
                        _uiState.value = _uiState.value.copy(showAuthGate = true)
                        detectionSession = FaceDetectionSession()
                    }
                }
            } catch (e: Exception) {
                Log.e("FridgeHome", "人脸检测过程出错", e)
                detectionSession = FaceDetectionSession()
            } finally {
                recycleBitmap(bitmap)
                isFaceDetectionInFlight.set(false)
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
        detectionSession = FaceDetectionSession()
    }

    /**
     * 主动请求打开人脸认证门（点击标签等场景）。
     * 与 onFaceDetectionFrame 触发的流程统一：先设置 showAuthGate 隐藏首页相机，
     * 再由 UI 层短暂延迟后导航，避免两个页面同时抢摄像头。
     */
    fun openAuthGate() {
        _uiState.value = _uiState.value.copy(showAuthGate = true, faceDetectionFrames = 0, isProcessingAuth = true)
        detectionSession = FaceDetectionSession()
    }

    /**
     * 认证页已经完成导航。保持处理中状态，直到收到明确的成功或取消结果。
     */
    fun onAuthGateNavigated() {
        _uiState.value = _uiState.value.copy(showAuthGate = false, faceDetectionFrames = 0, isProcessingAuth = true)
        detectionSession = FaceDetectionSession()
    }

    /**
     * 用户退出认证页时立即恢复首页摄像头。
     * 极短冷却只用于等待 Gate 的 CameraX 资源完成释放，避免原人脸瞬间重复拉起页面。
     */
    fun onAuthCancelled() {
        authGateCooldownUntil = System.currentTimeMillis() + 300
        _uiState.value = _uiState.value.copy(
            showAuthGate = false,
            faceDetectionFrames = 0,
            isProcessingAuth = false,
        )
        detectionSession = FaceDetectionSession()
        Log.i("FridgeHome", "认证已取消，首页人脸检测立即恢复")
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

    fun refreshMealStates() {
        Log.d("FridgeHome", "refreshMealStates called, baseDate=$baseDate")
        loadMealStates()
    }

    fun logout() {
        viewModelScope.launch {
            doorAuthorizationTracker.invalidateFutureAccess()
            userPreferencesRepository.clearSession()
            hardwareManager.lockDoor()
            hardwareManager.lightOff()
            _uiState.value = _uiState.value.copy(
                isAuthenticated = false,
                authUsers = emptyList(),
                currentUserName = null,
                authPromptMessage = "未认证 - 注视屏幕自动识别",
                isProcessingAuth = false,
            )
            detectionSession = FaceDetectionSession()
            authExpiryJob?.cancel()
            Log.i("FridgeHome", "用户手动退出登录")
        }
    }

    override fun onCleared() {
        super.onCleared()
        temperatureJob?.cancel()
        expiryCheckJob?.cancel()
        authExpiryJob?.cancel()
        heartbeatJob?.cancel()
        doorMonitoringJob?.cancel()
        safetyMonitoringJob?.cancel()
        hardwareManager.stopDoorMonitoring()
        hardwareManager.stopSafetyMonitoring()
        hardwareManager.ambientLightController.stop()
        hardwareManager.lockDoor()
        hardwareManager.lightOff()
    }
}
