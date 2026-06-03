package com.foodfridge.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.domain.face.FaceEngine
import com.foodfridge.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val REQUIRED_FRAMES = 3

@HiltViewModel
class FaceEnrollViewModel @Inject constructor(
    private val faceEngine: FaceEngine,
    private val userRepository: UserRepository,
) : ViewModel() {

    var isRegistering by mutableStateOf(false)
        private set

    var message by mutableStateOf("正在初始化人脸引擎...")
        private set

    var success by mutableStateOf(false)
        private set

    var capturedCount by mutableIntStateOf(0)
        private set

    var isEngineReady by mutableStateOf(false)
        private set

    private val capturedFrames = mutableListOf<Bitmap>()
    private val captureLock = Mutex()
    private var currentUserId: Int = 0

    fun init(context: Context, userId: Int) {
        currentUserId = userId
        message = "正在初始化人脸引擎..."
        isEngineReady = false
        viewModelScope.launch(Dispatchers.Default) {
            try {
                faceEngine.init(context)
                val ready = faceEngine.isReady()
                Log.d("FaceEnroll", "FaceEngine init returned ready=$ready")
                if (ready) {
                    // 给 FaceSdk 额外时间完成内部初始化，避免冷启动问题
                    delay(300)
                    Log.d("FaceEnroll", "Warm-up delay done, opening camera")
                }
                isEngineReady = ready
                if (isEngineReady) {
                    message = "请将人脸对准摄像头，系统将自动捕获"
                } else {
                    message = "人脸引擎初始化失败，请重试"
                }
            } catch (e: Exception) {
                Log.e("FaceEnroll", "FaceEngine init failed", e)
                message = "人脸引擎初始化失败: ${e.message}"
                isEngineReady = false
            }
        }
    }

    fun captureFrame(frame: Bitmap) {
        Log.d("FaceEnroll", "captureFrame called, frame=${frame.width}x${frame.height}, isRecycled=${frame.isRecycled}")

        if (isRegistering || success) {
            Log.d("FaceEnroll", "Capture skipped: isRegistering=$isRegistering, success=$success")
            runCatching { frame.recycle() }
            return
        }
        if (!faceEngine.isReady()) {
            Log.w("FaceEnroll", "Engine not ready, skipping frame")
            runCatching { frame.recycle() }
            message = "引擎初始化中，请稍后..."
            return
        }
        if (capturedFrames.size >= REQUIRED_FRAMES) {
            Log.d("FaceEnroll", "Already captured enough frames: ${capturedFrames.size}")
            runCatching { frame.recycle() }
            return
        }

        // Process frame on background thread
        viewModelScope.launch(Dispatchers.Default) {
            processFrame(frame)
        }
    }

    private suspend fun processFrame(frame: Bitmap) {
        try {
            // Ensure frame is ARGB_8888
            val argbFrame = if (frame.config != Bitmap.Config.ARGB_8888) {
                val converted = frame.copy(Bitmap.Config.ARGB_8888, false)
                runCatching { frame.recycle() }
                Log.d("FaceEnroll", "Frame converted to ARGB_8888: ${converted.width}x${converted.height}")
                converted
            } else {
                Log.d("FaceEnroll", "Frame already ARGB_8888: ${frame.width}x${frame.height}")
                frame
            }

            if (argbFrame.isRecycled) {
                Log.e("FaceEnroll", "Frame is already recycled!")
                return
            }

            // Detect face using FaceEngine
            Log.d("FaceEnroll", "Calling faceEngine.detect with frame: ${argbFrame.width}x${argbFrame.height}")
            val hasFace = faceEngine.detect(argbFrame)
            Log.d("FaceEnroll", "Face detection result: hasFace=$hasFace")

            if (!hasFace) {
                Log.d("FaceEnroll", "No face detected, recycling frame")
                runCatching { argbFrame.recycle() }
                withContext(Dispatchers.Main) {
                    message = "未检测到人脸，请将人脸对准摄像头"
                }
                return
            }

            // Create a copy for storage
            val snapshot = argbFrame.copy(Bitmap.Config.ARGB_8888, false)
            runCatching { argbFrame.recycle() }

            if (snapshot == null) {
                Log.e("FaceEnroll", "Failed to create snapshot copy")
                withContext(Dispatchers.Main) {
                    message = "帧处理失败，请重试"
                }
                return
            }

            // Mutex-protect capturedFrames to prevent concurrent registration races
            val currentCount = captureLock.withLock {
                if (isRegistering || success || capturedFrames.size >= REQUIRED_FRAMES) {
                    Log.d("FaceEnroll", "Snapshot discarded: isRegistering=$isRegistering, success=$success, size=${capturedFrames.size}")
                    runCatching { snapshot.recycle() }
                    return
                }
                capturedFrames.add(snapshot)
                capturedFrames.size
            }

            withContext(Dispatchers.Main) {
                capturedCount = currentCount
                message = "已捕获 $currentCount/$REQUIRED_FRAMES 帧，请轻微移动头部"
            }
            Log.d("FaceEnroll", "Frame captured: $currentCount/$REQUIRED_FRAMES")

            if (currentCount >= REQUIRED_FRAMES) {
                withContext(Dispatchers.Main) {
                    message = "捕获完成，正在注册..."
                }
                autoRegister()
            }
        } catch (e: Exception) {
            Log.e("FaceEnroll", "Error processing frame", e)
            runCatching { frame.recycle() }
        }
    }

    private suspend fun autoRegister() {
        if (capturedFrames.isEmpty() || currentUserId <= 0) {
            withContext(Dispatchers.Main) {
                message = "捕获失败，请重试"
            }
            return
        }

        withContext(Dispatchers.Main) {
            isRegistering = true
        }

        try {
            Log.d("FaceEnroll", "Starting registration with ${capturedFrames.size} frames")
            val result = faceEngine.registerUser(currentUserId, capturedFrames.toList())
            Log.d("FaceEnroll", "Registration result: $result")
            withContext(Dispatchers.Main) {
                success = result
                isRegistering = false
                message = if (result) "人脸注册成功！" else "人脸注册失败，请重试"
            }
        } catch (e: Exception) {
            Log.e("FaceEnroll", "人脸注册失败", e)
            withContext(Dispatchers.Main) {
                success = false
                isRegistering = false
                message = "注册失败: ${e.message}"
            }
        } finally {
            capturedFrames.forEach {
                if (!it.isRecycled) runCatching { it.recycle() }
            }
            capturedFrames.clear()
        }
    }

    fun reset() {
        capturedFrames.forEach {
            if (!it.isRecycled) runCatching { it.recycle() }
        }
        capturedFrames.clear()
        capturedCount = 0
        isRegistering = false
        success = false
        message = "请将人脸对准摄像头，系统将自动捕获"
    }

    override fun onCleared() {
        super.onCleared()
        capturedFrames.forEach {
            if (!it.isRecycled) runCatching { it.recycle() }
        }
        capturedFrames.clear()
    }
}
