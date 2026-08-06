package com.foodfridge.data.hardware

import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object KeyboardBarcodeCollector {
    private const val TAG = "KeyboardBarcodeCollector"
    private const val BARCODE_END_TIMEOUT_MS = 150L

    private val _detectedBarcode = MutableStateFlow<String?>(null)
    val detectedBarcode: StateFlow<String?> = _detectedBarcode.asStateFlow()

    private val buffer = StringBuilder()
    private var lastKeyTime = 0L
    private var isActive = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var endDetectionJob: kotlinx.coroutines.Job? = null

    fun setActive(active: Boolean) {
        isActive = active
        if (!active) {
            buffer.clear()
            endDetectionJob?.cancel()
            endDetectionJob = null
            _detectedBarcode.value = null
        }
        Log.d(TAG, "Active state changed to: $active")
    }

    fun processKeyEvent(event: KeyEvent): Boolean {
        if (!isActive || event.action != KeyEvent.ACTION_DOWN) return false

        if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
            Log.d(TAG, "ENTER key received, flushing buffer (len=${buffer.length})")
            flushBuffer()
            return true
        }

        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0 && unicodeChar > 31 && unicodeChar != 127) {
            buffer.append(unicodeChar.toChar())
            lastKeyTime = System.currentTimeMillis()
            val device = event.device
            Log.d(TAG, "Key received: '${unicodeChar.toChar()}' (0x${unicodeChar.toString(16)}), " +
                "buffer length: ${buffer.length}, device: ${device?.name}, isVirtual: ${device?.isVirtual}")

            endDetectionJob?.cancel()
            endDetectionJob = scope.launch {
                delay(BARCODE_END_TIMEOUT_MS)
                if (isActive && buffer.isNotEmpty() &&
                    System.currentTimeMillis() - lastKeyTime >= BARCODE_END_TIMEOUT_MS
                ) {
                    flushBuffer()
                }
            }
            return true
        }

        return false
    }

    private fun flushBuffer() {
        if (buffer.isEmpty()) return
        val barcode = buffer.toString().trim()
        buffer.clear()
        if (barcode.isNotEmpty()) {
            Log.i(TAG, "Keyboard barcode detected: $barcode")
            _detectedBarcode.value = barcode
        }
    }

    fun consumeBarcode() {
        _detectedBarcode.value = null
    }
}
