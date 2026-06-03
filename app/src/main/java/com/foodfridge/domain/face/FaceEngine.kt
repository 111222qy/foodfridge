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

    suspend fun detectAndRecognize(frame: Bitmap): FaceRecognitionResult?
    suspend fun registerUser(userId: Int, frames: List<Bitmap>): Boolean
    fun refreshUserCache()
    fun release()
    
    /** 检查引擎是否已初始化就绪 */
    fun isReady(): Boolean = true
}
