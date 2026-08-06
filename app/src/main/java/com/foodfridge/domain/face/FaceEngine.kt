package com.foodfridge.domain.face

import android.content.Context
import android.graphics.Bitmap

data class FaceRecognitionResult(
    val userId: Int?,
    val userName: String?,
    val similarity: Float,
)

interface FaceEngine {
    fun init(context: Context)

    /** 检测画面中是否有人脸 */
    fun detect(bitmap: Bitmap): Boolean

    /** 检测人脸并返回裁剪后的人脸区域图片（含20%扩展），未检测到则返回 null */
    fun detectAndCropFace(bitmap: Bitmap): Bitmap?

    /** 检测人脸并返回最显著人脸的详细信息，用于判断“注视/正对屏幕” */
    fun detectDetails(bitmap: Bitmap): com.foodfridge.data.face.FaceDetectionDetail? = null

    suspend fun detectAndRecognize(frame: Bitmap): FaceRecognitionResult?
    suspend fun registerUser(userId: Int, frames: List<Bitmap>): Boolean
    fun refreshUserCache()
    fun removeUserFromCache(userId: Int) {
        refreshUserCache()
    }
    fun release()

    /** 检查引擎是否已初始化就绪 */
    fun isReady(): Boolean = true
}
