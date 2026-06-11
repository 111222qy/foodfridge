package com.foodfridge.ui.facecheck

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodfridge.domain.face.FaceEngine
import com.foodfridge.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class FaceRecognitionGateUiState(
    val isRecognizing: Boolean = false,
    val message: String = "请将人脸对准摄像头，全画面实时检测中",
    val errorMessage: String? = null,
    val successToken: Int = 0,
    val matchedUserId: Int? = null,
)

private const val FACE_GATE_AUTO_SCAN_INTERVAL_MS = 500L

@HiltViewModel
class FaceRecognitionGateViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val faceEngine: FaceEngine,
    private val userRepository: UserRepository,
    private val userPrefs: com.foodfridge.data.local.UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FaceRecognitionGateUiState())
    val uiState: StateFlow<FaceRecognitionGateUiState> = _uiState.asStateFlow()
    private var lastAutoScanAtMs: Long = 0L
    // 防止识别成功后继续处理帧，以及返回键时的重复验证
    private val isVerificationCompleted = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                faceEngine.init(appContext)
                Log.d("FaceGateViewModel", "FaceEngine initialized: ${faceEngine.isReady()}")
            } catch (e: Exception) {
                Log.e("FaceGateViewModel", "FaceEngine init failed", e)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun verifyAndContinue(frame: Bitmap?, isAutoScan: Boolean = false) {
        // 识别已完成或正在返回，不再接受新帧
        if (isVerificationCompleted.get()) {
            Log.d("FaceGateViewModel", "Verification already completed, ignoring frame")
            runCatching { frame?.recycle() }
            return
        }

        if (frame == null || frame.isRecycled) {
            Log.w("FaceGateViewModel", "Frame is null or recycled")
            if (!isAutoScan) {
                _uiState.update { it.copy(errorMessage = "摄像头未就绪，请稍后重试") }
            }
            return
        }

        if (!faceEngine.isReady()) {
            Log.w("FaceGateViewModel", "FaceEngine not ready")
            runCatching { frame.recycle() }
            if (!isAutoScan) {
                _uiState.update { it.copy(errorMessage = "人脸识别引擎初始化中，请稍后") }
            }
            return
        }

        if (isAutoScan) {
            val now = System.currentTimeMillis()
            if (now - lastAutoScanAtMs < FACE_GATE_AUTO_SCAN_INTERVAL_MS) {
                runCatching { frame.recycle() }
                return
            }
            lastAutoScanAtMs = now
        }

        // Process on background thread
        viewModelScope.launch(Dispatchers.Default) {
            processFrame(frame, isAutoScan)
        }
    }

    private suspend fun processFrame(frame: Bitmap, isAutoScan: Boolean) {
        // 再次检查，防止已进入队列的任务在识别成功后继续执行
        if (isVerificationCompleted.get()) {
            Log.d("FaceGateViewModel", "processFrame: verification already completed, discarding frame")
            runCatching { frame.recycle() }
            return
        }

        // Ensure frame is ARGB_8888
        val snapshot = runCatching {
            if (frame.config != Bitmap.Config.ARGB_8888) {
                Log.d("FaceGateViewModel", "Converting frame to ARGB_8888")
                frame.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                frame.copy(Bitmap.Config.ARGB_8888, false)
            }
        }.getOrNull()

        runCatching { frame.recycle() }

        if (snapshot == null) {
            Log.e("FaceGateViewModel", "Failed to create snapshot")
            if (!isAutoScan) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "画面获取失败，请重试") }
                }
            }
            return
        }

        try {
            Log.d("FaceGateViewModel", "Processing frame: ${snapshot.width}x${snapshot.height}, config=${snapshot.config}")

            // First detect if there's a face
            val hasFace = faceEngine.detect(snapshot)
            Log.d("FaceGateViewModel", "Face detection result: hasFace=$hasFace")

            if (!hasFace) {
                Log.d("FaceGateViewModel", "No face detected in frame")
                runCatching { snapshot.recycle() }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isRecognizing = false,
                            message = "未检测到人脸，请将人脸对准摄像头",
                        )
                    }
                }
                return
            }

            // detect 完成后再次检查，防止识别成功后继续进入 verify
            if (isVerificationCompleted.get()) {
                Log.d("FaceGateViewModel", "processFrame: verification completed after detect, discarding frame")
                runCatching { snapshot.recycle() }
                return
            }

            processVerifyRequest(snapshot, isAutoScan)
        } catch (e: Exception) {
            Log.e("FaceGateViewModel", "Error processing frame", e)
            runCatching { snapshot.recycle() }
        }
    }

    private suspend fun processVerifyRequest(snapshot: Bitmap, isAutoScan: Boolean) {
        // 进入识别前再次检查，防止排队任务在成功后继续执行
        if (isVerificationCompleted.get()) {
            Log.d("FaceGateViewModel", "processVerifyRequest: verification already completed, discarding frame")
            runCatching { snapshot.recycle() }
            return
        }

        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    isRecognizing = true,
                    errorMessage = null,
                    message = "正在识别人脸，请稍候...",
                )
            }
        }

        try {
            if (snapshot.isRecycled) {
                throw IllegalStateException("Bitmap has been recycled")
            }

            Log.d("FaceGateViewModel", "Calling detectAndRecognize")
            val result = faceEngine.detectAndRecognize(snapshot)
            Log.d("FaceGateViewModel", "Recognition result: userId=${result?.userId}, similarity=${result?.similarity}")

            runCatching { snapshot.recycle() }

            handleRecognitionResult(result, isAutoScan)

        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程正常取消（页面离开），静默处理，不更新UI
            Log.d("FaceGateViewModel", "Face recognition coroutine cancelled")
            runCatching { snapshot.recycle() }
            throw e
        } catch (e: Exception) {
            Log.e("FaceGateViewModel", "Face recognition failed", e)
            runCatching { snapshot.recycle() }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isRecognizing = false,
                        errorMessage = e.message ?: "人脸识别失败，请重试",
                        message = "识别失败，请重新对准摄像头",
                    )
                }
            }
        }
    }

    private suspend fun handleRecognitionResult(result: com.foodfridge.domain.face.FaceRecognitionResult?, isAutoScan: Boolean) {
        // 防止已进入队列的任务重复处理成功结果
        if (isVerificationCompleted.get()) {
            Log.d("FaceGateViewModel", "handleRecognitionResult: verification already completed, ignoring result")
            return
        }

        if (result == null || result.userId == null) {
            Log.d("FaceGateViewModel", "No matching user found, raw similarity=${result?.similarity ?: 0f}")
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isRecognizing = false,
                        errorMessage = if (isAutoScan) null else "未匹配到已注册人员",
                        message = if (isAutoScan) {
                            "未识别到已注册人员，持续扫描中..."
                        } else {
                            "识别失败，请重新对准摄像头"
                        },
                    )
                }
            }
            return
        }

        val userFromDb = withContext(Dispatchers.IO) {
            userRepository.getUserById(result.userId)
        }

        if (userFromDb == null) {
            Log.w("FaceGateViewModel", "User not found in DB: ${result.userId} (face cache has this user but DB does not)")
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isRecognizing = false,
                        errorMessage = if (isAutoScan) null else "识别到的人员在数据库中不存在",
                        message = if (isAutoScan) {
                            "人员数据异常，持续扫描中..."
                        } else {
                            "识别失败，请联系管理员"
                        },
                    )
                }
            }
            return
        }

        if (!userFromDb.isActive) {
            Log.w("FaceGateViewModel", "User inactive: id=${result.userId}, name=${userFromDb.fullName}")
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isRecognizing = false,
                        errorMessage = if (isAutoScan) null else "识别到的人员已停用：${userFromDb.fullName}",
                        message = if (isAutoScan) {
                            "人员已停用，持续扫描中..."
                        } else {
                            "识别失败，请联系管理员"
                        },
                    )
                }
            }
            return
        }

        val matched = userFromDb

        Log.i("FaceGateViewModel", "Recognition successful: ${matched.fullName}")

        // CAS：确保只有一个任务能进入成功处理流程，防止并发任务重复更新UI
        if (!isVerificationCompleted.compareAndSet(false, true)) {
            Log.d("FaceGateViewModel", "Another task already completed verification, ignoring duplicate result")
            return
        }

        userPrefs.saveAuthTokens(
            accessToken = "local_${matched.id}",
            tokenType = "Bearer",
            wsToken = "local_${matched.id}",
            expiresInSeconds = 86400,
        )
        userPrefs.saveUserSession(
            userId = matched.id.toString(),
            userName = matched.fullName,
            lastLoginPassword = matched.password,
        )

        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    isRecognizing = false,
                    errorMessage = null,
                    message = "识别通过：${matched.fullName}",
                    successToken = it.successToken + 1,
                    matchedUserId = matched.id,
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 标记验证完成以防止任何挂起的帧处理
        isVerificationCompleted.set(true)
        // 注意：不调用 faceEngine.release()，因为 SeetaFaceEngine 是 @Singleton
        // 引用计数确保只有应用退出时才真正释放 native 资源
    }
}
