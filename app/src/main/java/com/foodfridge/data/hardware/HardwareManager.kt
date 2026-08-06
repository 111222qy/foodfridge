package com.foodfridge.data.hardware

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.q_zheng.QZhengIFManager
import com.q_zheng.QZhengGPIOManager
import com.q_zheng.QZGpio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val USB_SERIAL_PREFIX = "usb://"

internal fun isProcessorThermalZone(type: String): Boolean {
    val normalized = type.trim().lowercase(Locale.ROOT)
    return listOf("cpu", "gpu", "soc", "processor").any(normalized::contains)
}

internal fun modbusSerialCandidates(
    deviceType: String?,
    usbDeviceNames: List<String> = emptyList(),
): List<String> {
    val boardPorts = if (deviceType.equals("F28V2", ignoreCase = true)) {
        listOf("/dev/ttyS2", "/dev/ttyS1", "/dev/ttyS3")
    } else {
        listOf("/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3")
    }
    val usbPorts = usbDeviceNames.map { deviceName ->
        if (deviceName.startsWith(USB_SERIAL_PREFIX)) deviceName else USB_SERIAL_PREFIX + deviceName
    }
    return (boardPorts + listOf("/dev/ttyUSB0", "/dev/ttyUSB1") + usbPorts).distinct()
}

/**
 * 硬件管理器 — 封装 q-zhenglib SDK
 *
 * 提供温度传感器读取、电磁锁控制、门磁传感器、蜂鸣器、补光灯等硬件操作。
 * 冰箱温度只来自板载 RS-485 接口连接的外部 Modbus 采集模块。
 */
@Singleton
class HardwareManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "HardwareManager"

        // 看门狗推荐喂狗间隔（文档建议 < 10 秒）
        const val WATCHDOG_FEED_INTERVAL_MS = 8_000L
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ifManager: QZhengIFManager by lazy {
        QZhengIFManager(context)
    }

    private val gpioManager: QZhengGPIOManager by lazy {
        QZhengGPIOManager.getInstance(context)
    }

    // ── Modbus 温度读取器 ───────────────────────────────────

    private var modbusReader: ModbusTemperatureReader? = null
    private var modbusTempCollectorJob: Job? = null
    private var modbusStateCollectorJob: Job? = null
    private var modbusProbeCollectorJob: Job? = null
    private var modbusGeneration = 0L

    private val _modbusTemperature = MutableStateFlow<Float?>(null)
    val modbusTemperature: StateFlow<Float?> = _modbusTemperature.asStateFlow()

    private val _modbusReadings = MutableStateFlow<TemperatureReading?>(null)
    val modbusReadings: StateFlow<TemperatureReading?> = _modbusReadings.asStateFlow()

    private val _modbusConnectionState = MutableStateFlow<ModbusTemperatureReader.ConnectionState>(
        ModbusTemperatureReader.ConnectionState.Disconnected
    )
    val modbusConnectionState: StateFlow<ModbusTemperatureReader.ConnectionState> =
        _modbusConnectionState.asStateFlow()

    private val _modbusLastProbeResult = MutableStateFlow<ModbusProbeResult?>(null)
    val modbusLastProbeResult: StateFlow<ModbusProbeResult?> = _modbusLastProbeResult.asStateFlow()

    fun defaultModbusPort(): String = "/dev/ttyS2"

    fun preferredModbusPorts(): List<String> {
        val deviceType = runCatching { ifManager.deviceType }.getOrNull()
        val usbDeviceNames = runCatching {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return@runCatching emptyList()
            UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .map { driver -> driver.device.deviceName }
        }.getOrDefault(emptyList())
        return modbusSerialCandidates(deviceType, usbDeviceNames)
    }

    /**
     * 启动 Modbus 温度读取
     * @param config 已经人工确认的 Modbus 串口和寄存器配置
     * @param intervalMs 查询间隔，默认 5 秒
     */
    @Synchronized
    fun startModbusTemperatureReading(
        config: ModbusTemperatureConfig,
        intervalMs: Long = 5_000L
    ) {
        stopModbusTemperatureReading()
        val generation = ++modbusGeneration

        val reader = ModbusTemperatureReader(config, context)
        modbusReader = reader

        // 订阅 Modbus 温度流
        modbusTempCollectorJob = managerScope.launch {
            reader.readings.collect { reading ->
                if (generation != modbusGeneration || modbusReader !== reader) return@collect
                _modbusReadings.value = reading
                _modbusTemperature.value = reading?.celsius
                if (reading != null) {
                    Log.d(TAG, "Modbus temperature: ${reading.celsius}°C")
                }
            }
        }

        modbusStateCollectorJob = managerScope.launch {
            reader.connectionState.collect { state ->
                if (generation != modbusGeneration || modbusReader !== reader) return@collect
                _modbusConnectionState.value = state
                when (state) {
                    is ModbusTemperatureReader.ConnectionState.Connected ->
                        Log.i(TAG, "Modbus connected")
                    is ModbusTemperatureReader.ConnectionState.Error -> {
                        _modbusTemperature.value = null
                        Log.w(TAG, "Modbus error: ${state.message}")
                    }
                    is ModbusTemperatureReader.ConnectionState.Disconnected ->
                        _modbusTemperature.value = null
                    else -> Unit
                }
            }
        }

        modbusProbeCollectorJob = managerScope.launch {
            reader.lastProbeResult.collect { result ->
                if (generation != modbusGeneration || modbusReader !== reader) return@collect
                _modbusLastProbeResult.value = result
            }
        }

        reader.startReading(intervalMs)
        Log.i(
            TAG,
            "Modbus temperature reading started: ${config.devicePath} @ ${config.baudRate}, " +
                "slave=${config.slaveAddress}, function=${config.functionCode}, " +
                "register=${config.registerAddress}/${config.registerCount}",
        )
    }

    @Synchronized
    fun stopModbusTemperatureReading() {
        modbusGeneration++
        modbusTempCollectorJob?.cancel()
        modbusTempCollectorJob = null
        modbusStateCollectorJob?.cancel()
        modbusStateCollectorJob = null
        modbusProbeCollectorJob?.cancel()
        modbusProbeCollectorJob = null
        modbusReader?.destroy()
        modbusReader = null
        _modbusTemperature.value = null
        _modbusReadings.value = null
        _modbusLastProbeResult.value = null
        _modbusConnectionState.value = ModbusTemperatureReader.ConnectionState.Disconnected
        Log.i(TAG, "Modbus temperature reading stopped")
    }

    // Explicit sysfs fallback. A path must be configured; processor zones are rejected.

    private val _temperature = MutableStateFlow<Float?>(null)
    val temperature: StateFlow<Float?> = _temperature.asStateFlow()

    private val _temperatureReadings = MutableStateFlow<TemperatureReading?>(null)
    val temperatureReadings: StateFlow<TemperatureReading?> = _temperatureReadings.asStateFlow()

    private var thermalFallbackJob: Job? = null

    fun startTemperatureReading(
        thermalZonePath: String? = null,
        thermalZoneScale: Int = -1,
    ) {
        thermalFallbackJob?.cancel()
        thermalFallbackJob = null
        _temperature.value = null
        _temperatureReadings.value = null

        if (thermalZonePath.isNullOrBlank()) {
            Log.i(TAG, "No external sysfs temperature node configured; SoC temperature is ignored")
            return
        }
        startThermalZoneFallback(thermalZonePath, thermalZoneScale)
    }

    private fun startThermalZoneFallback(path: String, scale: Int) {
        thermalFallbackJob?.cancel()
        val zoneType = thermalZoneType(path)
        if (zoneType != null && isProcessorThermalZone(zoneType)) {
            _temperature.value = null
            _temperatureReadings.value = null
            Log.e(TAG, "Rejected processor thermal zone '$zoneType' as refrigerator temperature: $path")
            return
        }

        thermalFallbackJob = managerScope.launch {
            Log.w(TAG, "Using configured thermal zone fallback: $path, scale=$scale")
            while (isActive) {
                try {
                    readFromThermalZone(path, scale)?.let { celsius ->
                        _temperature.value = celsius
                        _temperatureReadings.value = TemperatureReading(
                            celsius = celsius,
                            recordedAt = System.currentTimeMillis(),
                        )
                        Log.i(TAG, "Thermal zone temperature: $celsius°C")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Thermal zone fallback error", e)
                }
                delay(5_000L)
            }
        }
    }

    private fun readFromThermalZone(path: String, scale: Int): Float? {
        val value = readSysFile(path)?.toFloatOrNull() ?: return null
        val celsius = if (scale > 0) {
            value / scale
        } else {
            when (value) {
                in -50f..80f -> value
                in -500f..800f -> value / 10f
                in -5_000f..8_000f -> value / 100f
                in -50_000f..80_000f -> value / 1_000f
                else -> return null
            }
        }
        return celsius.takeIf { it in -50f..80f }
    }

    fun stopTemperatureReading() {
        thermalFallbackJob?.cancel()
        thermalFallbackJob = null
        _temperature.value = null
        _temperatureReadings.value = null
        Log.i(TAG, "External sysfs temperature reading stopped")
    }

    // ── 电磁锁事件 ──────────────────────────────────────────────

    // ── 电磁锁（GPIO_ID_DOOR）──────────────────────────────

    fun unlockDoor(): Boolean {
        return try {
            gpioManager.getGPIO(QZhengGPIOManager.GPIO_ID_DOOR)
                .setValue(QZhengGPIOManager.GPIO_VALUE_HIGH)
            Log.i(TAG, "Door unlocked")
            true
        } catch (e: Exception) {
            Log.e(TAG, "unlockDoor failed", e)
            false
        }
    }

    fun lockDoor(): Boolean {
        return try {
            gpioManager.getGPIO(QZhengGPIOManager.GPIO_ID_DOOR)
                .setValue(QZhengGPIOManager.GPIO_VALUE_LOW)
            Log.i(TAG, "Door locked")
            true
        } catch (e: Exception) {
            Log.e(TAG, "lockDoor failed", e)
            false
        }
    }

    fun isDoorUnlocked(): Boolean {
        return try {
            val value = gpioManager.getGPIO(QZhengGPIOManager.GPIO_ID_DOOR).getValue()
            value == QZhengGPIOManager.GPIO_VALUE_HIGH
        } catch (e: Exception) {
            Log.e(TAG, "isDoorUnlocked failed", e)
            false
        }
    }

    // ── 门磁传感器（GPIO_DOOR_DET1）──────────────────────────

    private var doorDetGpio: QZGpio? = null
    private var doorDebounceJob: Job? = null
    private var lastDoorOpen: Boolean? = null
    private var doorOpenedAt: Long? = null

    private val _doorEvent = MutableSharedFlow<DoorEvent>(extraBufferCapacity = 8)
    val doorEvent: SharedFlow<DoorEvent> = _doorEvent.asSharedFlow()

    private val _doorOpenedEvent = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val doorOpenedEvent: SharedFlow<Long> = _doorOpenedEvent.asSharedFlow()

    data class DoorEvent(
        val openedAt: Long,
        val closedAt: Long,
    )

    fun startDoorMonitoring() {
        if (doorDetGpio != null) return
        try {
            doorDetGpio = gpioManager.getGPIO(QZhengGPIOManager.GPIO_DOOR_DET1)
            lastDoorOpen = doorDetGpio?.getValue() == QZhengGPIOManager.GPIO_VALUE_HIGH
            doorOpenedAt = null
            doorDetGpio?.startListening(object : QZGpio.GPIOListener {
                override fun onNewValue(value: Int) {
                    scheduleDoorStateCheck()
                }
            })
            Log.i(TAG, "Door monitoring started on GPIO_DOOR_DET1, initialOpen=$lastDoorOpen")
        } catch (e: Exception) {
            doorDetGpio = null
            Log.e(TAG, "Failed to start door monitoring", e)
        }
    }

    private fun scheduleDoorStateCheck() {
        doorDebounceJob?.cancel()
        doorDebounceJob = managerScope.launch {
            delay(300)
            val isOpen = try {
                doorDetGpio?.getValue() == QZhengGPIOManager.GPIO_VALUE_HIGH
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read debounced door state", e)
                return@launch
            }

            val previous = lastDoorOpen
            if (previous == null || previous == isOpen) return@launch

            val now = System.currentTimeMillis()
            lastDoorOpen = isOpen
            if (isOpen) {
                doorOpenedAt = now
                _doorOpenedEvent.emit(now)
                Log.i(TAG, "Physical door opened at $now")
            } else {
                val openedAt = doorOpenedAt
                doorOpenedAt = null
                if (openedAt != null) {
                    _doorEvent.emit(DoorEvent(openedAt = openedAt, closedAt = now))
                    Log.i(TAG, "Physical door closed at $now, event emitted")
                }
            }
        }
    }

    fun stopDoorMonitoring() {
        doorDebounceJob?.cancel()
        doorDebounceJob = null
        try {
            doorDetGpio?.stopListening()
            Log.i(TAG, "Door monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop door monitoring", e)
        } finally {
            doorDetGpio = null
            lastDoorOpen = null
            doorOpenedAt = null
        }
    }

    fun isDoorOpen(): Boolean {
        return try {
            val value = gpioManager.getGPIO(QZhengGPIOManager.GPIO_DOOR_DET1).getValue()
            value == QZhengGPIOManager.GPIO_VALUE_HIGH
        } catch (e: Exception) {
            Log.e(TAG, "isDoorOpen failed", e)
            false
        }
    }

    // ── 蜂鸣器（GPIO_ID_BELL）────────────────────────────────

    fun buzzerOn(): Boolean {
        return try {
            gpioManager.getGPIO(QZhengGPIOManager.GPIO_ID_BELL)
                .setValue(QZhengGPIOManager.GPIO_VALUE_HIGH)
            Log.i(TAG, "Buzzer ON")
            true
        } catch (e: Exception) {
            Log.e(TAG, "buzzerOn failed", e)
            false
        }
    }

    fun buzzerOff(): Boolean {
        return try {
            gpioManager.getGPIO(QZhengGPIOManager.GPIO_ID_BELL)
                .setValue(QZhengGPIOManager.GPIO_VALUE_LOW)
            Log.i(TAG, "Buzzer OFF")
            true
        } catch (e: Exception) {
            Log.e(TAG, "buzzerOff failed", e)
            false
        }
    }

    // ── 灯光（GPIO_ID_LED_B 白光补光灯）─────────────────────

    @Volatile
    private var fillLightEnabled = false

    fun isFillLightOn(): Boolean = fillLightEnabled

    fun lightOn(): Boolean {
        return setFillLight(enabled = true)
    }

    fun lightOff(): Boolean {
        return setFillLight(enabled = false)
    }

    private fun setFillLight(enabled: Boolean): Boolean {
        return try {
            // getGPIO() creates a Handler bound to the calling thread. Camera analysis
            // threads have no Looper, so use the manager's thread-independent API.
            val value = if (enabled) {
                QZhengGPIOManager.GPIO_VALUE_HIGH
            } else {
                QZhengGPIOManager.GPIO_VALUE_LOW
            }
            val success = gpioManager.setValue(QZhengGPIOManager.GPIO_ID_LED_B, value)
            if (success) {
                fillLightEnabled = enabled
                Log.i(TAG, "Light ${if (enabled) "ON" else "OFF"}")
            } else {
                Log.e(TAG, "Light ${if (enabled) "ON" else "OFF"} rejected by GPIO manager")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "light${if (enabled) "On" else "Off"} failed", e)
            false
        }
    }

    // ── 自动补光（环境亮度检测） ──────────────────────────────

    /** 全局自动补光控制器（懒创建）。 */
    val ambientLightController: AmbientLightController by lazy {
        AmbientLightController(this)
    }

    // ── 看门狗 ─────────────────────────────────────────────

    fun enableWatchdog(): Boolean {
        return try {
            ifManager.enableWatchdog()
            Log.i(TAG, "Watchdog enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enableWatchdog failed", e)
            false
        }
    }

    fun feedWatchdog(): Boolean {
        return try {
            ifManager.feedWatchdog()
            Log.d(TAG, "Watchdog fed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "feedWatchdog failed", e)
            false
        }
    }

    fun disableWatchdog(): Boolean {
        return try {
            ifManager.disableWatchdog()
            Log.i(TAG, "Watchdog disabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "disableWatchdog failed", e)
            false
        }
    }

    // ── 应用监控 ─────────────────────────────────────────────

    /**
     * 设置应用监控：开机自启 + 前台保活。
     * @param packageName 要监控的包名
     * @param keepAliveSeconds 前台监控周期（秒），最小 15 秒，0 表示不监控前台
     */
    fun setAppMonitor(packageName: String, keepAliveSeconds: Int = 60): Boolean {
        return try {
            val result = ifManager.setMonitorApp(packageName, true, keepAliveSeconds)
            Log.i(TAG, "setMonitorApp pkg=$packageName, seconds=$keepAliveSeconds, result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "setAppMonitor failed", e)
            false
        }
    }

    // ── 设备信息 ─────────────────────────────────────────────

    fun getDeviceNumber(): String {
        return try {
            ifManager.deviceNumber ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceNumber failed", e)
            "unknown"
        }
    }

    // ── 系统控制 ─────────────────────────────────────────────

    fun shutdown() {
        try {
            ifManager.shutdown(false, null, false)
        } catch (e: Exception) {
            Log.e(TAG, "shutdown failed", e)
        }
    }

    fun reboot() {
        try {
            ifManager.reboot(false, null, false)
        } catch (e: Exception) {
            Log.e(TAG, "reboot failed", e)
        }
    }

    // ── 安全传感器（烟感 / 防拆）────────────────────────────────
    // TODO: 新 SDK 中未定义烟感/防拆 GPIO 映射，待确认硬件接线后实现。

    private val _safetyEvent = MutableStateFlow<SafetyEvent?>(null)
    val safetyEvent: StateFlow<SafetyEvent?> = _safetyEvent.asStateFlow()

    data class SafetyEvent(
        val type: SafetyEventType,
        val triggered: Boolean,
        val timestamp: Long,
    )

    enum class SafetyEventType { SMOKE, TAMPER }

    fun startSafetyMonitoring() {
        Log.w(TAG, "Safety monitoring not yet implemented for new SDK")
    }

    fun stopSafetyMonitoring() {
        Log.w(TAG, "Safety monitoring stop (not implemented)")
    }

    private fun readSysFile(path: String): String? {
        return try {
            File(path).bufferedReader().use { reader -> reader.readLine()?.trim() }
        } catch (_: Exception) {
            null
        }
    }

    private fun thermalZoneType(path: String): String? {
        val tempFile = File(path)
        if (tempFile.name != "temp") return null
        val parent = tempFile.parentFile ?: return null
        if (!parent.name.startsWith("thermal_zone")) return null
        return readSysFile(File(parent, "type").path)
    }

}
