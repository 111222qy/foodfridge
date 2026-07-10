package com.foodfridge.data.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 摄像头资源协调器
 *
 * 管理设备上摄像头的使用状态，解决人脸识别与扫码之间的摄像头冲突问题。
 * 当设备只有一个摄像头时，通过协调器实现分时复用。
 */
@Singleton
class CameraCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "CameraCoordinator"
    }

    enum class CameraPurpose {
        IDLE,
        FACE_RECOGNITION,
        BARCODE_SCAN,
    }

    @Volatile
    private var currentPurpose: CameraPurpose = CameraPurpose.IDLE

    private val lock = Object()

    // 引用计数：同一 purpose 可能被多个 Composable/生命周期同时持有，
    // 只有计数归零时才真正回到 IDLE，避免 double-release 导致状态错乱。
    private val refCounts = mutableMapOf<CameraPurpose, Int>()

    /**
     * 获取当前摄像头用途
     */
    fun getCurrentPurpose(): CameraPurpose = currentPurpose

    /**
     * 判断摄像头是否空闲
     */
    fun isIdle(): Boolean = synchronized(lock) { currentPurpose == CameraPurpose.IDLE }

    /**
     * 尝试获取摄像头使用权。
     * 如果当前用途与请求相同，引用计数 +1；
     * 如果当前为 IDLE，直接占用；
     * 如果当前为其他用途，force=true 时强制抢占（计数重置为 1）。
     */
    fun acquire(purpose: CameraPurpose, force: Boolean = false): Boolean {
        synchronized(lock) {
            return when {
                currentPurpose == CameraPurpose.IDLE -> {
                    currentPurpose = purpose
                    refCounts[purpose] = 1
                    Log.d(TAG, "Camera acquired for purpose: $purpose (count=1)")
                    true
                }
                currentPurpose == purpose -> {
                    refCounts[purpose] = (refCounts[purpose] ?: 0) + 1
                    Log.d(TAG, "Camera re-acquired for purpose: $purpose (count=${refCounts[purpose]})")
                    true
                }
                force -> {
                    Log.w(TAG, "Force acquiring camera from $currentPurpose to $purpose")
                    refCounts.clear()
                    currentPurpose = purpose
                    refCounts[purpose] = 1
                    true
                }
                else -> {
                    Log.w(TAG, "Camera acquisition failed. Current purpose: $currentPurpose, Requested: $purpose")
                    false
                }
            }
        }
    }

    /**
     * 释放摄像头使用权。
     * 引用计数归零后才会回到 IDLE；与当前用途不匹配时只记录警告，不崩溃。
     */
    fun release(purpose: CameraPurpose) {
        synchronized(lock) {
            if (currentPurpose != purpose) {
                Log.w(TAG, "Camera release mismatch. Current: $currentPurpose, Attempted release: $purpose")
                return
            }
            val newCount = (refCounts[purpose] ?: 1) - 1
            if (newCount <= 0) {
                refCounts.remove(purpose)
                currentPurpose = CameraPurpose.IDLE
                Log.d(TAG, "Camera released from purpose: $purpose (idle now)")
            } else {
                refCounts[purpose] = newCount
                Log.d(TAG, "Camera partial release for purpose: $purpose (count=$newCount)")
            }
        }
    }

    /**
     * 强制释放摄像头（用于页面退出等场景）
     */
    fun forceRelease() {
        synchronized(lock) {
            val oldPurpose = currentPurpose
            refCounts.clear()
            currentPurpose = CameraPurpose.IDLE
            Log.d(TAG, "Camera force released from purpose: $oldPurpose")
        }
    }

    /**
     * 检测设备摄像头配置
     *
     * @return 摄像头描述列表
     */
    fun enumerateCameras(): List<CameraInfo> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.map { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingLabel = when (lensFacing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                CameraInfo(
                    cameraId = cameraId,
                    lensFacing = facingLabel,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate cameras", e)
            emptyList()
        }
    }

    /**
     * 判断设备是否有后置摄像头
     */
    fun hasBackCamera(): Boolean {
        return enumerateCameras().any { it.lensFacing == "back" }
    }

    /**
     * 判断设备是否有前置摄像头
     */
    fun hasFrontCamera(): Boolean {
        return enumerateCameras().any { it.lensFacing == "front" }
    }

    /**
     * 判断设备是否有外接摄像头
     */
    fun hasExternalCamera(): Boolean {
        return enumerateCameras().any { it.lensFacing == "external" }
    }

    /**
     * 获取外接摄像头的 CameraSelector
     */
    private fun externalCameraSelector(): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
            .build()
    }

    /**
     * 获取推荐的扫码摄像头选择器
     *
     * 优先使用外接摄像头（USB），其次前置，最后后置。
     */
    fun getRecommendedBarcodeCameraSelector(): CameraSelector {
        return when {
            hasExternalCamera() -> externalCameraSelector()
            hasFrontCamera() -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    /**
     * 获取推荐的人脸识别摄像头选择器
     *
     * 优先使用前置摄像头
     */
    fun getRecommendedFaceCameraSelector(): CameraSelector {
        return if (hasFrontCamera()) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    /**
     * 判断设备是否为单摄像头设备
     */
    fun isSingleCameraDevice(): Boolean {
        return enumerateCameras().size <= 1
    }

    data class CameraInfo(
        val cameraId: String,
        val lensFacing: String,
    )
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface CameraCoordinatorEntryPoint {
    fun cameraCoordinator(): CameraCoordinator
}
