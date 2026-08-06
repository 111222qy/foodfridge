package com.foodfridge.data.hardware

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.serialport.SerialPort
import com.foodfridge.utils.Pt100Converter
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import timber.log.Timber

data class TemperatureReading(
    val celsius: Float,
    val recordedAt: Long,
)

enum class ModbusValueType(val registerWidth: Int) {
    INT16(1),
    UINT16(1),
    INT32(2),
    UINT32(2),
    FLOAT32(2),
}

enum class ModbusByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN,
}

enum class ModbusWordOrder {
    HIGH_WORD_FIRST,
    LOW_WORD_FIRST,
}

enum class ModbusTemperatureValueMode {
    DIRECT_CELSIUS,
    PT100_RESISTANCE,
    PT1000_RESISTANCE,
}

data class ModbusTemperatureConfig(
    val devicePath: String,
    val baudRate: Int = 115200,
    val parity: Int = 0,
    val stopBits: Int = 1,
    val slaveAddress: Int = 0xFF,
    val functionCode: Int = 0x03,
    val registerAddress: Int = 0x0000,
    val registerCount: Int = 2,
    val temperatureRegisterOffset: Int = 1,
    val temperatureScale: Float = 0.1f,
    val calibrationOffset: Float = 0f,
    val valueType: ModbusValueType = ModbusValueType.INT16,
    val byteOrder: ModbusByteOrder = ModbusByteOrder.BIG_ENDIAN,
    val wordOrder: ModbusWordOrder = ModbusWordOrder.HIGH_WORD_FIRST,
    val valueMode: ModbusTemperatureValueMode = ModbusTemperatureValueMode.DIRECT_CELSIUS,
) {
    init {
        require(devicePath.isNotBlank()) { "devicePath must not be blank" }
        require(baudRate > 0) { "baudRate must be positive" }
        require(parity in 0..2) { "parity must be 0 (none), 1 (odd), or 2 (even)" }
        require(stopBits == 1 || stopBits == 2) { "stopBits must be 1 or 2" }
        require(slaveAddress in 1..0xFF) { "slaveAddress must be in 1..255" }
        require(functionCode == 0x03 || functionCode == 0x04) {
            "functionCode must be 3 or 4"
        }
        require(registerAddress in 0..0xFFFF) { "registerAddress must be in 0..65535" }
        require(registerCount in 1..125) { "registerCount must be in 1..125" }
        require(registerAddress.toLong() + registerCount <= 0x10000L) {
            "register range must not extend beyond 65535"
        }
        require(
            temperatureRegisterOffset >= 0 &&
                temperatureRegisterOffset.toLong() + valueType.registerWidth <= registerCount.toLong()
        ) {
            "temperature value must fit inside requested registers"
        }
        require(temperatureScale.isFinite() && temperatureScale > 0f) {
            "temperatureScale must be finite and positive"
        }
        require(calibrationOffset.isFinite()) { "calibrationOffset must be finite" }
    }
}

enum class ModbusProbeStatus {
    SUCCESS,
    PORT_NOT_FOUND,
    PORT_BUSY,
    PORT_PERMISSION_DENIED,
    OPEN_FAILED,
    WRITE_FAILED,
    NO_BYTES,
    INVALID_FRAME,
    ADDRESS_MISMATCH,
    FUNCTION_MISMATCH,
    MODBUS_EXCEPTION,
    INVALID_PAYLOAD,
}

data class ModbusProbeResult(
    val status: ModbusProbeStatus,
    val message: String,
    val requestHex: String? = null,
    val rawResponseHex: String? = null,
    val responseFrameHex: String? = null,
    val registerValues: List<Int> = emptyList(),
    val temperature: Float? = null,
    val exceptionCode: Int? = null,
    val responseAddress: Int? = null,
    val responseFunctionCode: Int? = null,
    val attemptedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
) {
    val isSuccess: Boolean
        get() = status == ModbusProbeStatus.SUCCESS

    val hasValidFrame: Boolean
        get() = responseFrameHex != null

    val hasRegisterData: Boolean
        get() = registerValues.isNotEmpty()
}

/**
 * Modbus RTU 温度读取器
 *
 * 通过 RS-485 串口以 Modbus RTU 协议从外部温控控制器读取温度。
 *
 * 协议格式：
 * - 查询：从机地址(1) + 功能码0x03(1) + 起始寄存器(2) + 寄存器数量(2) + CRC16(2)
 * - 响应：从机地址(1) + 功能码0x03(1) + 字节数(1) + 湿度(2) + 温度(2) + CRC16(2)
 * - 温度 = 有符号大端整数 / 10.0
 */
class ModbusTemperatureReader(
    private val config: ModbusTemperatureConfig,
    private val context: Context? = null,
) {
    constructor(
        devicePath: String,
        baudRate: Int = 115200,
        parity: Int = 0,
        stopBits: Int = 1,
        slaveAddress: Int = 0xFF,
        context: Context? = null,
        functionCode: Int = 0x03,
        registerAddress: Int = 0x0000,
        registerCount: Int = 2,
        temperatureRegisterOffset: Int = 1,
        temperatureScale: Float = 0.1f,
        calibrationOffset: Float = 0f,
        valueType: ModbusValueType = ModbusValueType.INT16,
        byteOrder: ModbusByteOrder = ModbusByteOrder.BIG_ENDIAN,
        wordOrder: ModbusWordOrder = ModbusWordOrder.HIGH_WORD_FIRST,
        valueMode: ModbusTemperatureValueMode = ModbusTemperatureValueMode.DIRECT_CELSIUS,
    ) : this(
        config = ModbusTemperatureConfig(
            devicePath = devicePath,
            baudRate = baudRate,
            parity = parity,
            stopBits = stopBits,
            slaveAddress = slaveAddress,
            functionCode = functionCode,
            registerAddress = registerAddress,
            registerCount = registerCount,
            temperatureRegisterOffset = temperatureRegisterOffset,
            temperatureScale = temperatureScale,
            calibrationOffset = calibrationOffset,
            valueType = valueType,
            byteOrder = byteOrder,
            wordOrder = wordOrder,
            valueMode = valueMode,
        ),
        context = context,
    )

    companion object {
        private const val TAG = "ModbusTemperatureReader"

        // 超时
        private const val READ_TIMEOUT_MS = 2000L
        private const val QUERY_INTERVAL_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val MAX_RESPONSE_BUFFER_BYTES = 512
        private const val MAX_DRAIN_BYTES = 512
        private const val MAX_DRAIN_TIME_MS = 50L
        private const val CONFIRMATION_INTERVAL_MS = 2_000L
        private const val MAX_CONFIRMATION_DELTA_CELSIUS = 5f
        private const val MAX_RUNTIME_JUMP_CELSIUS = 10f
        private const val RUNTIME_CONFIRMATION_TOLERANCE_CELSIUS = 2f

        // 串口参数
        private const val DATA_BITS = 8
        private const val MIN_VALID_TEMPERATURE = -50f
        private const val MAX_VALID_TEMPERATURE = 80f
    }

    private val devicePath: String get() = config.devicePath
    private val baudRate: Int get() = config.baudRate
    private val parity: Int get() = config.parity
    private val stopBits: Int get() = config.stopBits
    private val slaveAddress: Int get() = config.slaveAddress
    private val functionCode: Int get() = config.functionCode
    private val registerAddress: Int get() = config.registerAddress
    private val registerCount: Int get() = config.registerCount
    private val temperatureRegisterOffset: Int get() = config.temperatureRegisterOffset
    private val temperatureScale: Float get() = config.temperatureScale

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val connectionLock = Any()

    @Volatile
    private var destroyed = false

    /**
     * 释放资源，取消 scope。应在不再需要时调用。
     */
    @Synchronized
    fun destroy() {
        destroyed = true
        stopReading()
        readerScope.cancel()
        Timber.tag(TAG).i("ModbusTemperatureReader destroyed")
    }

    private var jniSerialPort: SerialPort? = null
    private var fileInputStream: java.io.InputStream? = null
    private var fileOutputStream: java.io.OutputStream? = null
    private var usbPort: UsbSerialPort? = null
    private var serialPortLease: SerialPortLeaseRegistry.Lease? = null
    private var isConnected = false
    private var isUsbMode = false

    private val _temperature = MutableStateFlow<Float?>(null)
    val temperature: StateFlow<Float?> = _temperature.asStateFlow()

    private val _readings = MutableStateFlow<TemperatureReading?>(null)
    val readings: StateFlow<TemperatureReading?> = _readings.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastProbeResult = MutableStateFlow<ModbusProbeResult?>(null)
    val lastProbeResult: StateFlow<ModbusProbeResult?> = _lastProbeResult.asStateFlow()

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object WaitingForResponse : ConnectionState()
        object Validating : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    /**
     * 启动 Modbus 温度读取
     */
    @Synchronized
    fun startReading(intervalMs: Long = QUERY_INTERVAL_MS) {
        require(intervalMs > 0) { "intervalMs must be positive" }
        check(!destroyed) { "ModbusTemperatureReader has been destroyed" }
        if (isRunning.getAndSet(true)) {
            Timber.tag(TAG).w("Modbus reading already started")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        readJob = readerScope.launch {
            var consecutiveFailures = 0
            var pendingConfirmation: Float? = null
            var connectionValidated = false
            var lastPublishedTemperature: Float? = null
            try {
                while (isActive && isRunning.get()) {
                    var nextDelay = intervalMs
                    try {
                        if (!isConnected) {
                            _connectionState.value = ConnectionState.Connecting
                            connect()
                        }

                        if (isConnected) {
                            val probeResult = executeQuery()
                            val temp = probeResult.temperature
                            if (probeResult.isSuccess && temp != null) {
                                consecutiveFailures = 0
                                if (!connectionValidated) {
                                    val previous = pendingConfirmation
                                    if (previous == null || abs(previous - temp) > MAX_CONFIRMATION_DELTA_CELSIUS) {
                                        pendingConfirmation = temp
                                        nextDelay = minOf(intervalMs, CONFIRMATION_INTERVAL_MS)
                                        _connectionState.value = ConnectionState.Validating
                                        Timber.tag(TAG).i("Valid temperature received; waiting for confirmation: $temp°C")
                                    } else {
                                        connectionValidated = true
                                        pendingConfirmation = null
                                        publishTemperature(temp)
                                        lastPublishedTemperature = temp
                                    }
                                } else if (requiresTemperatureJumpConfirmation(
                                        previous = lastPublishedTemperature,
                                        current = temp,
                                        threshold = MAX_RUNTIME_JUMP_CELSIUS,
                                    )
                                ) {
                                    val pending = pendingConfirmation
                                    if (!isTemperatureConfirmationMatch(
                                            pending = pending,
                                            current = temp,
                                            tolerance = RUNTIME_CONFIRMATION_TOLERANCE_CELSIUS,
                                        )
                                    ) {
                                        pendingConfirmation = temp
                                        nextDelay = minOf(intervalMs, CONFIRMATION_INTERVAL_MS)
                                        _connectionState.value = ConnectionState.Validating
                                        Timber.tag(TAG).w(
                                            "Large temperature jump requires confirmation: " +
                                                "$lastPublishedTemperature°C -> $temp°C",
                                        )
                                    } else {
                                        pendingConfirmation = null
                                        publishTemperature(temp)
                                        lastPublishedTemperature = temp
                                    }
                                } else {
                                    pendingConfirmation = null
                                    publishTemperature(temp)
                                    lastPublishedTemperature = temp
                                }
                            } else {
                                Timber.tag(TAG).w("Temperature probe failed: ${probeResult.message}")
                                disconnect()
                                _connectionState.value = ConnectionState.Error(probeResult.message)
                                pendingConfirmation = null
                                connectionValidated = false
                                consecutiveFailures++
                                nextDelay = minOf(
                                    intervalMs,
                                    reconnectDelayMs(
                                        consecutiveFailures,
                                        RECONNECT_DELAY_MS,
                                        MAX_RECONNECT_DELAY_MS,
                                    ),
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Modbus read error")
                        _lastProbeResult.value = connectionFailureResult(e)
                        disconnect()
                        _connectionState.value = ConnectionState.Error(e.message ?: "未知错误")
                        pendingConfirmation = null
                        connectionValidated = false
                        consecutiveFailures++
                        nextDelay = minOf(
                            intervalMs,
                            reconnectDelayMs(
                                consecutiveFailures,
                                RECONNECT_DELAY_MS,
                                MAX_RECONNECT_DELAY_MS,
                            ),
                        )
                    }
                    if (consecutiveFailures > 0) {
                        Timber.tag(TAG).i("Next Modbus retry in ${nextDelay}ms")
                    }
                    delay(nextDelay)
                }
            } finally {
                disconnect()
            }
        }
        Timber.tag(TAG).i("Modbus temperature reading started on $devicePath @ $baudRate")
    }

    suspend fun probeOnce(
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): ModbusProbeResult = withContext(Dispatchers.IO) {
        require(readTimeoutMs > 0L) { "readTimeoutMs must be positive" }
        check(!destroyed) { "ModbusTemperatureReader has been destroyed" }
        check(!isRunning.get()) { "probeOnce cannot run while continuous reading is active" }
        try {
            _connectionState.value = ConnectionState.Connecting
            connect(allowOneShot = true)
            executeQuery(readTimeoutMs).also { result ->
                _connectionState.value = if (result.isSuccess) {
                    ConnectionState.Connected
                } else {
                    ConnectionState.Error(result.message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connectionFailureResult(e).also { result ->
                _lastProbeResult.value = result
                _connectionState.value = ConnectionState.Error(result.message)
            }
        } finally {
            disconnect()
        }
    }

    private fun publishTemperature(temp: Float) {
        _temperature.value = temp
        _readings.value = TemperatureReading(
            celsius = temp,
            recordedAt = System.currentTimeMillis(),
        )
        _connectionState.value = ConnectionState.Connected
        Timber.tag(TAG).d("Temperature read: $temp°C")
    }

    /**
     * 停止读取
     */
    @Synchronized
    fun stopReading() {
        isRunning.set(false)
        readJob?.cancel()
        readJob = null
        disconnect()
        Timber.tag(TAG).i("Modbus temperature reading stopped")
    }

    private fun connect(allowOneShot: Boolean = false) = synchronized(connectionLock) {
        ensureConnectAllowed(allowOneShot)
        try {
            if (devicePath.startsWith("usb://")) {
                connectUsb()
            } else {
                connectJni()
            }
            ensureConnectAllowed(allowOneShot)
        } catch (e: CancellationException) {
            disconnectLocked()
            throw e
        } catch (e: Exception) {
            disconnectLocked()
            Timber.tag(TAG).e(e, "Failed to connect to $devicePath")
            _connectionState.value = ConnectionState.Error("连接失败: ${e.message}")
            throw e
        }
    }

    private fun ensureConnectAllowed(allowOneShot: Boolean) {
        if (destroyed || (!allowOneShot && !isRunning.get())) {
            throw CancellationException("Modbus connection was stopped")
        }
    }

    private fun connectJni() {
        val device = File(devicePath)
        if (!device.exists()) {
            throw IllegalStateException("Device not found: $devicePath")
        }
        if (!device.canRead() || !device.canWrite()) {
            throw SecurityException("Serial port is not readable and writable: $devicePath")
        }

        serialPortLease = SerialPortLeaseRegistry.tryAcquire(
            devicePath = devicePath,
            owner = "ModbusTemperatureReader@${System.identityHashCode(this)}",
        ) ?: throw IllegalStateException(
            "Serial port is busy: $devicePath, owner=${SerialPortLeaseRegistry.currentOwner(devicePath)}"
        )

        try {
            jniSerialPort = SerialPort.newBuilder(devicePath, baudRate)
                .dataBits(DATA_BITS)
                .parity(parity)
                .stopBits(stopBits)
                .build()

            fileInputStream = jniSerialPort!!.inputStream
            fileOutputStream = jniSerialPort!!.outputStream
        } catch (e: Exception) {
            serialPortLease?.close()
            serialPortLease = null
            throw e
        }
        isConnected = true
        isUsbMode = false
        _connectionState.value = ConnectionState.WaitingForResponse
        Timber.tag(TAG).i("JNI serial opened; waiting for valid response: $devicePath @ $baudRate")
    }

    private fun connectUsb() {
        val appContext = context ?: throw IllegalStateException("Context required for USB serial")
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            ?: throw IllegalStateException("UsbManager unavailable")
        val targetDeviceName = devicePath.removePrefix("usb://")
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = if (targetDeviceName.isBlank()) {
            drivers.firstOrNull()
        } else {
            drivers.firstOrNull { it.device.deviceName == targetDeviceName }
        } ?: throw IllegalStateException("USB serial device not found: $targetDeviceName")

        if (!usbManager.hasPermission(driver.device)) {
            throw SecurityException("USB permission not granted")
        }

        val leaseKey = "usb://${driver.device.deviceName}"
        serialPortLease = SerialPortLeaseRegistry.tryAcquire(
            devicePath = leaseKey,
            owner = "ModbusTemperatureReader@${System.identityHashCode(this)}",
        ) ?: throw IllegalStateException(
            "Serial port is busy: $leaseKey, owner=${SerialPortLeaseRegistry.currentOwner(leaseKey)}"
        )

        val port = driver.ports.firstOrNull()
            ?: throw IllegalStateException("No serial ports on USB device")
        val connection = usbManager.openDevice(driver.device)
            ?: throw IllegalStateException("Failed to open USB device")
        try {
            port.open(connection)
            port.setParameters(
                baudRate,
                DATA_BITS,
                stopBits,
                when (parity) {
                    1 -> UsbSerialPort.PARITY_ODD
                    2 -> UsbSerialPort.PARITY_EVEN
                    else -> UsbSerialPort.PARITY_NONE
                },
            )
        } catch (e: Exception) {
            runCatching { port.close() }
            runCatching { connection.close() }
            throw e
        }

        usbPort = port
        isConnected = true
        isUsbMode = true
        _connectionState.value = ConnectionState.WaitingForResponse
        Timber.tag(TAG).i("USB serial opened; waiting for valid response: $devicePath @ $baudRate")
    }

    private fun disconnect() = synchronized(connectionLock) {
        disconnectLocked()
    }

    private fun disconnectLocked() {
        try {
            jniSerialPort?.tryCloseSafely(TAG)
        } catch (e: Exception) {
            Log.w(TAG, "Error closing JNI serial", e)
        }
        try {
            usbPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB serial", e)
        }
        jniSerialPort = null
        fileInputStream = null
        fileOutputStream = null
        usbPort = null
        serialPortLease?.close()
        serialPortLease = null
        isConnected = false
        isUsbMode = false
        _temperature.value = null
        _readings.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun executeQuery(
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): ModbusProbeResult = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val attemptedAt = System.currentTimeMillis()
        val queryFrame = buildQueryFrame()
        try {
            drainInput()
            if (!writeFrame(queryFrame)) {
                return@withContext publishProbeResult(
                    ModbusProbeResult(
                        status = ModbusProbeStatus.WRITE_FAILED,
                        message = "串口已打开，但查询帧发送失败",
                        requestHex = queryFrame.toHexString(),
                        attemptedAt = attemptedAt,
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                )
            }

            val readResult = readFrame(readTimeoutMs)
            publishProbeResult(
                analyzeModbusProbeResponse(
                    config = config,
                    requestFrame = queryFrame,
                    rawResponse = readResult.rawBytes,
                    attemptedAt = attemptedAt,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Query temperature error")
            publishProbeResult(
                ModbusProbeResult(
                    status = ModbusProbeStatus.OPEN_FAILED,
                    message = "查询失败: ${e.message ?: e.javaClass.simpleName}",
                    requestHex = queryFrame.toHexString(),
                    attemptedAt = attemptedAt,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                )
            )
        }
    }

    private fun publishProbeResult(result: ModbusProbeResult): ModbusProbeResult {
        _lastProbeResult.value = result
        if (result.isSuccess) {
            Timber.tag(TAG).d(result.message)
        } else {
            Timber.tag(TAG).w(result.message)
        }
        return result
    }

    private fun connectionFailureResult(error: Exception): ModbusProbeResult {
        val status = when {
            error.message?.contains("not found", ignoreCase = true) == true ->
                ModbusProbeStatus.PORT_NOT_FOUND
            error.message?.contains("busy", ignoreCase = true) == true ->
                ModbusProbeStatus.PORT_BUSY
            error is SecurityException -> ModbusProbeStatus.PORT_PERMISSION_DENIED
            else -> ModbusProbeStatus.OPEN_FAILED
        }
        return ModbusProbeResult(
            status = status,
            message = when (status) {
                ModbusProbeStatus.PORT_NOT_FOUND -> "串口不存在: $devicePath"
                ModbusProbeStatus.PORT_BUSY -> "串口正被其他模块占用: $devicePath"
                ModbusProbeStatus.PORT_PERMISSION_DENIED -> "串口无读写权限: $devicePath"
                else -> "串口打开失败: ${error.message ?: error.javaClass.simpleName}"
            },
        )
    }

    internal fun buildQueryFrame(): ByteArray {
        val frame = buildModbusReadRegistersRequest(
            slaveAddress = slaveAddress,
            functionCode = functionCode,
            registerAddress = registerAddress,
            registerCount = registerCount,
        )
        Timber.tag(TAG).d("Query frame: ${frame.toHexString()}")
        return frame
    }

    private suspend fun drainInput() {
        val buffer = ByteArray(256)
        val startedAt = SystemClock.elapsedRealtime()
        var drainedBytes = 0
        if (isUsbMode) {
            while (drainedBytes < MAX_DRAIN_BYTES &&
                SystemClock.elapsedRealtime() - startedAt < MAX_DRAIN_TIME_MS
            ) {
                currentCoroutineContext().ensureActive()
                val count = usbPort?.read(buffer, 10) ?: 0
                if (count <= 0) break
                drainedBytes += count
            }
        } else {
            while (drainedBytes < MAX_DRAIN_BYTES &&
                SystemClock.elapsedRealtime() - startedAt < MAX_DRAIN_TIME_MS
            ) {
                currentCoroutineContext().ensureActive()
                val available = minOf(
                    fileInputStream?.available() ?: 0,
                    buffer.size,
                    MAX_DRAIN_BYTES - drainedBytes,
                )
                if (available <= 0 || (fileInputStream?.read(buffer, 0, available) ?: -1) <= 0) break
                drainedBytes += available
            }
        }
        if (drainedBytes > 0) {
            Timber.tag(TAG).w("Discarded $drainedBytes stale/noise bytes before Modbus query")
        }
    }

    private suspend fun writeFrame(frame: ByteArray): Boolean {
        return try {
            if (isUsbMode && usbPort != null) {
                usbPort?.write(frame, 1_000)
            } else if (fileOutputStream != null) {
                fileOutputStream?.write(frame)
                fileOutputStream?.flush()
            } else {
                return false
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Write frame error")
            false
        }
    }

    private data class FrameReadResult(val rawBytes: ByteArray)

    private suspend fun readFrame(readTimeoutMs: Long = READ_TIMEOUT_MS): FrameReadResult {
        val buffer = ByteArray(256)
        val result = java.io.ByteArrayOutputStream()
        val startTime = SystemClock.elapsedRealtime()

        while (SystemClock.elapsedRealtime() - startTime < readTimeoutMs) {
            val available = if (isUsbMode && usbPort != null) {
                usbPort?.read(buffer, 100) ?: 0
            } else if (fileInputStream != null) {
                val avail = fileInputStream?.available() ?: 0
                if (avail > 0) fileInputStream?.read(buffer, 0, minOf(avail, buffer.size)) ?: 0 else 0
            } else {
                0
            }

            if (available > 0) {
                result.write(buffer, 0, available)
                extractModbusResponseFrame(result.toByteArray(), slaveAddress)?.let { frame ->
                    Timber.tag(TAG).d("Response frame: ${frame.toHexString()}")
                    return FrameReadResult(result.toByteArray())
                }
                if (result.size() > MAX_RESPONSE_BUFFER_BYTES) {
                    val tail = result.toByteArray().takeLast(MAX_RESPONSE_BUFFER_BYTES / 2).toByteArray()
                    result.reset()
                    result.write(tail)
                }
            }
            delay(10)
        }

        val data = result.toByteArray()
        if (data.isNotEmpty()) {
            Timber.tag(TAG).w("Timed out without a valid frame; raw bytes: ${data.toHexString()}")
        }
        return FrameReadResult(data)
    }

}

internal fun requiresTemperatureJumpConfirmation(
    previous: Float?,
    current: Float,
    threshold: Float,
): Boolean {
    if (previous == null || !previous.isFinite() || !current.isFinite() || threshold <= 0f) return false
    return abs(previous - current) > threshold
}

internal fun isTemperatureConfirmationMatch(
    pending: Float?,
    current: Float,
    tolerance: Float,
): Boolean {
    if (pending == null || !pending.isFinite() || !current.isFinite() || tolerance < 0f) return false
    return abs(pending - current) <= tolerance
}

internal fun ByteArray.toHexString(): String =
    joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

/**
 * Modbus RTU CRC-16 校验（多项式 0x8005，初值 0xFFFF，反射 0xA001）。
 * 提取为包级 internal 函数以便单元测试。
 */
internal fun modbusCrc16(
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
): Int {
    require(offset >= 0 && length >= 0 && offset + length <= data.size) {
        "offset and length must describe a valid range"
    }
    var crc = 0xFFFF
    for (i in offset until offset + length) {
        crc = crc xor (data[i].toInt() and 0xFF)
        for (j in 0 until 8) {
            crc = if (crc and 0x0001 != 0) {
                (crc shr 1) xor 0xA001
            } else {
                crc shr 1
            }
        }
    }
    return crc
}

internal fun buildModbusReadHoldingRegistersRequest(
    slaveAddress: Int,
    registerAddress: Int,
    registerCount: Int,
): ByteArray = buildModbusReadRegistersRequest(
    slaveAddress = slaveAddress,
    functionCode = 0x03,
    registerAddress = registerAddress,
    registerCount = registerCount,
)

internal fun buildModbusReadRegistersRequest(
    slaveAddress: Int,
    functionCode: Int,
    registerAddress: Int,
    registerCount: Int,
): ByteArray {
    require(slaveAddress in 1..0xFF)
    require(functionCode == 0x03 || functionCode == 0x04)
    require(registerAddress in 0..0xFFFF)
    require(registerCount in 1..125)
    require(registerAddress.toLong() + registerCount <= 0x10000L)

    val frame = ByteArray(8)
    frame[0] = slaveAddress.toByte()
    frame[1] = functionCode.toByte()
    frame[2] = ((registerAddress shr 8) and 0xFF).toByte()
    frame[3] = (registerAddress and 0xFF).toByte()
    frame[4] = ((registerCount shr 8) and 0xFF).toByte()
    frame[5] = (registerCount and 0xFF).toByte()
    val crc = modbusCrc16(frame, 0, 6)
    frame[6] = (crc and 0xFF).toByte()
    frame[7] = ((crc shr 8) and 0xFF).toByte()
    return frame
}

internal fun extractModbusResponseFrame(
    data: ByteArray,
    slaveAddress: Int,
): ByteArray? {
    if (data.size < 5 || slaveAddress !in 1..0xFF) return null

    for (start in 0..data.size - 5) {
        if ((data[start].toInt() and 0xFF) != slaveAddress) continue

        val functionCode = data[start + 1].toInt() and 0xFF
        val expectedLength = when (functionCode) {
            0x03, 0x04 -> (data[start + 2].toInt() and 0xFF) + 5
            0x83, 0x84 -> 5
            else -> continue
        }
        if (expectedLength !in 5..260 || start + expectedLength > data.size) continue

        val candidate = data.copyOfRange(start, start + expectedLength)
        val receivedCrc = ((candidate[candidate.lastIndex].toInt() and 0xFF) shl 8) or
            (candidate[candidate.lastIndex - 1].toInt() and 0xFF)
        if (receivedCrc == modbusCrc16(candidate, 0, candidate.size - 2)) {
            return candidate
        }
    }
    return null
}

internal fun extractAnyModbusResponseFrame(data: ByteArray): ByteArray? {
    if (data.size < 5) return null
    for (start in 0..data.size - 5) {
        val functionCode = data[start + 1].toInt() and 0xFF
        val expectedLength = when (functionCode) {
            0x03, 0x04 -> (data[start + 2].toInt() and 0xFF) + 5
            0x83, 0x84 -> 5
            else -> continue
        }
        if (expectedLength !in 5..260 || start + expectedLength > data.size) continue

        val candidate = data.copyOfRange(start, start + expectedLength)
        val receivedCrc = ((candidate[candidate.lastIndex].toInt() and 0xFF) shl 8) or
            (candidate[candidate.lastIndex - 1].toInt() and 0xFF)
        if (receivedCrc == modbusCrc16(candidate, 0, candidate.size - 2)) {
            return candidate
        }
    }
    return null
}

private fun extractModbusRegisterValues(frame: ByteArray): List<Int> {
    if (frame.size < 5) return emptyList()
    val functionCode = frame[1].toInt() and 0xFF
    if (functionCode != 0x03 && functionCode != 0x04) return emptyList()

    val byteCount = frame[2].toInt() and 0xFF
    if (byteCount <= 0 || byteCount % 2 != 0 || frame.size != byteCount + 5) return emptyList()
    return (0 until byteCount / 2).map { index ->
        val valueIndex = 3 + index * 2
        ((frame[valueIndex].toInt() and 0xFF) shl 8) or
            (frame[valueIndex + 1].toInt() and 0xFF)
    }
}

internal fun analyzeModbusProbeResponse(
    config: ModbusTemperatureConfig,
    requestFrame: ByteArray,
    rawResponse: ByteArray,
    attemptedAt: Long = System.currentTimeMillis(),
    durationMs: Long = 0L,
): ModbusProbeResult {
    val requestHex = requestFrame.toHexString()
    if (rawResponse.isEmpty()) {
        return ModbusProbeResult(
            status = ModbusProbeStatus.NO_BYTES,
            message = "超时：未收到任何响应字节",
            requestHex = requestHex,
            attemptedAt = attemptedAt,
            durationMs = durationMs,
        )
    }

    val rawHex = rawResponse.toHexString()
    val frame = extractModbusResponseFrame(rawResponse, config.slaveAddress)
        ?: extractAnyModbusResponseFrame(rawResponse)
        ?: return ModbusProbeResult(
            status = ModbusProbeStatus.INVALID_FRAME,
            message = "收到 ${rawResponse.size} 字节，但未找到 CRC 正确的完整 Modbus 帧",
            requestHex = requestHex,
            rawResponseHex = rawHex,
            attemptedAt = attemptedAt,
            durationMs = durationMs,
        )
    val frameHex = frame.toHexString()
    val actualAddress = frame[0].toInt() and 0xFF
    val actualFunction = frame[1].toInt() and 0xFF
    val registers = extractModbusRegisterValues(frame)
    if (actualAddress != config.slaveAddress) {
        return ModbusProbeResult(
            status = ModbusProbeStatus.ADDRESS_MISMATCH,
            message = "收到地址 $actualAddress 的有效帧，当前配置地址为 ${config.slaveAddress}",
            requestHex = requestHex,
            rawResponseHex = rawHex,
            responseFrameHex = frameHex,
            registerValues = registers,
            responseAddress = actualAddress,
            responseFunctionCode = actualFunction,
            attemptedAt = attemptedAt,
            durationMs = durationMs,
        )
    }

    if (actualFunction == (config.functionCode or 0x80)) {
        val exceptionCode = frame[2].toInt() and 0xFF
        return ModbusProbeResult(
            status = ModbusProbeStatus.MODBUS_EXCEPTION,
            message = "Modbus 异常 0x${exceptionCode.toString(16).uppercase().padStart(2, '0')}：" +
                modbusExceptionMessage(exceptionCode),
            requestHex = requestHex,
            rawResponseHex = rawHex,
            responseFrameHex = frameHex,
            exceptionCode = exceptionCode,
            responseAddress = actualAddress,
            responseFunctionCode = actualFunction,
            attemptedAt = attemptedAt,
            durationMs = durationMs,
        )
    }
    if (actualFunction != config.functionCode) {
        return ModbusProbeResult(
            status = ModbusProbeStatus.FUNCTION_MISMATCH,
            message = "收到功能码 0x${actualFunction.toString(16).uppercase()}，当前配置功能码为 0x${config.functionCode.toString(16).uppercase()}",
            requestHex = requestHex,
            rawResponseHex = rawHex,
            responseFrameHex = frameHex,
            registerValues = registers,
            responseAddress = actualAddress,
            responseFunctionCode = actualFunction,
            attemptedAt = attemptedAt,
            durationMs = durationMs,
        )
    }

    val temperature = parseModbusTemperatureResponse(
        data = frame,
        slaveAddress = config.slaveAddress,
        functionCode = config.functionCode,
        registerCount = config.registerCount,
        temperatureRegisterOffset = config.temperatureRegisterOffset,
        temperatureScale = config.temperatureScale,
        calibrationOffset = config.calibrationOffset,
        valueType = config.valueType,
        byteOrder = config.byteOrder,
        wordOrder = config.wordOrder,
        valueMode = config.valueMode,
    )
    val byteCount = frame[2].toInt() and 0xFF
    if (temperature == null) {
        val message = if (byteCount != config.registerCount * 2) {
            "响应寄存器数量不匹配：收到 ${byteCount / 2}，期望 ${config.registerCount}"
        } else {
            "帧校验通过，但温度字段无效或超出 -50°C 到 80°C"
        }
        return ModbusProbeResult(
            status = ModbusProbeStatus.INVALID_PAYLOAD,
            message = message,
            requestHex = requestHex,
            rawResponseHex = rawHex,
            responseFrameHex = frameHex,
            registerValues = registers,
            responseAddress = actualAddress,
            responseFunctionCode = actualFunction,
            attemptedAt = attemptedAt,
            durationMs = durationMs,
        )
    }

    return ModbusProbeResult(
        status = ModbusProbeStatus.SUCCESS,
        message = "通信与解析成功：${"%.2f".format(temperature)}°C",
        requestHex = requestHex,
        rawResponseHex = rawHex,
        responseFrameHex = frameHex,
        registerValues = registers,
        temperature = temperature,
        responseAddress = actualAddress,
        responseFunctionCode = actualFunction,
        attemptedAt = attemptedAt,
        durationMs = durationMs,
    )
}

internal fun modbusExceptionMessage(exceptionCode: Int): String = when (exceptionCode) {
    0x01 -> "不支持的功能码"
    0x02 -> "非法寄存器地址"
    0x03 -> "非法寄存器数量或数值"
    0x04 -> "从机执行失败"
    0x06 -> "从机忙"
    else -> "未定义异常"
}

internal fun reconnectDelayMs(
    consecutiveFailures: Int,
    initialDelayMs: Long,
    maxDelayMs: Long,
): Long {
    require(initialDelayMs > 0L)
    require(maxDelayMs >= initialDelayMs)
    if (consecutiveFailures <= 1) return initialDelayMs

    var delayMs = initialDelayMs
    repeat(minOf(consecutiveFailures - 1, 62)) {
        if (delayMs >= maxDelayMs || delayMs > maxDelayMs / 2) return maxDelayMs
        delayMs *= 2
    }
    return delayMs
}

internal fun parseModbusTemperatureResponse(
    data: ByteArray,
    slaveAddress: Int,
    functionCode: Int = 0x03,
    registerCount: Int,
    temperatureRegisterOffset: Int,
    temperatureScale: Float,
    calibrationOffset: Float = 0f,
    valueType: ModbusValueType = ModbusValueType.INT16,
    byteOrder: ModbusByteOrder = ModbusByteOrder.BIG_ENDIAN,
    wordOrder: ModbusWordOrder = ModbusWordOrder.HIGH_WORD_FIRST,
    valueMode: ModbusTemperatureValueMode = ModbusTemperatureValueMode.DIRECT_CELSIUS,
    minValidTemperature: Float = -50f,
    maxValidTemperature: Float = 80f,
): Float? {
    if (data.size < 5 || registerCount !in 1..125) return null
    if (functionCode != 0x03 && functionCode != 0x04) return null
    if (temperatureRegisterOffset < 0 ||
        temperatureRegisterOffset.toLong() + valueType.registerWidth > registerCount.toLong() ||
        !temperatureScale.isFinite() || temperatureScale <= 0f || !calibrationOffset.isFinite()
    ) return null
    if (!minValidTemperature.isFinite() || !maxValidTemperature.isFinite() ||
        minValidTemperature > maxValidTemperature
    ) return null
    if ((data[0].toInt() and 0xFF) != slaveAddress) return null
    if ((data[1].toInt() and 0xFF) != functionCode) return null

    val byteCount = data[2].toInt() and 0xFF
    if (byteCount != registerCount * 2 || data.size != byteCount + 5) return null

    val receivedCrc = ((data[data.lastIndex].toInt() and 0xFF) shl 8) or
        (data[data.lastIndex - 1].toInt() and 0xFF)
    if (receivedCrc != modbusCrc16(data, 0, data.size - 2)) return null

    val rawValue = decodeModbusNumericValue(
        registerBytes = data.copyOfRange(3, 3 + byteCount),
        registerOffset = temperatureRegisterOffset,
        valueType = valueType,
        byteOrder = byteOrder,
        wordOrder = wordOrder,
    ) ?: return null
    val scaledValue = rawValue * temperatureScale
    if (!scaledValue.isFinite()) return null

    val convertedTemperature = when (valueMode) {
        ModbusTemperatureValueMode.DIRECT_CELSIUS -> scaledValue
        ModbusTemperatureValueMode.PT100_RESISTANCE ->
            Pt100Converter.resistanceToTemperature(scaledValue, Pt100Converter.SensorType.PT100)
        ModbusTemperatureValueMode.PT1000_RESISTANCE ->
            Pt100Converter.resistanceToTemperature(scaledValue / 10.0, Pt100Converter.SensorType.PT100)
    } ?: return null
    val calibratedTemperature = convertedTemperature + calibrationOffset
    return calibratedTemperature.toFloat().takeIf {
        calibratedTemperature.isFinite() && calibratedTemperature in minValidTemperature..maxValidTemperature
    }
}

internal fun decodeModbusNumericValue(
    registerBytes: ByteArray,
    registerOffset: Int,
    valueType: ModbusValueType,
    byteOrder: ModbusByteOrder,
    wordOrder: ModbusWordOrder,
): Double? {
    if (registerOffset < 0) return null
    val startLong = registerOffset.toLong() * 2L
    val byteWidth = valueType.registerWidth * 2
    if (startLong + byteWidth > registerBytes.size.toLong()) return null

    val start = startLong.toInt()
    val canonicalBytes = registerBytes.copyOfRange(start, start + byteWidth)
    if (byteOrder == ModbusByteOrder.LITTLE_ENDIAN) {
        for (index in canonicalBytes.indices step 2) {
            val first = canonicalBytes[index]
            canonicalBytes[index] = canonicalBytes[index + 1]
            canonicalBytes[index + 1] = first
        }
    }
    if (valueType.registerWidth == 2 && wordOrder == ModbusWordOrder.LOW_WORD_FIRST) {
        val firstHigh = canonicalBytes[0]
        val firstLow = canonicalBytes[1]
        canonicalBytes[0] = canonicalBytes[2]
        canonicalBytes[1] = canonicalBytes[3]
        canonicalBytes[2] = firstHigh
        canonicalBytes[3] = firstLow
    }

    val buffer = ByteBuffer.wrap(canonicalBytes).order(ByteOrder.BIG_ENDIAN)
    val decoded = when (valueType) {
        ModbusValueType.INT16 -> buffer.short.toDouble()
        ModbusValueType.UINT16 -> (buffer.short.toInt() and 0xFFFF).toDouble()
        ModbusValueType.INT32 -> buffer.int.toDouble()
        ModbusValueType.UINT32 -> (buffer.int.toLong() and 0xFFFF_FFFFL).toDouble()
        ModbusValueType.FLOAT32 -> buffer.float.toDouble()
    }
    return decoded.takeIf { it.isFinite() }
}

/**
 * 安全关闭 SerialPort，吞掉异常只打日志。
 * 提取为包级 internal 扩展，供 SerialBarcodeScanner 和 ModbusTemperatureReader 共用。
 */
internal fun SerialPort.tryCloseSafely(tag: String = "SerialPort") {
    try {
        tryClose()
    } catch (e: Exception) {
        Log.w(tag, "Error closing serial port", e)
    }
}
