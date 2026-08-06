package com.foodfridge.data.hardware

import android.content.Context
import android.os.SystemClock
import com.foodfridge.data.local.TemperaturePreferenceSnapshot
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.data.remote.device.dto.TemperatureUploadData
import com.foodfridge.domain.model.TemperatureRecord
import com.foodfridge.domain.repository.DeviceUploadRepository
import com.foodfridge.domain.repository.TemperatureRepository
import com.foodfridge.utils.DeviceInfoProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class TemperatureMonitorState(
    val reading: TemperatureReading? = null,
    val source: RefrigeratorTemperatureSource? = null,
    val isAlarm: Boolean = false,
    val showAlarmDialog: Boolean = false,
    val isSensorFault: Boolean = false,
)

enum class RefrigeratorTemperatureSource(
    val displayName: String,
    val staleAfterMs: Long,
) {
    MODBUS("Modbus", 130_000L),
    CONFIGURED_SYSFS("外部 sysfs", 15_000L),
}

private data class ModbusAcquisitionConfig(
    val enabled: Boolean,
    val readerConfig: ModbusTemperatureConfig,
)

private data class SysfsAcquisitionConfig(
    val path: String?,
    val scale: Int,
)

private data class TemperatureAcquisitionConfig(
    val modbus: ModbusAcquisitionConfig,
    val sysfs: SysfsAcquisitionConfig,
)

internal data class TemperatureAlarmEpisodeState(
    val isActive: Boolean = false,
    val isAcknowledged: Boolean = false,
) {
    val shouldShowDialog: Boolean
        get() = isActive && !isAcknowledged

    fun update(alarmActive: Boolean): TemperatureAlarmEpisodeState {
        if (!alarmActive) return TemperatureAlarmEpisodeState()
        return if (isActive) this else TemperatureAlarmEpisodeState(isActive = true)
    }

    fun acknowledge(): TemperatureAlarmEpisodeState {
        return if (isActive) copy(isAcknowledged = true) else this
    }
}

@Singleton
class TemperatureMonitor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val hardwareManager: HardwareManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val temperatureRepository: TemperatureRepository,
    private val deviceUploadRepository: DeviceUploadRepository,
) {
    companion object {
        private const val TAG = "TemperatureMonitor"
        private const val QUERY_INTERVAL_MS = 5_000L
        private const val STORE_INTERVAL_MS = 60_000L
        private const val UPLOAD_INTERVAL_MS = 5L * 60 * 1000
        private const val STALE_CHECK_INTERVAL_MS = 5_000L
        private const val ALARM_LOW_CELSIUS = 0f
        private const val ALARM_HIGH_CELSIUS = 8f
        private const val ALARM_HYSTERESIS_CELSIUS = 0.3f
        private const val MAX_SINGLE_READING_JUMP_CELSIUS = 10f
        private const val JUMP_CONFIRMATION_TOLERANCE_CELSIUS = 2f
    }

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val processingMutex = Mutex()
    private val _state = MutableStateFlow(TemperatureMonitorState())
    val state: StateFlow<TemperatureMonitorState> = _state.asStateFlow()

    private val deviceId by lazy { DeviceInfoProvider.getDeviceNumber(appContext) }
    private var lastStoredElapsedAt = 0L
    private var lastUploadedElapsedAt = 0L
    private var alarmEpisode = TemperatureAlarmEpisodeState()
    private var pendingLargeChange: Pair<RefrigeratorTemperatureSource, TemperatureReading>? = null

    fun start() {
        if (started.getAndSet(true)) return

        observeConfiguration()
        observeModbusReadings()
        observeConfiguredSysfsReadings()
        startStaleReadingCheck()
        Timber.tag(TAG).i("Process-level temperature monitor started")
    }

    private fun observeConfiguration() {
        monitorScope.launch {
            userPreferencesRepository.temperaturePreferenceSnapshot
                .map { preferences ->
                    TemperatureAcquisitionConfig(
                        modbus = ModbusAcquisitionConfig(
                            enabled = preferences.modbusEnabled,
                            readerConfig = buildModbusTemperatureConfig(
                                preferences = preferences,
                                fallbackDevicePath = hardwareManager.preferredModbusPorts().first(),
                            ),
                        ),
                        sysfs = SysfsAcquisitionConfig(
                            path = preferences.thermalZoneOverride?.takeIf { it.isNotBlank() },
                            scale = preferences.thermalZoneScale,
                        ),
                    )
                }
                .distinctUntilChanged()
                .collect(::applyConfiguration)
        }
    }

    private fun applyConfiguration(config: TemperatureAcquisitionConfig) {
        if (config.modbus.enabled) {
            runCatching {
                hardwareManager.startModbusTemperatureReading(
                    config = config.modbus.readerConfig,
                    intervalMs = QUERY_INTERVAL_MS,
                )
            }.onSuccess {
                Timber.tag(TAG).i(
                    "Applied Modbus config: ${config.modbus.readerConfig}",
                )
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Failed to apply Modbus temperature config")
            }
        } else {
            hardwareManager.stopModbusTemperatureReading()
            monitorScope.launch {
                clearSource(
                    RefrigeratorTemperatureSource.MODBUS,
                    "Modbus 温度采集已停用",
                    sensorFault = false,
                )
            }
            Timber.tag(TAG).i("Modbus temperature acquisition disabled")
        }
        hardwareManager.startTemperatureReading(config.sysfs.path, config.sysfs.scale)
    }

    private fun observeModbusReadings() {
        monitorScope.launch {
            hardwareManager.modbusReadings.collect { reading ->
                if (reading == null) {
                    Timber.tag(TAG).w("Modbus 本次读取失败，保留未超时的上一条有效温度")
                } else {
                    processReading(reading, RefrigeratorTemperatureSource.MODBUS)
                }
            }
        }
    }

    private fun observeConfiguredSysfsReadings() {
        monitorScope.launch {
            hardwareManager.temperatureReadings.collect { reading ->
                if (reading == null) {
                    clearSource(
                        RefrigeratorTemperatureSource.CONFIGURED_SYSFS,
                        "外部 sysfs 温度源失效",
                    )
                } else if (!isModbusAcquisitionActive()) {
                    processReading(reading, RefrigeratorTemperatureSource.CONFIGURED_SYSFS)
                }
            }
        }
    }

    private fun isModbusAcquisitionActive(): Boolean =
        when (hardwareManager.modbusConnectionState.value) {
            ModbusTemperatureReader.ConnectionState.Connecting,
            ModbusTemperatureReader.ConnectionState.WaitingForResponse,
            ModbusTemperatureReader.ConnectionState.Validating,
            ModbusTemperatureReader.ConnectionState.Connected -> true
            is ModbusTemperatureReader.ConnectionState.Disconnected,
            is ModbusTemperatureReader.ConnectionState.Error -> false
        }

    private suspend fun processReading(
        reading: TemperatureReading,
        source: RefrigeratorTemperatureSource,
    ) {
        var uploadData: TemperatureUploadData? = null
        processingMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            if (!isTemperatureTimestampAcceptable(reading.recordedAt, now, source.staleAfterMs)) {
                Timber.tag(TAG).w(
                    "Rejected stale/future ${source.displayName} reading: " +
                        "recordedAt=${reading.recordedAt}, now=$now",
                )
                return@withLock
            }

            if (source == RefrigeratorTemperatureSource.CONFIGURED_SYSFS &&
                isModbusAcquisitionActive()
            ) return@withLock

            val current = _state.value
            if (source == RefrigeratorTemperatureSource.CONFIGURED_SYSFS &&
                current.source == source && current.reading != null &&
                kotlin.math.abs(current.reading.celsius - reading.celsius) >
                MAX_SINGLE_READING_JUMP_CELSIUS
            ) {
                val pending = pendingLargeChange
                if (pending?.first != source ||
                    kotlin.math.abs(pending.second.celsius - reading.celsius) >
                    JUMP_CONFIRMATION_TOLERANCE_CELSIUS
                ) {
                    pendingLargeChange = source to reading
                    Timber.tag(TAG).w(
                        "Ignored unconfirmed sysfs jump: ${current.reading.celsius}°C -> " +
                            "${reading.celsius}°C",
                    )
                    return@withLock
                }
            }
            pendingLargeChange = null

            if (isPeriodicActionDue(lastStoredElapsedAt, elapsedNow, STORE_INTERVAL_MS)) {
                runCatching {
                    temperatureRepository.insertTemperature(
                        TemperatureRecord(
                            id = 0,
                            temperature = reading.celsius,
                            recordedAt = reading.recordedAt,
                        )
                    )
                }.onSuccess {
                    lastStoredElapsedAt = elapsedNow
                }.onFailure { error ->
                    Timber.tag(TAG).e(error, "Failed to persist temperature reading")
                }
            }

            val alarm = temperatureAlarmState(
                temperature = reading.celsius,
                wasAlarmActive = alarmEpisode.isActive,
                enabled = true,
                low = ALARM_LOW_CELSIUS,
                high = ALARM_HIGH_CELSIUS,
                hysteresis = ALARM_HYSTERESIS_CELSIUS,
            )
            updateAlarmLocked(alarm, reading.celsius)
            _state.value = TemperatureMonitorState(
                reading = reading,
                source = source,
                isAlarm = alarmEpisode.isActive,
                showAlarmDialog = alarmEpisode.shouldShowDialog,
                isSensorFault = false,
            )

            if (isPeriodicActionDue(lastUploadedElapsedAt, elapsedNow, UPLOAD_INTERVAL_MS)) {
                lastUploadedElapsedAt = elapsedNow
                uploadData = TemperatureUploadData(
                    device_id = deviceId,
                    timestamp = reading.recordedAt,
                    temperature = reading.celsius,
                )
            }
            Timber.tag(TAG).d("Accepted ${source.displayName} temperature: ${reading.celsius}°C")
        }

        uploadData?.let { data ->
            monitorScope.launch { uploadTemperature(data) }
        }
    }

    private suspend fun clearSource(
        source: RefrigeratorTemperatureSource,
        reason: String,
        sensorFault: Boolean = true,
    ) {
        processingMutex.withLock {
            if (_state.value.source != source) return@withLock
            pendingLargeChange = null
            val retainActiveAlarm = sensorFault && alarmEpisode.isActive
            if (!retainActiveAlarm) {
                updateAlarmLocked(false, _state.value.reading?.celsius ?: 0f)
            }
            _state.value = TemperatureMonitorState(
                source = source.takeIf { sensorFault },
                isAlarm = retainActiveAlarm,
                showAlarmDialog = retainActiveAlarm && alarmEpisode.shouldShowDialog,
                isSensorFault = sensorFault,
            )
            Timber.tag(TAG).w("$reason; cleared current refrigerator temperature")
        }
    }

    private fun startStaleReadingCheck() {
        monitorScope.launch {
            while (isActive) {
                delay(STALE_CHECK_INTERVAL_MS)
                val current = _state.value
                val source = current.source ?: continue
                val recordedAt = current.reading?.recordedAt ?: continue
                if (!isTemperatureTimestampAcceptable(
                        recordedAt = recordedAt,
                        now = System.currentTimeMillis(),
                        staleAfterMs = source.staleAfterMs,
                    )
                ) {
                    clearSource(source, "${source.displayName} 温度已超时")
                }
            }
        }
    }

    suspend fun dismissAlarmDialog() {
        processingMutex.withLock {
            val acknowledgedEpisode = alarmEpisode.acknowledge()
            if (acknowledgedEpisode == alarmEpisode) return@withLock

            alarmEpisode = acknowledgedEpisode
            _state.value = _state.value.copy(showAlarmDialog = false)
            Timber.tag(TAG).i("Temperature alarm dialog dismissed")
        }
    }

    private fun updateAlarmLocked(alarm: Boolean, temperature: Float) {
        val previousEpisode = alarmEpisode
        val nextEpisode = previousEpisode.update(alarm)
        if (nextEpisode == previousEpisode) return

        alarmEpisode = nextEpisode
        if (nextEpisode.isActive) {
            Timber.tag(TAG).w("Temperature alarm triggered: $temperature°C")
        } else {
            Timber.tag(TAG).i("Temperature alarm cleared: $temperature°C")
        }
    }

    private suspend fun uploadTemperature(data: TemperatureUploadData) {
        deviceUploadRepository.uploadTemperature(data).fold(
            onSuccess = { response ->
                Timber.tag(TAG).i(
                    "Temperature upload succeeded: code=${response.code}, message=${response.message}",
                )
            },
            onFailure = { error ->
                Timber.tag(TAG).e(error, "Temperature upload failed and was queued for retry")
            },
        )
    }
}

internal fun buildModbusTemperatureConfig(
    preferences: TemperaturePreferenceSnapshot,
    fallbackDevicePath: String,
): ModbusTemperatureConfig {
    return ModbusTemperatureConfig(
        devicePath = preferences.modbusDevicePath
            ?.takeIf { it.isNotBlank() }
            ?: fallbackDevicePath,
        baudRate = preferences.modbusBaudRate,
        parity = preferences.modbusParity,
        stopBits = preferences.modbusStopBits,
        slaveAddress = preferences.modbusSlaveAddress,
        functionCode = preferences.modbusFunctionCode,
        registerAddress = preferences.modbusRegisterAddress,
        registerCount = preferences.modbusRegisterCount,
        temperatureRegisterOffset = preferences.modbusTemperatureRegisterOffset,
        temperatureScale = preferences.modbusTemperatureScale,
        calibrationOffset = preferences.modbusCalibrationOffset,
        valueType = preferences.modbusValueType,
        byteOrder = preferences.modbusByteOrder,
        wordOrder = preferences.modbusWordOrder,
        valueMode = preferences.modbusValueMode,
    )
}

internal fun isTemperatureTimestampAcceptable(
    recordedAt: Long,
    now: Long,
    staleAfterMs: Long,
): Boolean {
    if (staleAfterMs <= 0L) return false
    val age = now - recordedAt
    return age in -5_000L..staleAfterMs
}

internal fun isPeriodicActionDue(lastElapsedAt: Long, nowElapsed: Long, intervalMs: Long): Boolean {
    if (intervalMs <= 0L || nowElapsed < 0L) return false
    return lastElapsedAt <= 0L || nowElapsed < lastElapsedAt || nowElapsed - lastElapsedAt >= intervalMs
}

internal fun temperatureAlarmState(
    temperature: Float,
    wasAlarmActive: Boolean,
    enabled: Boolean,
    low: Float,
    high: Float,
    hysteresis: Float,
): Boolean {
    if (!enabled || !temperature.isFinite() || low >= high || hysteresis < 0f) return false
    if (!wasAlarmActive) return temperature < low || temperature > high
    return temperature < low + hysteresis || temperature > high - hysteresis
}
