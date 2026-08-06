package com.foodfridge.data.hardware

import android.serialport.SerialPort
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SerialPortManager(
    private val devicePath: String = DEFAULT_DEVICE_PATH,
    private val baudRate: Int = DEFAULT_BAUD_RATE,
    private val dataBits: Int = 8,
    private val stopBits: Int = 1,
    private val parity: Int = 0
) {
    companion object {
        private const val TAG = "SerialPortManager"

        const val DEFAULT_DEVICE_PATH = "/dev/ttyS1"
        const val DEFAULT_BAUD_RATE = 9600

        val SUPPORTED_BAUD_RATES = listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
    }

    private var serialPort: SerialPort? = null
    private var fileInputStream: java.io.FileInputStream? = null
    private var fileOutputStream: java.io.FileOutputStream? = null
    private var isConnected = false

    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isConnected) {
                Log.w(TAG, "Serial port already open")
                return@withContext true
            }

            val device = File(devicePath)
            if (!device.exists()) {
                Log.e(TAG, "Serial port device not found: $devicePath")
                return@withContext false
            }

            try {
                serialPort = SerialPort.newBuilder(devicePath, baudRate)
                    .dataBits(dataBits)
                    .parity(parity)
                    .stopBits(stopBits)
                    .build()

                fileInputStream = serialPort!!.inputStream as java.io.FileInputStream
                fileOutputStream = serialPort!!.outputStream as java.io.FileOutputStream
                isConnected = true

                Log.i(TAG, "JNI SerialPort opened: $devicePath @ $baudRate baud (${dataBits}N${stopBits})")
                true
            } catch (e: Exception) {
                Log.e(TAG, "JNI SerialPort failed for $devicePath, trying fallback", e)
                tryFallbackOpen()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open serial port", e)
            close()
            false
        }
    }

    private fun tryFallbackOpen(): Boolean {
        try {
            if (!File(devicePath).canRead() || !File(devicePath).canWrite()) {
                try {
                    Runtime.getRuntime().exec("chmod 666 $devicePath")
                } catch (_: Exception) { }
            }

            serialPort = SerialPort.newBuilder(devicePath, baudRate)
                .dataBits(dataBits)
                .parity(parity)
                .stopBits(stopBits)
                .build()

            fileInputStream = serialPort!!.inputStream as java.io.FileInputStream
            fileOutputStream = serialPort!!.outputStream as java.io.FileOutputStream
            isConnected = true

            Log.i(TAG, "Fallback JNI SerialPort opened: $devicePath @ $baudRate baud")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback JNI SerialPort also failed", e)
            return false
        }
    }

    fun close() {
        try {
            serialPort?.tryCloseSafely(TAG)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing serial port via JNI", e)
            try {
                fileInputStream?.close()
                fileOutputStream?.close()
            } catch (e2: Exception) {
                Log.e(TAG, "Error closing streams", e2)
            }
        } finally {
            serialPort = null
            fileInputStream = null
            fileOutputStream = null
            isConnected = false
            Log.i(TAG, "Serial port closed")
        }
    }

    suspend fun write(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || fileOutputStream == null) {
                Log.e(TAG, "Serial port not connected")
                return@withContext false
            }

            fileOutputStream?.write(data)
            fileOutputStream?.flush()
            Log.d(TAG, "Sent ${data.size} bytes: ${data.toHexString()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to serial port", e)
            false
        }
    }

    suspend fun read(bufferSize: Int = 256, timeoutMs: Long = 1000): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || fileInputStream == null) {
                Log.e(TAG, "Serial port not connected")
                return@withContext null
            }

            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val available = fileInputStream?.available() ?: 0
                if (available > 0) {
                    val read = fileInputStream?.read(buffer, 0, minOf(available, bufferSize)) ?: 0
                    if (read > 0) {
                        val result = buffer.copyOf(read)
                        Log.d(TAG, "Read $read bytes: ${result.toHexString()}")
                        return@withContext result
                    }
                }
                delay(10)
            }

            Log.w(TAG, "Read timeout after ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read from serial port", e)
            null
        }
    }

    suspend fun sendAndReceive(
        request: ByteArray,
        expectedLength: Int = 256,
        timeoutMs: Long = 2000
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!write(request)) return@withContext null
        delay(50)
        read(expectedLength, timeoutMs)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(" ") { String.format("%02X", it) }
    }
}
