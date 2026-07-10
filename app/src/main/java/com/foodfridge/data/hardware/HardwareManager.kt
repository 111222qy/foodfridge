package com.foodfridge.data.hardware

import android.content.Context
import android.util.Log
import com.sdk.api.manager.ApiManager
import com.sdk.api.manager.ErrorCode
import com.foodfridge.data.local.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 硬件管理器 — 封装 api.jar 的 ApiManager SDK
 *
 * 提供温度传感器读取、电磁锁控制、门磁传感器等硬件操作。
 * 温度数据通过 I_FCCTL_CONTROL 回调异步接收。
 */
@Singleton
class HardwareManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    companion object {
        private const val TAG = "HardwareManager"
        private const val RELAY_OPEN = true
        private const val RELAY_CLOSE = false

        // 门磁轮询间隔
        private const val DOOR_POLL_INTERVAL_MS = 300L
        // 最小有效开门时长，小于该值视为抖动
        private const val MIN_DOOR_OPEN_DURATION_MS = 500L
        // 关门确认次数，避免门半开时快速翻转导致误报
        private const val DOOR_CLOSE_CONFIRM_COUNT = 2

        // 安全传感器轮询间隔
        private const val SAFETY_POLL_INTERVAL_MS = 1_500L

        // 温度区扫描范围
        private const val THERMAL_ZONE_SCAN_MAX = 50

        // 常见非环境温度类型，应优先排除
        private val NON_FRIDGE_THERMAL_TYPES = setOf(
            "pmic", "battery", "charger", "usb", "wifi", "modem", "bluetooth",
            "shell", "skin", "flash", "led", "speaker", "mic", "gpu", "cpu",
            "soc", "pkg", "big", "little"
        )

        // 常见传感器错误码/默认值，直接过滤避免误读
        private val INVALID_TEMPERATURE_RAW_VALUES = setOf(0f, -1f, 999f, 9999f, 65535f, 85000f)
        // 原始值超过该阈值视为异常，不再进行缩放
        private const val MAX_REASONABLE_TEMPERATURE_RAW = 200_000f
    }

    /**
     * 统一管理的协程作用域，随 Singleton 实例生命周期。
     * 避免每次 startXxx() 都创建新的 root scope 导致泄漏。
     */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val apiManager: ApiManager by lazy {
        ApiManager.getInstance(context)
    }

    // ── 温度 ────────────────────────────────────────────────

    private val _temperature = MutableStateFlow<Float?>(null)
    val temperature: StateFlow<Float?> = _temperature.asStateFlow()

    @Volatile
    private var temperatureCallbackRegistered = false

    @Volatile
    private var thermalZonesLogged = false

    private var temperaturePollingJob: Job? = null

    // ── 门磁事件 ──────────────────────────────────────────────

    private val _doorEvent = MutableStateFlow<DoorEvent?>(null)
    val doorEvent: StateFlow<DoorEvent?> = _doorEvent.asStateFlow()

    private var doorMonitoringJob: Job? = null

    /**
     * 一次完整的开门→关门事件。
     */
    data class DoorEvent(
        val openAt: Long,
        val closeAt: Long,
    )

    /**
     * 注册温度控制器回调并开始读取温度数据。
     * 通过 setFCCTL_CONTROL 接收 VKDrive 异步回调（当前 SDK 中该通道实际对应蜂鸣器/静音控制，仅作日志记录），
     * 同时尝试从 Android thermal zone 或常见自定义节点读取真实温度。
     */
    fun startTemperatureReading() {
        if (temperatureCallbackRegistered) return

        registerTemperatureCallbacks()

        temperaturePollingJob?.cancel()
        temperaturePollingJob = managerScope.launch {
            // 首次启动时打印一次完整的 thermal zone 诊断信息
            if (!thermalZonesLogged) {
                logAllThermalZones()
                thermalZonesLogged = true
            }

            var consecutiveFailures = 0
            while (isActive) {
                try {
                    val temp = readRealTemperature()
                    if (temp != null) {
                        _temperature.value = temp
                        consecutiveFailures = 0
                        Log.i(TAG, "Real temperature read: $temp°C")
                    } else {
                        consecutiveFailures++
                        Log.w(TAG, "No valid temperature source (failures=$consecutiveFailures)")
                    }
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Temperature polling error", e)
                    delay(2000)
                }
            }
        }
    }

    private fun registerTemperatureCallbacks() {
        try {
            apiManager.setFCCTL_CONTROL(object : ApiManager.I_FCCTL_CONTROL {
                override fun showIOResult(result: String) {
                    Log.i(TAG, "FCCTL_CONTROL callback raw: '$result'")
                    // 注意：当前 api.jar 中 FCCTL_CONTROL 对应蜂鸣器/静音控制，不是温度。
                    // 保留回调以便观察硬件返回，但不更新温度。
                }
            })
            try {
                apiManager.setFCCTL_CONTROL1(object : ApiManager.I_FCCTL_CONTROL {
                    override fun showIOResult(result: String) {
                        Log.i(TAG, "FCCTL_CONTROL1 callback raw: '$result'")
                        // 同上，仅记录日志
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "setFCCTL_CONTROL1 not available", e)
            }
            temperatureCallbackRegistered = true
            Log.i(TAG, "Temperature callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register temperature callback", e)
        }
    }

    /**
     * 读取真实环境温度。
     * 按优先级尝试：
     * 1. 用户配置的覆盖节点（如现场指定的 food compartment sensor）
     * 2. 非 SoC/GPU/电池等非环境温度类型的 thermal zone
     * 3. SoC/GPU thermal zone（仅作为兜底）
     * 4. 自定义驱动常见路径
     */
    private suspend fun readRealTemperature(): Float? {
        // 1. 优先使用覆盖配置
        val overridePath = userPreferencesRepository.thermalZoneOverride.first()
        val overrideScale = userPreferencesRepository.thermalZoneScale.first()
        if (!overridePath.isNullOrBlank()) {
            val path = resolveThermalZonePath(overridePath)
            val raw = readSysFile(path)
            if (raw != null) {
                val parsed = parseWithScale(raw, overrideScale)
                if (parsed != null) {
                    Log.i(TAG, "Override temperature source: path=$path raw=$raw scale=$overrideScale celsius=${parsed}°C")
                    return parsed
                }
            }
            Log.w(TAG, "Override temperature source invalid: path=$path raw=$raw")
        }

        val thermalZones = enumerateThermalZones()
        val preferred = thermalZones.filter { !isNonFridgeThermal(it.type) }
        val fallback = thermalZones.filter { isNonFridgeThermal(it.type) }

        for (zone in preferred + fallback) {
            val raw = readSysFile(zone.tempPath) ?: continue
            val parsed = parseThermalZoneTemp(raw)
            if (parsed != null) {
                Log.i(TAG, "temperature source: path=${zone.tempPath} type=${zone.type} raw=$raw celsius=${parsed}°C")
                return parsed
            }
        }

        val customPaths = listOf(
            "/sys/devices/platform/temperature/temp",
            "/sys/class/hwmon/hwmon0/temp1_input",
            "/sys/class/hwmon/hwmon1/temp1_input",
        )
        for (path in customPaths) {
            val raw = readSysFile(path) ?: continue
            val parsed = parseSysTemperature(raw)
            if (parsed != null) {
                Log.i(TAG, "temperature source: path=$path raw=$raw celsius=${parsed}°C")
                return parsed
            }
        }

        return null
    }

    private fun resolveThermalZonePath(input: String): String {
        return if (input.startsWith("/")) input else "/sys/class/thermal/$input/temp"
    }

    private fun parseWithScale(raw: String, scale: Int): Float? {
        val value = raw.toFloatOrNull() ?: return null
        return when (scale) {
            0 -> normalizeTemperature(value)
            1 -> normalizeTemperature(value / 10f)
            2 -> normalizeTemperature(value / 100f)
            3 -> normalizeTemperature(value / 1000f)
            4 -> normalizeTemperature(value / 10000f)
            else -> tryAutoScale(value)
        }
    }

    /**
     * 打印所有 thermal zone 的诊断信息，便于现场定位真实传感器。
     */
    private fun logAllThermalZones() {
        try {
            Log.i(TAG, dumpThermalZonesToString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump thermal zones", e)
        }
    }

    /**
     * 生成当前 thermal zone 与覆盖配置的诊断字符串，同时输出到日志。
     */
    suspend fun dumpThermalDiagnostics(): String {
        return try {
            val builder = StringBuilder()
            builder.appendLine("==== Thermal diagnostics start ====")
            builder.appendLine(dumpThermalZonesToString())

            val overridePath = userPreferencesRepository.thermalZoneOverride.first()
            val overrideScale = userPreferencesRepository.thermalZoneScale.first()
            builder.appendLine("Override: path=$overridePath scale=$overrideScale")

            if (!overridePath.isNullOrBlank()) {
                val resolved = resolveThermalZonePath(overridePath)
                val raw = readSysFile(resolved)
                builder.appendLine("Override resolved: $resolved, raw=$raw")
                val parsed = raw?.let { parseWithScale(it, overrideScale) }
                builder.appendLine("Override parsed: ${parsed ?: "invalid"}°C")
            }

            val current = readRealTemperature()
            builder.appendLine("Current selected temperature: ${current ?: "null"}°C")
            builder.appendLine("==== Thermal diagnostics end ====")
            builder.toString().also { Log.i(TAG, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump thermal diagnostics", e)
            "诊断失败: ${e.message}"
        }
    }

    private fun dumpThermalZonesToString(): String {
        val builder = StringBuilder()
        val zones = enumerateThermalZones()
        if (zones.isEmpty()) {
            builder.appendLine("No thermal zones found")
        } else {
            for (zone in zones) {
                val raw = readSysFile(zone.tempPath) ?: "null"
                val parsed = parseThermalZoneTemp(raw)
                builder.appendLine(
                    "zone${zone.index}: type=${zone.type}, raw=$raw, " +
                        "parsed=${parsed ?: "invalid"}°C, excluded=${isNonFridgeThermal(zone.type)}"
                )
            }
        }
        return builder.toString()
    }

    private fun isNonFridgeThermal(type: String): Boolean {
        val lower = type.lowercase()
        return NON_FRIDGE_THERMAL_TYPES.any { lower.contains(it) }
    }

    private data class ThermalZone(val index: Int, val type: String, val tempPath: String)

    private fun enumerateThermalZones(): List<ThermalZone> {
        val zones = mutableListOf<ThermalZone>()
        for (i in 0..THERMAL_ZONE_SCAN_MAX) {
            val typePath = "/sys/class/thermal/thermal_zone$i/type"
            val tempPath = "/sys/class/thermal/thermal_zone$i/temp"
            // thermal zone 索引不一定连续，某个 zone 缺失时应继续扫描后续 zone
            val type = readSysFile(typePath) ?: continue
            zones.add(ThermalZone(i, type, tempPath))
        }
        return zones
    }

    private fun isCpuGpuThermal(type: String): Boolean {
        val lower = type.lowercase()
        return lower.contains("soc") || lower.contains("cpu") || lower.contains("gpu") ||
                lower.contains("pkg") || lower.contains("big") || lower.contains("little")
    }

    private fun readSysFile(path: String): String? {
        return try {
            java.io.File(path).bufferedReader().use { it.readLine()?.trim() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 thermal_zone temp 节点原始值。
     * 不同厂家节点刻度不同（毫摄氏度、百分之一度、十分之一度等），
     * 这里尝试多种刻度并返回第一个合理值。
     */
    private fun parseThermalZoneTemp(raw: String): Float? {
        val value = raw.toFloatOrNull() ?: return null
        return tryAutoScale(value)
    }

    /**
     * 解析温度节点原始值。
     * 支持：
     * - 毫摄氏度整数（如 "25600" -> 25.6）
     * - 摄氏度浮点（如 "25.6"）
     * - 带单位（如 "25.6℃"）
     * - JSON（如 {"result":"25.6"}）
     */
    private fun parseSysTemperature(raw: String): Float? {
        if (raw.isBlank()) return null

        // 直接数字
        raw.toFloatOrNull()?.let { value ->
            return tryAutoScale(value)
        }

        // JSON
        try {
            val json = org.json.JSONObject(raw)
            for (key in listOf("result", "temperature", "temp", "value", "data", "t")) {
                if (json.has(key)) {
                    val valueStr = json.optString(key, "")
                    val value = if (valueStr.isNotEmpty()) {
                        valueStr.toFloatOrNull()
                    } else {
                        json.optDouble(key, Double.NaN).toFloat()
                    }
                    if (value != null && !value.isNaN()) {
                        tryAutoScale(value)?.let { return it }
                    }
                }
            }
        } catch (_: org.json.JSONException) {
            // not JSON
        }

        // 正则提取第一个浮点数
        Regex("""-?\d+\.?\d*""").find(raw)?.value?.toFloatOrNull()?.let { value ->
            return tryAutoScale(value)
        }

        return null
    }

    /**
     * 自动尝试不同刻度，返回第一个落在合理范围内的摄氏度值。
     *
     * 会对原始值做基础校验：
     * - 过滤常见错误码（0、-1、999、9999、65535、85000 等）
     * - 绝对值超过 [MAX_REASONABLE_TEMPERATURE_RAW] 视为异常，不再缩放
     */
    private fun tryAutoScale(rawValue: Float): Float? {
        if (rawValue in INVALID_TEMPERATURE_RAW_VALUES) {
            Log.w(TAG, "Ignore known invalid temperature raw value: $rawValue")
            return null
        }
        if (rawValue > MAX_REASONABLE_TEMPERATURE_RAW || rawValue < -MAX_REASONABLE_TEMPERATURE_RAW) {
            Log.w(TAG, "Temperature raw value out of reasonable range: $rawValue")
            return null
        }

        return normalizeTemperature(rawValue)
            ?: normalizeTemperature(rawValue / 10f)
            ?: normalizeTemperature(rawValue / 100f)
            ?: normalizeTemperature(rawValue / 1000f)
            ?: normalizeTemperature(rawValue / 10000f)
    }

    /**
     * 校验温度值是否在合理范围内（-50°C ~ 80°C）。
     */
    private fun normalizeTemperature(value: Float): Float? {
        return if (value in -50f..80f) value else null
    }

    /**
     * 停止温度读取。
     */
    fun stopTemperatureReading() {
        temperaturePollingJob?.cancel()
        temperaturePollingJob = null
        try {
            apiManager.setFCCTL_CONTROL(null)
            apiManager.setFCCTL_CONTROL1(null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear temperature callback", e)
        }
        temperatureCallbackRegistered = false
    }

    // ── 门磁事件 ──────────────────────────────────────────────

    /**
     * 开始监听门磁开关事件。
     * 当检测到「开门 → 关门」完整周期后，通过 [doorEvent] 发出事件。
     *
     * 包含简单防抖：
     * - 开门时间小于 [MIN_DOOR_OPEN_DURATION_MS] 视为抖动，不上报。
     * - 关门后连续两次确认门关才认为真正关闭，避免半开状态快速翻转。
     */
    fun startDoorMonitoring() {
        if (doorMonitoringJob != null) return

        doorMonitoringJob = managerScope.launch {
            var openAt: Long? = null
            var closeConfirmCount = 0
            while (isActive) {
                try {
                    val isOpen = isDoorOpen()
                    val now = System.currentTimeMillis()

                    when {
                        isOpen && openAt == null -> {
                            openAt = now
                            closeConfirmCount = 0
                            Log.i(TAG, "Door opened at $openAt")
                        }

                        !isOpen && openAt != null -> {
                            closeConfirmCount++
                            if (closeConfirmCount >= DOOR_CLOSE_CONFIRM_COUNT) {
                                val duration = now - openAt
                                if (duration >= MIN_DOOR_OPEN_DURATION_MS) {
                                    val event = DoorEvent(openAt = openAt, closeAt = now)
                                    _doorEvent.value = event
                                    Log.i(TAG, "Door closed at $now, event=$event")
                                } else {
                                    Log.d(TAG, "Ignore short door open event, duration=$duration ms")
                                }
                                openAt = null
                                closeConfirmCount = 0
                            }
                        }

                        isOpen -> {
                            // 门仍处于打开状态，重置关门确认计数
                            closeConfirmCount = 0
                        }
                    }
                    delay(DOOR_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Door monitoring error", e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * 停止监听门磁开关事件。
     */
    fun stopDoorMonitoring() {
        doorMonitoringJob?.cancel()
        doorMonitoringJob = null
    }

    // ── 安全传感器（烟感 / 防拆）────────────────────────────────

    private val _safetyEvent = MutableStateFlow<SafetyEvent?>(null)
    val safetyEvent: StateFlow<SafetyEvent?> = _safetyEvent.asStateFlow()

    private var safetyMonitoringJob: Job? = null

    /**
     * 安全传感器事件。
     */
    data class SafetyEvent(
        val type: SafetyEventType,
        val triggered: Boolean,
        val timestamp: Long,
    )

    enum class SafetyEventType {
        SMOKE,
        TAMPER,
    }

    /**
     * 开始监听烟感和防拆传感器状态。
     * 当状态变化时，通过 [safetyEvent] 发出事件。
     */
    fun startSafetyMonitoring() {
        if (safetyMonitoringJob != null) return

        var lastSmokeStatus = false
        var lastTamperStatus = false

        safetyMonitoringJob = managerScope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()

                    val smokeTriggered = readSmokeSensorStatus()
                    if (smokeTriggered != lastSmokeStatus) {
                        lastSmokeStatus = smokeTriggered
                        _safetyEvent.value = SafetyEvent(
                            type = SafetyEventType.SMOKE,
                            triggered = smokeTriggered,
                            timestamp = now,
                        )
                        Log.i(TAG, "Smoke sensor status changed: triggered=$smokeTriggered")
                    }

                    val tamperTriggered = readTamperStatus()
                    if (tamperTriggered != lastTamperStatus) {
                        lastTamperStatus = tamperTriggered
                        _safetyEvent.value = SafetyEvent(
                            type = SafetyEventType.TAMPER,
                            triggered = tamperTriggered,
                            timestamp = now,
                        )
                        Log.i(TAG, "Tamper sensor status changed: triggered=$tamperTriggered")
                    }

                    delay(SAFETY_POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Safety monitoring error", e)
                    delay(2000)
                }
            }
        }
    }

    /**
     * 停止监听安全传感器状态。
     */
    fun stopSafetyMonitoring() {
        safetyMonitoringJob?.cancel()
        safetyMonitoringJob = null
    }

    private fun readSmokeSensorStatus(): Boolean {
        return try {
            apiManager.getTheSmokeSensorStatus() == 1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read smoke sensor status", e)
            false
        }
    }

    private fun readTamperStatus(): Boolean {
        return try {
            apiManager.getTamperAlarmStatus() == 1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read tamper status", e)
            false
        }
    }

    // ── 电磁锁事件 ──────────────────────────────────────────────

    private val _lockEvent = MutableStateFlow<LockEvent?>(null)
    val lockEvent: StateFlow<LockEvent?> = _lockEvent.asStateFlow()

    /**
     * 电磁锁开关事件，用于生成开关门记录上报。
     */
    data class LockEvent(
        val openedAt: Long,
        val closedAt: Long,
    )

    @Volatile
    private var lockOpenedAt: Long? = null

    // ── 电磁锁 ──────────────────────────────────────────────

    /**
     * 开锁（释放继电器）
     * @return true 表示操作成功
     */
    fun unlockDoor(): Boolean {
        return try {
            val result = apiManager.setRelaysControly(RELAY_OPEN)
            Log.i(TAG, "unlockDoor result=$result")
            if (result == ErrorCode.OK) {
                lockOpenedAt = System.currentTimeMillis()
                Log.i(TAG, "Lock opened at ${lockOpenedAt}")
            }
            result == ErrorCode.OK
        } catch (e: Exception) {
            Log.e(TAG, "unlockDoor failed", e)
            false
        }
    }

    /**
     * 关锁（吸合继电器）
     * @return true 表示操作成功
     */
    fun lockDoor(): Boolean {
        return try {
            val result = apiManager.setRelaysControly(RELAY_CLOSE)
            Log.i(TAG, "lockDoor result=$result")
            if (result == ErrorCode.OK) {
                val openedAt = lockOpenedAt
                if (openedAt != null) {
                    val closedAt = System.currentTimeMillis()
                    _lockEvent.value = LockEvent(openedAt = openedAt, closedAt = closedAt)
                    lockOpenedAt = null
                    Log.i(TAG, "Lock closed at $closedAt, event emitted")
                }
            }
            result == ErrorCode.OK
        } catch (e: Exception) {
            Log.e(TAG, "lockDoor failed", e)
            false
        }
    }

    /**
     * 查询继电器状态
     * @return true 表示锁已打开（继电器释放）
     */
    fun isDoorUnlocked(): Boolean {
        return try {
            val status = apiManager.getRelaysControly()
            Log.d(TAG, "getRelaysControly=$status")
            status == 1
        } catch (e: Exception) {
            Log.e(TAG, "getRelaysControly failed", e)
            false
        }
    }

    // ── 门磁传感器 ───────────────────────────────────────────

    /**
     * 查询门磁传感器状态
     * @return true 表示门已打开
     */
    fun isDoorOpen(): Boolean {
        return try {
            val status = apiManager.getDSStatus()
            Log.d(TAG, "getDSStatus=$status")
            status == 1
        } catch (e: Exception) {
            Log.e(TAG, "getDSStatus failed", e)
            false
        }
    }

    /**
     * 查询二次门状态
     * @return true 表示门已打开
     */
    fun isDoorOpenSecondary(): Boolean {
        return try {
            val status = apiManager.getOpenDoorStatus()
            Log.d(TAG, "getOpenDoorStatus=$status")
            status == 1
        } catch (e: Exception) {
            Log.e(TAG, "getOpenDoorStatus failed", e)
            false
        }
    }

    // ── MCU 温度探测 ──────────────────────────────────────────

    /**
     * 诊断：向 MCU 发送各种可能的温度读取命令，记录回调响应。
     * 在 logcat 中搜索 tag="HardwareManager" + "MCU_PROBE" 查看结果。
     */
    fun probeMcuTemperature() {
        managerScope.launch {
            Log.i(TAG, "MCU_PROBE: === Starting MCU temperature probe ===")

            // 先测试 API 连接是否正常
            try {
                val apiVer = apiManager.apiVersion
                Log.i(TAG, "MCU_PROBE: API version = $apiVer")
            } catch (e: Exception) {
                Log.e(TAG, "MCU_PROBE: getApiVersion failed: ${e.message}")
            }
            try {
                val jarVer = apiManager.jarVersion
                Log.i(TAG, "MCU_PROBE: JAR version = $jarVer")
            } catch (e: Exception) {
                Log.e(TAG, "MCU_PROBE: getJarVersion failed: ${e.message}")
            }
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", "persist.sys.mcu.version"))
                val mcuVer = process.inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
                process.waitFor()
                Log.i(TAG, "MCU_PROBE: MCU version (property) = $mcuVer")
            } catch (e: Exception) {
                Log.e(TAG, "MCU_PROBE: getMCUVersion failed: ${e.message}")
            }
            try {
                val sn = apiManager.sn
                Log.i(TAG, "MCU_PROBE: SN = $sn")
            } catch (e: Exception) {
                Log.e(TAG, "MCU_PROBE: getSn failed: ${e.message}")
            }

            // 注册宽泛的 FCCTL_CONTROL 回调
            apiManager.setFCCTL_CONTROL(object : ApiManager.I_FCCTL_CONTROL {
                override fun showIOResult(result: String) {
                    Log.i(TAG, "MCU_PROBE: FCCTL_CONTROL callback = '$result'")
                }
            })
            try {
                apiManager.setFCCTL_CONTROL1(object : ApiManager.I_FCCTL_CONTROL {
                    override fun showIOResult(result: String) {
                        Log.i(TAG, "MCU_PROBE: FCCTL_CONTROL1 callback = '$result'")
                    }
                })
            } catch (_: Exception) {}

            // 尝试各种 workType/cmd 组合
            // sendCmd(workType, cmd, content)
            val probes = listOf(
                // 已知 workType
                Triple("HEART_BEAT", "1", ""),
                Triple("FCCTL_CONTROL", "1", ""),
                // 温度相关猜测
                Triple("TEMPERATURE", "1", ""),
                Triple("TEMP", "1", ""),
                Triple("READ_TEMP", "1", ""),
                Triple("GET_TEMP", "1", ""),
                Triple("TEMPERATURE", "0", ""),
                Triple("TEMPERATURE", "read", ""),
                // 可能的 MCU 内部传感器
                Triple("MCU_TEMP", "1", ""),
                Triple("MCU_TEMPERATURE", "1", ""),
                Triple("ENV_TEMP", "1", ""),
                Triple("CABINET_TEMP", "1", ""),
                Triple("BOX_TEMP", "1", ""),
                Triple("FRIDGE_TEMP", "1", ""),
                // 通用读取命令
                Triple("READ", "TEMP", ""),
                Triple("GET", "TEMP", ""),
                Triple("QUERY", "TEMP", ""),
                // 数字命令（MCU 可能用数字 workType）
                Triple("3", "1", ""),
                Triple("4", "1", ""),
                Triple("5", "1", ""),
                Triple("6", "1", ""),
                Triple("7", "1", ""),
                Triple("8", "1", ""),
                Triple("9", "1", ""),
                Triple("10", "1", ""),
                // 带数字 cmd 的已知 workType
                Triple("HEART_BEAT", "2", ""),
                Triple("FCCTL_CONTROL", "2", ""),
            )

            for ((workType, cmd, content) in probes) {
                try {
                    Log.i(TAG, "MCU_PROBE: Sending sendCmd('$workType', '$cmd', '$content')")
                    apiManager.sendCmd(workType, cmd, content)
                    delay(500) // 等待异步回调
                } catch (e: Exception) {
                    Log.e(TAG, "MCU_PROBE: sendCmd('$workType', '$cmd', '$content') threw: ${e.message}")
                }
            }

            Log.i(TAG, "MCU_PROBE: === Probe complete ===")
        }
    }

    /**
     * 诊断：读取所有 IIO ADC 通道，记录到 logcat。
     */
    fun probeAdcChannels(): String {
        val builder = StringBuilder()
        builder.appendLine("=== ADC Channel Probe ===")
        for (ch in 0..7) {
            val path = "/sys/bus/iio/devices/iio:device0/in_voltage${ch}_raw"
            val raw = readSysFile(path)
            builder.appendLine("ch$ch = $raw  ($path)")
        }
        val scale = readSysFile("/sys/bus/iio/devices/iio:device0/in_voltage_scale")
        builder.appendLine("scale = $scale mV/count")
        val name = readSysFile("/sys/bus/iio/devices/iio:device0/name")
        builder.appendLine("device = $name")
        Log.i(TAG, builder.toString())
        return builder.toString()
    }

    // ── 蜂鸣器 ──────────────────────────────────────────────

    /**
     * 打开蜂鸣器（报警）
     */
    fun buzzerOn(): Boolean {
        return try {
            val result = apiManager.setDoorbell(true)
            Log.i(TAG, "buzzerOn result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "buzzerOn failed", e)
            false
        }
    }

    /**
     * 关闭蜂鸣器
     */
    fun buzzerOff(): Boolean {
        return try {
            val result = apiManager.setDoorbell(false)
            Log.i(TAG, "buzzerOff result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "buzzerOff failed", e)
            false
        }
    }

    // ── 灯光 ─────────────────────────────────────────────────

    /**
     * 开灯
     */
    fun lightOn(): Boolean {
        return try {
            val result = apiManager.setWhiteLight(true)
            Log.i(TAG, "lightOn result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "lightOn failed", e)
            false
        }
    }

    /**
     * 关灯
     */
    fun lightOff(): Boolean {
        return try {
            val result = apiManager.setWhiteLight(false)
            Log.i(TAG, "lightOff result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "lightOff failed", e)
            false
        }
    }

    // ── 看门狗与进程守护 ─────────────────────────────────────

    /**
     * 使能硬件看门狗。
     * @param timeoutSeconds 超时时间（秒），超过该时间未喂狗将触发重启。
     *        SDK 文档要求该值必须 >= 60。
     */
    fun enableWatchdog(timeoutSeconds: Long): Boolean {
        return try {
            val result = apiManager.sys_setWatchDog(true, timeoutSeconds)
            Log.i(TAG, "enableWatchdog timeout=${timeoutSeconds}s result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "enableWatchdog failed", e)
            false
        }
    }

    /**
     * 关闭硬件看门狗。
     */
    fun disableWatchdog(): Boolean {
        return try {
            val result = apiManager.sys_setWatchDog(false, 0L)
            Log.i(TAG, "disableWatchdog result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "disableWatchdog failed", e)
            false
        }
    }

    /**
     * 喂狗一次。
     */
    fun feedWatchdog(): Boolean {
        return try {
            val result = apiManager.sys_setWatchDogFeed()
            Log.d(TAG, "feedWatchdog result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "feedWatchdog failed", e)
            false
        }
    }

    /**
     * 标记/取消标记本应用为守护应用，降低被系统清理的概率。
     */
    fun setDaemonKeepAlive(enabled: Boolean, packageName: String, timeoutMs: Long): Boolean {
        return try {
            val result = apiManager.sys_setDaemonsActivity(enabled, packageName, timeoutMs)
            Log.i(TAG, "setDaemonKeepAlive enabled=$enabled result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "setDaemonKeepAlive failed", e)
            false
        }
    }
}
