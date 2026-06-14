package com.foodfridge.data.hardware

import android.content.Context
import android.util.Log
import com.sdk.api.manager.ApiManager
import com.sdk.api.manager.ErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {

    companion object {
        private const val TAG = "HardwareManager"
        private const val RELAY_OPEN = true
        private const val RELAY_CLOSE = false
    }

    private val apiManager: ApiManager by lazy {
        ApiManager.getInstance(context)
    }

    // ── 温度 ────────────────────────────────────────────────

    private val _temperature = MutableStateFlow<Float?>(null)
    val temperature: StateFlow<Float?> = _temperature.asStateFlow()

    private var temperatureCallbackRegistered = false

    private var temperaturePollingJob: Job? = null

    /**
     * 注册温度控制器回调并开始读取温度数据。
     * 通过 setFCCTL_CONTROL 接收 VKDrive 异步回调（当前 SDK 中该通道实际对应蜂鸣器/静音控制，仅作日志记录），
     * 同时尝试从 Android thermal zone 或常见自定义节点读取真实温度。
     */
    fun startTemperatureReading() {
        if (temperatureCallbackRegistered) return

        registerTemperatureCallbacks()

        temperaturePollingJob?.cancel()
        temperaturePollingJob = CoroutineScope(Dispatchers.IO).launch {
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
                        // 连续失败 3 次后尝试通过 VKDrive 查询一次（如后续硬件协议变更可复用）
                        if (consecutiveFailures >= 3) {
                            queryTemperatureViaVKDrive()
                            consecutiveFailures = 0
                        }
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

    private fun queryTemperatureViaVKDrive() {
        try {
            apiManager.sendCmd("FCCTL_CONTROL", "1", "")
            Log.d(TAG, "Sent FCCTL_CONTROL query via VKDrive")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send FCCTL_CONTROL query", e)
        }
    }

    /**
     * 读取真实环境温度。
     * 按优先级尝试：
     * 1. 非 SoC/GPU 的 thermal zone（如 battery/ambient，刻度自动识别）
     * 2. SoC/GPU thermal zone（仅作为兜底）
     * 3. 自定义驱动常见路径
     */
    private fun readRealTemperature(): Float? {
        val thermalZones = enumerateThermalZones()
        val preferred = thermalZones.filter { !isCpuGpuThermal(it.type) }
        val fallback = thermalZones.filter { isCpuGpuThermal(it.type) }

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

    private data class ThermalZone(val index: Int, val type: String, val tempPath: String)

    private fun enumerateThermalZones(): List<ThermalZone> {
        val zones = mutableListOf<ThermalZone>()
        for (i in 0..20) {
            val typePath = "/sys/class/thermal/thermal_zone$i/type"
            val tempPath = "/sys/class/thermal/thermal_zone$i/temp"
            val type = readSysFile(typePath) ?: break
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
     */
    private fun tryAutoScale(value: Float): Float? {
        return normalizeTemperature(value)
            ?: normalizeTemperature(value / 10f)
            ?: normalizeTemperature(value / 100f)
            ?: normalizeTemperature(value / 1000f)
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

    // ── 电磁锁 ──────────────────────────────────────────────

    /**
     * 开锁（释放继电器）
     * @return true 表示操作成功
     */
    fun unlockDoor(): Boolean {
        return try {
            val result = apiManager.setRelaysControly(RELAY_OPEN)
            Log.i(TAG, "unlockDoor result=$result")
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
}
