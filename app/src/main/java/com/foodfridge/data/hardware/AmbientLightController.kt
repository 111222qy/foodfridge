package com.foodfridge.data.hardware

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 环境亮度自动补光控制器。
 *
 * 工作原理：
 * - 从摄像头帧计算平均亮度（Y 通道）
 * - 亮度低于 [LIGHT_ON_THRESHOLD] 时快速开补光灯
 * - 亮度高于 [LIGHT_OFF_THRESHOLD] 时延迟关补光灯
 * - 中间区间保持当前状态（迟滞防抖）
 * - 开灯灵敏、关灯保守，避免灯光照亮画面后立即把自己关掉
 *
 * 使用方式：
 * - 在 FridgeHomeViewModel.init 启动 [start]
 * - 摄像头帧通过 [onFrame] 传入（已由人脸检测链路提供）
 * - FridgeHomeViewModel.onCleared 调用 [stop]（离开首页自动关灯）
 */
class AmbientLightController(
    private val hardwareManager: HardwareManager,
) {
    companion object {
        private const val TAG = "AmbientLight"

        /** Y 平均值低于此值开灯。相机自动曝光下无需等到画面接近全黑。 */
        const val LIGHT_ON_THRESHOLD = 85

        /** 使用较宽迟滞区间，降低补光灯自身造成误关灯的概率。 */
        const val LIGHT_OFF_THRESHOLD = 130

        /** 暗场连续采样次数，配合 250ms 采样可在 0.5 秒内响应。 */
        const val DARK_SAMPLES_REQUIRED = 2

        /** 明亮场景需要持续更久才关灯，避免曝光波动造成闪烁。 */
        const val BRIGHT_SAMPLES_REQUIRED = 16

        /** 开灯后至少保持一段时间，再允许自动关灯。 */
        const val MIN_LIGHT_ON_DURATION_MS = 10_000L

        /** 与首页相机分析帧率一致，不额外引入一秒级等待。 */
        const val SAMPLE_INTERVAL_MS = 250L

        /** 亮度诊断日志间隔，用于实机标定阈值。 */
        private const val BRIGHTNESS_LOG_INTERVAL_MS = 1_000L
    }

    enum class LightState {
        ON, OFF, UNKNOWN
    }

    private val _lightState = MutableStateFlow(LightState.UNKNOWN)
    val lightState: StateFlow<LightState> = _lightState.asStateFlow()

    private val _currentBrightness = MutableStateFlow<Int?>(null)
    val currentBrightness: StateFlow<Int?> = _currentBrightness.asStateFlow()

    @Volatile
    private var lastSampleAtMs: Long = 0L

    @Volatile
    private var lastBrightnessLogAtMs: Long = 0L

    @Volatile
    private var lightTurnedOnAtMs: Long? = null

    @Volatile
    private var consecutiveDarkFrames = 0

    @Volatile
    private var consecutiveBrightFrames = 0

    @Volatile
    private var isStarted = false

    /** 启动自动补光。重复调用安全（幂等）。 */
    @Synchronized
    fun start() {
        if (isStarted) {
            Log.d(TAG, "Already started, skip")
            return
        }
        isStarted = true
        lastSampleAtMs = 0L
        lastBrightnessLogAtMs = 0L
        lightTurnedOnAtMs = null
        consecutiveDarkFrames = 0
        consecutiveBrightFrames = 0
        Log.i(TAG, "Auto fill light started")
    }

    /** 停止自动补光，并关闭补光灯（避免离开首页后灯常亮）。 */
    @Synchronized
    fun stop() {
        isStarted = false
        consecutiveDarkFrames = 0
        consecutiveBrightFrames = 0
        lightTurnedOnAtMs = null

        // 即使状态被其他硬件操作改变，也在离开页面时明确关灯。
        if (hardwareManager.lightOff()) {
            _lightState.value = LightState.OFF
            Log.i(TAG, "Auto fill light stopped, light turned OFF")
        } else {
            _lightState.value = LightState.UNKNOWN
            Log.w(TAG, "Auto fill light stopped, but light OFF command failed")
        }
    }

    /**
     * 接收一帧摄像头画面，按需采样计算亮度。
     * 由人脸检测链路在 ImageAnalysis 回调中调用。
     */
    @Synchronized
    fun onFrame(bitmap: Bitmap) {
        if (!isStarted) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAtMs < SAMPLE_INTERVAL_MS) return
        lastSampleAtMs = now

        val y = calculateAverageBrightness(bitmap) ?: return
        _currentBrightness.value = y

        syncLightStateFromHardware(now)
        val canTurnOff = _lightState.value != LightState.ON ||
            lightTurnedOnAtMs?.let { now - it >= MIN_LIGHT_ON_DURATION_MS } != false
        val decision = decideAmbientLight(
            brightness = y,
            lightState = _lightState.value,
            consecutiveDarkSamples = consecutiveDarkFrames,
            consecutiveBrightSamples = consecutiveBrightFrames,
            canTurnOff = canTurnOff,
        )
        consecutiveDarkFrames = decision.consecutiveDarkSamples
        consecutiveBrightFrames = decision.consecutiveBrightSamples

        if (now - lastBrightnessLogAtMs >= BRIGHTNESS_LOG_INTERVAL_MS) {
            lastBrightnessLogAtMs = now
            Log.d(
                TAG,
                "brightness=$y state=${_lightState.value} darkSamples=$consecutiveDarkFrames " +
                    "brightSamples=$consecutiveBrightFrames",
            )
        }

        when (decision.command) {
            AmbientLightCommand.TURN_ON -> turnOn(now)
            AmbientLightCommand.TURN_OFF -> turnOff()
            AmbientLightCommand.NONE -> Unit
        }
    }

    private fun syncLightStateFromHardware(now: Long) {
        val hardwareLightIsOn = hardwareManager.isFillLightOn()
        when {
            hardwareLightIsOn && _lightState.value != LightState.ON -> {
                _lightState.value = LightState.ON
                lightTurnedOnAtMs = now
                consecutiveDarkFrames = 0
                Log.d(TAG, "Light state synchronized to ON")
            }
            !hardwareLightIsOn && _lightState.value == LightState.ON -> {
                _lightState.value = LightState.OFF
                lightTurnedOnAtMs = null
                consecutiveBrightFrames = 0
                Log.d(TAG, "Light state synchronized to OFF")
            }
        }
    }

    private fun turnOn(now: Long) {
        val success = hardwareManager.lightOn()
        if (success) {
            _lightState.value = LightState.ON
            lightTurnedOnAtMs = now
            Log.i(TAG, "Auto fill light ON (brightness=${_currentBrightness.value})")
        }
        consecutiveDarkFrames = 0
    }

    private fun turnOff() {
        val success = hardwareManager.lightOff()
        if (success) {
            _lightState.value = LightState.OFF
            lightTurnedOnAtMs = null
            Log.i(TAG, "Auto fill light OFF (brightness=${_currentBrightness.value})")
        }
        consecutiveBrightFrames = 0
    }

    /**
     * 计算 Bitmap 平均亮度（Y 通道）。
     * 使用 RGB 加权公式：Y ≈ 0.299R + 0.587G + 0.114B
     * 返回值 0~255，越低越暗。
     *
     * 为性能考虑，对图片做采样而非逐像素：取 1/4 像素即可。
     */
    internal fun calculateAverageBrightness(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled) return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        return try {
            var sum = 0L
            var count = 0
            // 每 2 个像素采一个，降低 CPU 占用
            val step = 2
            val row = IntArray(width)
            var y = 0
            while (y < height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                var x = 0
                while (x < width) {
                    val pixel = row[x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    // ITU-R BT.601 luma
                    val luma = (299 * r + 587 * g + 114 * b) / 1000
                    sum += luma
                    count++
                    x += step
                }
                y += step
            }
            if (count == 0) null else (sum / count).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate brightness", e)
            null
        }
    }
}

internal enum class AmbientLightCommand {
    NONE,
    TURN_ON,
    TURN_OFF,
}

internal data class AmbientLightDecision(
    val command: AmbientLightCommand,
    val consecutiveDarkSamples: Int,
    val consecutiveBrightSamples: Int,
)

/**
 * 纯亮度决策逻辑，与 Android 硬件调用分离，便于验证快速开灯和稳定关灯策略。
 */
internal fun decideAmbientLight(
    brightness: Int,
    lightState: AmbientLightController.LightState,
    consecutiveDarkSamples: Int,
    consecutiveBrightSamples: Int,
    canTurnOff: Boolean,
): AmbientLightDecision {
    return when {
        brightness < AmbientLightController.LIGHT_ON_THRESHOLD -> {
            if (lightState == AmbientLightController.LightState.ON) {
                return AmbientLightDecision(
                    command = AmbientLightCommand.NONE,
                    consecutiveDarkSamples = 0,
                    consecutiveBrightSamples = 0,
                )
            }
            val darkSamples = consecutiveDarkSamples + 1
            val shouldTurnOn =
                darkSamples >= AmbientLightController.DARK_SAMPLES_REQUIRED
            AmbientLightDecision(
                command = if (shouldTurnOn) AmbientLightCommand.TURN_ON else AmbientLightCommand.NONE,
                consecutiveDarkSamples = if (shouldTurnOn) 0 else darkSamples,
                consecutiveBrightSamples = 0,
            )
        }

        brightness > AmbientLightController.LIGHT_OFF_THRESHOLD -> {
            if (lightState == AmbientLightController.LightState.OFF) {
                return AmbientLightDecision(
                    command = AmbientLightCommand.NONE,
                    consecutiveDarkSamples = 0,
                    consecutiveBrightSamples = 0,
                )
            }
            val brightSamples = if (canTurnOff) consecutiveBrightSamples + 1 else 0
            val shouldTurnOff =
                canTurnOff &&
                    brightSamples >= AmbientLightController.BRIGHT_SAMPLES_REQUIRED
            AmbientLightDecision(
                command = if (shouldTurnOff) AmbientLightCommand.TURN_OFF else AmbientLightCommand.NONE,
                consecutiveDarkSamples = 0,
                consecutiveBrightSamples = if (shouldTurnOff) 0 else brightSamples,
            )
        }

        else -> AmbientLightDecision(
            command = AmbientLightCommand.NONE,
            consecutiveDarkSamples = 0,
            consecutiveBrightSamples = 0,
        )
    }
}
