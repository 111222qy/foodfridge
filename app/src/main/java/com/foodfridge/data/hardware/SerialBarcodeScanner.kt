package com.foodfridge.data.hardware

import android.content.Context
import android.util.Log
import android.serialport.SerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.LinkedHashSet

class SerialBarcodeScanner(
    private val devicePath: String = DEFAULT_DEVICE_PATH,
    private val baudRate: Int = DEFAULT_BAUD_RATE,
    private val dataBits: Int = 8,
    private val stopBits: Int = 1,
    private val parity: Int = 0,
    private val context: Context? = null,
) {
    companion object {
        private const val TAG = "SerialBarcodeScanner"
        private const val USB_PREFIX = "usb://"

        const val DEFAULT_DEVICE_PATH = "/dev/ttyS4"
        const val DEFAULT_BAUD_RATE = 9600

        val COMMON_SERIAL_PORTS = listOf(
            "/dev/ttyS1",
            "/dev/ttyS2",
            "/dev/ttyS3",
            "/dev/ttyS4",
            "/dev/ttyUSB0",
            "/dev/ttyUSB1",
            "/dev/ttyACM0",
            "/dev/ttyACM1",
            "/dev/ttyHS0",
            "/dev/ttyHS1",
            "/dev/ttyAMA0",
            "/dev/ttyAMA1",
        )

        val TRIGGER_NEWLAND = byteArrayOf(0x1B, 0x31)
        val STOP_NEWLAND = byteArrayOf(0x1B, 0x30)

        val TRIGGER_HONEYWELL = byteArrayOf(0x16, 0x54, 0x0D)
        val STOP_HONEYWELL = byteArrayOf(0x16, 0x55, 0x0D)

        val TRIGGER_GENERIC = byteArrayOf(0x7E, 0x00, 0x08, 0x01, 0x00, 0x02, 0x01, 0xAB.toByte(), 0xCD.toByte())

        const val READ_TIMEOUT_MS = 5000L
        const val SCAN_TIMEOUT_MS = 10000L

        val SUPPORTED_BAUD_RATES = listOf(9600, 115200, 19200, 38400, 57600, 4800, 2400, 1200)

        const val MIN_RAW_DATA_LENGTH = 2
        const val MIN_BARCODE_LENGTH = 2
    }

    private var jniSerialPort: SerialPort? = null
    private var fileInputStream: java.io.FileInputStream? = null
    private var fileOutputStream: java.io.FileOutputStream? = null
    private var usbPort: UsbSerialPort? = null
    private var serialPortLease: SerialPortLeaseRegistry.Lease? = null
    private val connectionLock = Any()
    private var lifecycleGeneration = 0L

    @Volatile
    private var destroyed = false
    private var isConnected = false
    var isUsbMode = false
        private set

    fun isConnected(): Boolean = isConnected

    private val scannerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    sealed class ScanState {
        object Idle : ScanState()
        object Scanning : ScanState()
        data class Success(val barcode: String) : ScanState()
        data class Error(val message: String) : ScanState()
    }

    private var onBarcodeDetected: ((String) -> Unit)? = null
    private var continuousScanJob: Job? = null
    private var continuousListeningJob: Job? = null

    /**
     * 释放资源，取消 scannerScope。应在不再需要时调用。
     */
    fun destroy() {
        continuousScanJob?.cancel()
        continuousScanJob = null
        continuousListeningJob?.cancel()
        continuousListeningJob = null
        synchronized(connectionLock) {
            destroyed = true
            lifecycleGeneration++
            closeLocked()
        }
        scannerScope.cancel()
        Log.i(TAG, "SerialBarcodeScanner destroyed")
    }

    suspend fun open(): Boolean {
        val requestedGeneration = synchronized(connectionLock) { lifecycleGeneration }
        var completed = false
        try {
            val result = withContext(Dispatchers.IO) {
                synchronized(connectionLock) {
                    if (destroyed || requestedGeneration != lifecycleGeneration) {
                        return@synchronized false
                    }
                    openLocked()
                }
            }
            completed = true
            return result
        } finally {
            if (!completed) {
                synchronized(connectionLock) {
                    if (requestedGeneration == lifecycleGeneration) {
                        lifecycleGeneration++
                        closeLocked()
                    }
                }
            }
        }
    }

    private fun openLocked(): Boolean {
        return try {
            if (isConnected) {
                Log.w(TAG, "Serial port already open: $devicePath")
                return true
            }

            if (devicePath.startsWith(USB_PREFIX)) {
                val usbOpened = openUsbSerialPort(devicePath)
                if (usbOpened) {
                    isConnected = true
                    isUsbMode = true
                    Log.i(TAG, "USB serial port opened: $devicePath @ $baudRate baud")
                }
                return usbOpened
            }

            val device = File(devicePath)
            if (!device.exists()) {
                Log.e(TAG, "Serial port device not found: $devicePath")
                return false
            }

            serialPortLease = SerialPortLeaseRegistry.tryAcquire(
                devicePath = devicePath,
                owner = "SerialBarcodeScanner@${System.identityHashCode(this)}",
            )
            if (serialPortLease == null) {
                Log.e(
                    TAG,
                    "Serial port is busy: $devicePath, owner=${SerialPortLeaseRegistry.currentOwner(devicePath)}",
                )
                return false
            }

            val jniOpened = openJniSerialPort(devicePath)
            if (jniOpened) {
                isConnected = true
                isUsbMode = false
                return true
            }

            serialPortLease?.close()
            serialPortLease = null

            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open serial port $devicePath", e)
            closeLocked()
            false
        }
    }

    private fun openJniSerialPort(path: String): Boolean {
        return try {
            jniSerialPort = SerialPort.newBuilder(path, baudRate)
                .dataBits(dataBits)
                .parity(parity)
                .stopBits(stopBits)
                .build()

            fileInputStream = jniSerialPort!!.inputStream as java.io.FileInputStream
            fileOutputStream = jniSerialPort!!.outputStream as java.io.FileOutputStream

            Log.i(TAG, "JNI SerialPort opened: $path @ $baudRate baud (${dataBits}N${stopBits})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "JNI SerialPort open failed for $path", e)
            jniSerialPort?.tryCloseSafely(TAG)
            jniSerialPort = null
            fileInputStream = null
            fileOutputStream = null
            false
        }
    }

    fun close() {
        continuousScanJob?.cancel()
        continuousScanJob = null
        continuousListeningJob?.cancel()
        continuousListeningJob = null
        synchronized(connectionLock) {
            lifecycleGeneration++
            closeLocked()
        }
    }

    private fun closeLocked() {
        try {
            jniSerialPort?.tryCloseSafely(TAG)
        } catch (e: Exception) {
            Log.w(TAG, "Error closing JNI serial port", e)
            try {
                fileInputStream?.close()
                fileOutputStream?.close()
            } catch (_: Exception) { }
        }
        try {
            usbPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB serial port", e)
        } finally {
            jniSerialPort = null
            fileInputStream = null
            fileOutputStream = null
            usbPort = null
            serialPortLease?.close()
            serialPortLease = null
            isConnected = false
            isUsbMode = false
            _scanState.value = ScanState.Idle
            Log.i(TAG, "Serial port closed: $devicePath")
        }
    }

    suspend fun write(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                Log.e(TAG, "Serial port not connected")
                return@withContext false
            }

            if (isUsbMode && usbPort != null) {
                usbPort?.write(data, 1000)
                Log.d(TAG, "Sent ${data.size} bytes to USB serial: ${data.toHexString()}")
                return@withContext true
            }

            if (fileOutputStream != null) {
                fileOutputStream?.write(data)
                fileOutputStream?.flush()
                Log.d(TAG, "Sent ${data.size} bytes: ${data.toHexString()}")
                return@withContext true
            }

            Log.e(TAG, "No output stream available")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to serial port", e)
            false
        }
    }

    suspend fun read(
        bufferSize: Int = 512,
        timeoutMs: Long = READ_TIMEOUT_MS,
        interFrameGapMs: Long = 1500L
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                Log.e(TAG, "Serial port not connected")
                return@withContext null
            }

            if (isUsbMode && usbPort != null) {
                val usbBuffer = ByteArray(bufferSize)
                val read = usbPort?.read(usbBuffer, timeoutMs.toInt()) ?: 0
                if (read > 0) {
                    val data = usbBuffer.copyOf(read)
                    Log.d(TAG, "Read $read bytes from USB serial: ${data.toHexString()}")
                    return@withContext data
                }
                Log.w(TAG, "USB serial read timeout after ${timeoutMs}ms")
                return@withContext null
            }

            if (fileInputStream == null) {
                Log.e(TAG, "No input stream available")
                return@withContext null
            }

            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()
            val result = ByteArrayOutputStream()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val available = fileInputStream?.available() ?: 0
                if (available > 0) {
                    val read = fileInputStream?.read(buffer, 0, minOf(available, bufferSize)) ?: 0
                    if (read > 0) {
                        result.write(buffer, 0, read)
                        Log.d(TAG, "Read chunk: $read bytes, accumulated ${result.size()} bytes")
                        if (result.size() > 0 && fileInputStream?.available() == 0) {
                            Log.d(TAG, "Input idle, waiting inter-frame gap ${interFrameGapMs}ms for more data...")
                            delay(interFrameGapMs)
                            if (fileInputStream?.available() == 0) {
                                val data = result.toByteArray()
                                Log.d(TAG, "Returning ${data.size} bytes after inter-frame gap: ${data.toHexString()}")
                                return@withContext data
                            } else {
                                Log.d(TAG, "More data arrived during inter-frame gap, continuing read")
                            }
                        }
                    }
                }
                delay(10)
            }

            if (result.size() > 0) {
                val data = result.toByteArray()
                Log.d(TAG, "Read ${data.size} bytes (timeout): ${data.toHexString()}")
                return@withContext data
            }

            Log.w(TAG, "Read timeout after ${timeoutMs}ms")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read from serial port", e)
            null
        }
    }

    suspend fun startScan(
        triggerCommand: ByteArray = TRIGGER_NEWLAND,
        timeoutMs: Long = SCAN_TIMEOUT_MS,
        onDetected: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        stopContinuousScanning()
        if (!isConnected) {
            Log.e(TAG, "Cannot start scan: serial port not connected")
            _scanState.value = ScanState.Error("串口未连接")
            return@withContext false
        }

        onBarcodeDetected = onDetected
        _scanState.value = ScanState.Scanning

        try {
            if (!write(triggerCommand)) {
                _scanState.value = ScanState.Error("发送触发命令失败")
                return@withContext false
            }

            Log.i(TAG, "Scan triggered, waiting for barcode...")

            val result = read(timeoutMs = timeoutMs)

            if (result != null && result.isNotEmpty()) {
                val barcode = parseBarcodeResult(result)
                if (barcode != null) {
                    Log.i(TAG, "Barcode detected: $barcode")
                    _scanState.value = ScanState.Success(barcode)
                    onDetected(barcode)
                    return@withContext true
                } else {
                    Log.w(TAG, "Received data but failed to parse as barcode: ${result.toHexString()}")
                    _scanState.value = ScanState.Error("未检测到有效二维码")
                    return@withContext false
                }
            } else {
                Log.w(TAG, "Scan timeout, no barcode detected")
                _scanState.value = ScanState.Error("扫描超时，未检测到二维码")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            _scanState.value = ScanState.Error("扫描异常: ${e.message}")
            false
        } finally {
            val stopCommand = when (triggerCommand) {
                TRIGGER_NEWLAND -> STOP_NEWLAND
                TRIGGER_HONEYWELL -> STOP_HONEYWELL
                else -> null
            }
            stopCommand?.let { write(it) }
        }
    }

    fun startContinuousListening(onDetected: (String) -> Unit) {
        onBarcodeDetected = onDetected
        _scanState.value = ScanState.Scanning

        continuousListeningJob = scannerScope.launch {
            while (isActive && isConnected) {
                try {
                    val result = read(timeoutMs = 1000)
                    if (result != null && result.isNotEmpty()) {
                        val barcode = parseBarcodeResult(result)
                        if (barcode != null) {
                            Log.i(TAG, "Barcode detected (continuous): $barcode")
                            _scanState.value = ScanState.Success(barcode)
                            onDetected(barcode)
                            _scanState.value = ScanState.Scanning
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Continuous listening error", e)
                    delay(1000)
                }
            }
        }
    }

    fun stopContinuousListening() {
        continuousListeningJob?.cancel()
        continuousListeningJob = null
        _scanState.value = ScanState.Idle
    }

    fun resetState() {
        stopContinuousScanning()
        _scanState.value = ScanState.Idle
    }

    fun startContinuousScanning(
        triggerCommand: ByteArray = TRIGGER_NEWLAND,
        scanTimeoutMs: Long = 30_000L,
        intervalMs: Long = 500L,
        onDetected: (String) -> Unit
    ) {
        stopContinuousScanning()
        if (!isConnected) {
            _scanState.value = ScanState.Error("串口未连接")
            return
        }

        onBarcodeDetected = onDetected
        _scanState.value = ScanState.Scanning
        Log.i(TAG, "Continuous scanning started (timeout=${scanTimeoutMs}ms)")

        continuousScanJob = scannerScope.launch {
            try {
                // 先清空输入缓冲区，避免旧数据干扰
                clearInputBuffer()

                // 尝试发送触发命令（部分扫码器需要），失败也不退出
                if (triggerCommand.isNotEmpty()) {
                    if (write(triggerCommand)) {
                        Log.d(TAG, "Trigger sent, entering continuous listen mode")
                    } else {
                        Log.w(TAG, "Trigger command failed, trying passive listen mode")
                    }
                }

                val accumulated = mutableListOf<Byte>()
                var scanDeadline = System.currentTimeMillis() + scanTimeoutMs
                var noDataCount = 0

                while (isActive && isConnected) {
                    val remaining = scanDeadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        // 超时未识别，不直接失败，而是重新触发一次并继续监听
                        Log.d(TAG, "Scan deadline reached, re-triggering without clearing buffer")
                        if (triggerCommand.isNotEmpty() && write(triggerCommand)) {
                            Log.d(TAG, "Re-triggered")
                        }
                        scanDeadline = System.currentTimeMillis() + scanTimeoutMs
                        noDataCount++
                        // 连续 3 个周期无数据再报错，避免误判
                        if (noDataCount >= 3) {
                            _scanState.value = ScanState.Error("扫描超时，未识别到二维码")
                            return@launch
                        }
                        continue
                    }

                    try {
                        val chunk = read(
                            timeoutMs = minOf(5000L, remaining),
                            interFrameGapMs = 500L
                        )

                        if (chunk != null && chunk.isNotEmpty()) {
                            noDataCount = 0
                            val isAckOnly = chunk.all { it == 0x06.toByte() }
                            if (isAckOnly) {
                                Log.d(TAG, "ACK received (0x06), waiting for barcode data...")
                                delay(300)
                                continue
                            }

                            val stripped = chunk.dropWhile { it == 0x06.toByte() }.toByteArray()
                            accumulated.addAll(stripped.toList())
                            val combined = accumulated.toByteArray()
                            Log.d(TAG, "Accumulated ${combined.size} bytes so far (after stripping ACK)")
                            val barcode = parseBarcodeResult(combined)
                            if (barcode != null) {
                                Log.i(TAG, "Continuous scan detected: $barcode")
                                _scanState.value = ScanState.Success(barcode)
                                onDetected(barcode)
                                return@launch
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Read error in continuous scanning", e)
                        delay(500)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Continuous scanning cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Continuous scanning error", e)
                _scanState.value = ScanState.Error("扫描异常: ${e.message}")
            } finally {
                // 此扫码器在触发模式下不需要发送停止命令，
                // 每次 1B 31 触发一次扫描，完成后自动回到等待状态。
                // 发送 1B 30 反而会导致扫码器锁定，不再响应后续触发。
            }

            if (!isConnected) {
                _scanState.value = ScanState.Error("串口连接断开")
            }
        }
    }

    fun stopContinuousScanning() {
        continuousScanJob?.cancel()
        continuousScanJob = null
        if (_scanState.value is ScanState.Scanning) {
            _scanState.value = ScanState.Idle
        }
        Log.i(TAG, "Continuous scanning stopped")
    }

    private fun stripTriggerEcho(data: ByteArray): ByteArray {
        val echoPatterns = listOf(TRIGGER_NEWLAND, TRIGGER_HONEYWELL, TRIGGER_GENERIC, STOP_NEWLAND, STOP_HONEYWELL)
        for (pattern in echoPatterns) {
            if (data.size > pattern.size && data.copyOfRange(0, pattern.size).contentEquals(pattern)) {
                val stripped = data.copyOfRange(pattern.size, data.size)
                Log.d(TAG, "Stripped trigger/stop echo (${pattern.size} bytes), remaining ${stripped.size} bytes")
                return stripped
            }
        }
        return data
    }

    private fun parseBarcodeResult(data: ByteArray): String? {
        var rawData = data
        if (rawData.isEmpty()) return null

        Log.d(TAG, "parseBarcodeResult input: ${rawData.size} bytes, hex: ${rawData.toHexString()}")

        rawData = stripTriggerEcho(rawData)
        if (rawData.isEmpty()) {
            Log.d(TAG, "Rejected: only trigger echo, no barcode data")
            return null
        }

        val ackStripped = rawData.dropWhile { it == 0x06.toByte() }.toByteArray()
        if (ackStripped.isEmpty()) {
            Log.d(TAG, "Rejected: only ACK bytes (0x06), no barcode data")
            return null
        }
        if (ackStripped.size < rawData.size) {
            Log.d(TAG, "Stripped ${rawData.size - ackStripped.size} ACK bytes, remaining ${ackStripped.size} bytes")
        }
        rawData = ackStripped

        if (rawData.size < MIN_RAW_DATA_LENGTH) {
            Log.w(TAG, "Rejected: raw data too short (${rawData.size} bytes), raw hex: ${rawData.toHexString()}")
            return null
        }

        // 尝试多种编码解析，优先 UTF-8，回退 GB2312/GBK（部分扫码器默认中文编码）
        val encodings = listOf(Charsets.UTF_8, java.nio.charset.Charset.forName("GBK"), java.nio.charset.Charset.forName("GB2312"))
        for (charset in encodings) {
            val decoded = try {
                String(rawData, charset)
                    .replace("\r\n", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode with $charset", e)
                continue
            }
            Log.d(TAG, "Decoded with $charset: '$decoded'")
            val cleaned = decoded.filter { it.code > 31 && it.code != 127 }
            if (cleaned.length >= MIN_BARCODE_LENGTH) {
                Log.d(TAG, "Parsed barcode ($charset): $cleaned")
                return cleaned
            }
        }

        // 新增：识别扫码器把字节值以十进制数字字符串发送的特殊格式
        val digitString = rawData.filter { b ->
            (b.toInt() and 0xFF) in '0'.code..'9'.code
        }.toByteArray()
        if (digitString.size >= 4 && digitString.size.toFloat() / rawData.size >= 0.8f) {
            val digits = String(digitString, Charsets.UTF_8)
            // 可能是每2位(00-99)、每3位(000-255)或连续变长ASCII十进制
            data class DigitCandidate(val chunkType: String, val decoded: String)
            val candidates = mutableListOf<DigitCandidate>()

            // 尝试每3位拆分（0-255）
            runCatching {
                val chunks3 = digits.chunked(3).filter { it.length == 3 }
                val bytes3 = chunks3.mapNotNull { it.toIntOrNull()?.coerceIn(0, 255)?.toByte() }.toByteArray()
                if (bytes3.isNotEmpty()) {
                    candidates.add(DigitCandidate("3", String(bytes3, Charsets.UTF_8)))
                }
            }

            // 尝试每2位拆分（0-99）
            runCatching {
                val chunks2 = digits.chunked(2).filter { it.length == 2 }
                val bytes2 = chunks2.mapNotNull { it.toIntOrNull()?.coerceIn(0, 255)?.toByte() }.toByteArray()
                if (bytes2.isNotEmpty()) {
                    candidates.add(DigitCandidate("2", String(bytes2, Charsets.UTF_8)))
                }
            }

            // 选择包含 '|' 或 printable 比例更高的候选
            val best = candidates
                .filter { it.decoded.length >= MIN_BARCODE_LENGTH }
                .maxByOrNull { candidate ->
                    val containsDelimiter = if (candidate.decoded.contains("|")) 1000f else 0f
                    val printableRatio = candidate.decoded.count { it.code in 32..126 }.toFloat() / candidate.decoded.length
                    containsDelimiter + printableRatio
                }
            if (best != null) {
                val cleaned = best.decoded.filter { it.code > 31 && it.code != 127 }
                Log.d(TAG, "Parsed from digit-string encoding (${best.chunkType}-digit chunks): $cleaned")
                return cleaned
            }
        }

        // 新增：识别扫码器把原始字节以 ASCII 十六进制字符串发送的格式
        // 例如真实字节 0x41 0x42 被发送为字符 '4','1','4','2'（即十六进制 34 31 34 32）
        val hexChars = rawData.filter { b ->
            val code = b.toInt() and 0xFF
            code in '0'.code..'9'.code || code in 'a'.code..'f'.code || code in 'A'.code..'F'.code
        }.toByteArray()
        if (hexChars.size >= 4 && hexChars.size % 2 == 0 && hexChars.size.toFloat() / rawData.size >= 0.8f) {
            val hexString = String(hexChars, Charsets.UTF_8)
            runCatching {
                val decodedBytes = hexString.chunked(2)
                    .mapNotNull { it.toIntOrNull(16)?.toByte() }
                    .toByteArray()
                if (decodedBytes.isNotEmpty()) {
                    for (charset in encodings) {
                        val decoded = String(decodedBytes, charset)
                            .replace("\r\n", "")
                            .replace("\r", "")
                            .replace("\n", "")
                            .trim()
                        val cleaned = decoded.filter { it.code > 31 && it.code != 127 }
                        if (cleaned.length >= MIN_BARCODE_LENGTH) {
                            Log.d(TAG, "Parsed from ASCII-hex encoding ($charset): $cleaned")
                            return cleaned
                        }
                    }
                }
            }
        }

        // 最后尝试仅保留可打印 ASCII 字节
        val asciiBytes = rawData.filter { b ->
            (b.toInt() and 0xFF) in 32..126
        }.toByteArray()
        val asciiString = String(asciiBytes, Charsets.UTF_8).trim()
        if (asciiString.length >= MIN_BARCODE_LENGTH) {
            Log.d(TAG, "Parsed after byte-level ASCII cleaning: $asciiString")
            return asciiString
        }

        Log.w(TAG, "Failed to parse barcode (too short or noise), raw hex: ${rawData.toHexString()}")
        return null
    }

    suspend fun autoDetect(
        baudRates: List<Int> = listOf(9600),
        onLog: ((String) -> Unit)? = null
    ): DetectResult? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting auto-detection of serial barcode scanner...")
        onLog?.invoke("开始自动探测串口扫码器...")
        val candidatePorts = discoverCandidatePorts()
        onLog?.invoke("候选串口: ${candidatePorts.joinToString()}")
        onLog?.invoke("探测波特率: ${baudRates.joinToString()}")

        for (port in candidatePorts) {
            val isUsbCandidate = port.startsWith(USB_PREFIX)
            val device = if (isUsbCandidate) null else File(port)
            if (device != null && !device.exists()) {
                Log.d(TAG, "Port $port does not exist, skipping")
                onLog?.invoke("串口 $port 不存在，跳过")
                continue
            }

            for (currentBaudRate in baudRates) {
                Log.i(TAG, "Trying port: $port @ $currentBaudRate baud")
                onLog?.invoke("正在测试串口: $port @ ${currentBaudRate}bps")

                val scanner = SerialBarcodeScanner(port, currentBaudRate, context = context)
                if (!scanner.open()) {
                    Log.w(TAG, "Failed to open $port @ $currentBaudRate")
                    onLog?.invoke("无法打开串口 $port @ ${currentBaudRate}bps")
                    continue
                }

                onLog?.invoke("串口 $port 已打开 (${if (scanner.isUsbMode) "USB" else "JNI"}模式)，开始探测...")

                try {
                    onLog?.invoke("策略1: 被动监听模式（等待主动上报）...")
                    val passiveResult = scanner.read(timeoutMs = 2000)
                    if (passiveResult != null && passiveResult.isNotEmpty()) {
                        Log.i(TAG, "Passive mode detected data on $port: ${passiveResult.toHexString()}")
                        onLog?.invoke("被动模式检测到数据!")
                        val barcode = scanner.parseBarcodeResult(passiveResult)
                        if (barcode != null) {
                            Log.i(TAG, "Barcode (passive): $barcode")
                            onLog?.invoke("解析成功: $barcode")
                            scanner.close()
                            return@withContext DetectResult(port, "被动模式/自动上报", byteArrayOf(), barcode, currentBaudRate)
                        }
                    }

                    val triggerCommands = listOf(
                        "Newland" to TRIGGER_NEWLAND,
                        "Honeywell" to TRIGGER_HONEYWELL,
                        "Generic" to TRIGGER_GENERIC
                    )

                    for ((name, command) in triggerCommands) {
                        onLog?.invoke("策略2: 尝试 $name 触发命令...")
                        Log.d(TAG, "Trying $name trigger command on $port @ $currentBaudRate")

                        scanner.clearInputBuffer()

                        if (!scanner.write(command)) {
                            onLog?.invoke("发送命令失败")
                            continue
                        }

                        onLog?.invoke("已发送触发命令，等待响应...")
                        delay(200)
                        val response = scanner.read(timeoutMs = 5000)

                        if (response != null && response.isNotEmpty()) {
                            Log.i(TAG, "Detected scanner on $port with $name protocol! Response: ${response.toHexString()}")
                            onLog?.invoke("检测到响应! 协议: $name")
                            onLog?.invoke("原始数据: ${response.toHexString()}")
                            val barcode = scanner.parseBarcodeResult(response)
                            if (barcode != null) {
                                Log.i(TAG, "Barcode: $barcode")
                                onLog?.invoke("解析成功: $barcode")
                                scanner.close()
                                return@withContext DetectResult(port, name, command, barcode, currentBaudRate)
                            } else {
                                onLog?.invoke("收到数据但无法解析为条码")
                            }
                        } else {
                            onLog?.invoke("$name 协议无响应")
                        }
                    }

                    onLog?.invoke("策略3: 尝试唤醒命令...")
                    val wakeCommands = listOf(
                        "Wake1" to byteArrayOf(0x00),
                        "Wake2" to byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
                    )
                    for ((wakeName, wakeCmd) in wakeCommands) {
                        scanner.clearInputBuffer()
                        if (!scanner.write(wakeCmd)) continue
                        delay(300)
                        val wakeResponse = scanner.read(timeoutMs = 3000)
                        if (wakeResponse != null && wakeResponse.isNotEmpty()) {
                            Log.i(TAG, "Wake command $wakeName got response on $port: ${wakeResponse.toHexString()}")
                            onLog?.invoke("唤醒命令 $wakeName 收到响应")
                        }
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Error testing $port: ${e.message}")
                    onLog?.invoke("测试 $port 时出错: ${e.message}")
                } finally {
                    scanner.close()
                    onLog?.invoke("关闭串口 $port")
                }
            }
        }

        Log.w(TAG, "No serial barcode scanner detected")
        onLog?.invoke("未检测到串口扫码器")
        null
    }

    private fun discoverCandidatePorts(): List<String> {
        val candidates = LinkedHashSet<String>()

        candidates.addAll(COMMON_SERIAL_PORTS)
        candidates.addAll(scanDevDirectory(File("/dev")))
        candidates.addAll(scanSerialAliasDirectory(File("/dev/serial/by-id")))
        candidates.addAll(scanSerialAliasDirectory(File("/dev/serial/by-path")))
        candidates.addAll(scanSysClassTtyDirectory(File("/sys/class/tty")))
        candidates.addAll(scanUsbSerialDevices())

        return candidates.toList()
    }

    private fun scanUsbSerialDevices(): List<String> {
        val appContext = context ?: return emptyList()
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            ?: return emptyList()

        return runCatching {
            UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .map { driver -> USB_PREFIX + driver.device.deviceName }
        }.getOrElse { emptyList() }
    }

    private fun openUsbSerialPort(candidate: String): Boolean {
        val appContext = context ?: run {
            Log.w(TAG, "Context missing, cannot open USB serial device")
            return false
        }

        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            ?: run {
                Log.w(TAG, "UsbManager unavailable")
                return false
            }

        val targetDeviceName = candidate.removePrefix(USB_PREFIX)
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull { it.device.deviceName == targetDeviceName }
            ?: run {
                Log.w(TAG, "No USB serial driver found for $candidate")
                return false
            }

        if (!usbManager.hasPermission(driver.device)) {
            Log.w(TAG, "USB permission not granted for ${driver.device.deviceName}")
            return false
        }

        val leaseKey = USB_PREFIX + driver.device.deviceName
        val lease = SerialPortLeaseRegistry.tryAcquire(
            devicePath = leaseKey,
            owner = "SerialBarcodeScanner@${System.identityHashCode(this)}",
        ) ?: run {
            Log.w(TAG, "USB serial port is busy: $leaseKey")
            return false
        }
        serialPortLease = lease

        val connection = usbManager.openDevice(driver.device) ?: run {
            Log.w(TAG, "Failed to open USB device connection for ${driver.device.deviceName}")
            lease.close()
            serialPortLease = null
            return false
        }

        return try {
            val port = driver.ports.firstOrNull() ?: throw IllegalStateException("USB device has no serial ports")
            port.open(connection)
            port.setParameters(
                baudRate,
                dataBits,
                stopBits,
                when (parity) {
                    1 -> UsbSerialPort.PARITY_ODD
                    2 -> UsbSerialPort.PARITY_EVEN
                    else -> UsbSerialPort.PARITY_NONE
                }
            )
            usbPort = port
            jniSerialPort = null
            fileInputStream = null
            fileOutputStream = null
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed opening USB serial port ${driver.device.deviceName}", e)
            runCatching { connection.close() }
            lease.close()
            serialPortLease = null
            false
        }
    }

    private fun scanDevDirectory(root: File): List<String> {
        return runCatching {
            root.listFiles { file ->
                file.name.matches(Regex("tty(S|USB|ACM|HS|AMA)\\d+"))
            }?.map { it.absolutePath }.orEmpty()
        }.getOrElse { emptyList() }
    }

    private fun scanSerialAliasDirectory(root: File): List<String> {
        if (!root.exists() || !root.isDirectory) return emptyList()

        val results = mutableListOf<String>()
        root.listFiles()?.forEach { file ->
            if (file.exists()) {
                results.add(file.absolutePath)
            }
        }
        return results
    }

    private fun scanSysClassTtyDirectory(root: File): List<String> {
        if (!root.exists() || !root.isDirectory) return emptyList()

        return runCatching {
            root.listFiles()?.mapNotNull { ttyDir ->
                val name = ttyDir.name
                if (!name.matches(Regex("tty(S|USB|ACM|HS|AMA)\\d+"))) {
                    null
                } else {
                    val devNode = File("/dev/$name")
                    if (devNode.exists()) devNode.absolutePath else null
                }
            }.orEmpty()
        }.getOrElse { emptyList() }
    }

    data class DetectResult(
        val port: String,
        val protocolName: String,
        val triggerCommand: ByteArray,
        val sampleBarcode: String? = null,
        val baudRate: Int = DEFAULT_BAUD_RATE
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DetectResult
            return port == other.port && protocolName == other.protocolName && baudRate == other.baudRate
        }

        override fun hashCode(): Int {
            var result = port.hashCode()
            result = 31 * result + protocolName.hashCode()
            result = 31 * result + baudRate
            return result
        }
    }

    private suspend fun clearInputBuffer() {
        try {
            if (isUsbMode && usbPort != null) {
                val buf = ByteArray(256)
                val read = usbPort?.read(buf, 100) ?: 0
                if (read > 0) {
                    Log.d(TAG, "Cleared $read bytes from USB buffer: ${buf.copyOf(read).toHexString()}")
                }
                return
            }
            while (fileInputStream?.available() ?: 0 > 0) {
                fileInputStream?.read()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear input buffer", e)
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(" ") { String.format("%02X", it) }
    }

    private class ByteArrayOutputStream {
        private val buffer = java.io.ByteArrayOutputStream()

        fun write(b: ByteArray, off: Int, len: Int) {
            buffer.write(b, off, len)
        }

        fun size(): Int = buffer.size()

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
}
