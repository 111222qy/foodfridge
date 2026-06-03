package com.foodfridge.data.face

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import com.foodfridge.data.face.ipc.FaceIpcContract
import com.foodfridge.data.face.ipc.IRemoteFaceService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class FaceProcessService : Service() {

    @Inject
    lateinit var seetaFaceEngine: SeetaFaceEngine

    private val binder = object : IRemoteFaceService.Stub() {
        override fun init() {
            runCatching { seetaFaceEngine.init(applicationContext) }
                .onFailure { Timber.e(it, "FaceProcess init failed") }
        }

        override fun detectAndRecognize(frame: Bitmap): Bundle {
            if (frame.isRecycled) return Bundle()

            val result = runCatching {
                runBlocking {
                    seetaFaceEngine.detectAndRecognize(frame)
                }
            }.onFailure {
                Timber.e(it, "FaceProcess detectAndRecognize failed")
            }.getOrNull() ?: return Bundle()

            return Bundle().apply {
                putBoolean(FaceIpcContract.KEY_HAS_RESULT, result.userId != null)
                putInt(FaceIpcContract.KEY_USER_ID, result.userId ?: -1)
                putString(FaceIpcContract.KEY_USER_NAME, result.userName)
                putFloat(FaceIpcContract.KEY_SIMILARITY, result.similarity)
            }
        }

        override fun registerUser(userId: Int, frames: MutableList<Bitmap>): Boolean {
            val validFrames = frames.filterNot { it.isRecycled }
            if (validFrames.isEmpty()) return false

            return runCatching {
                runBlocking {
                    seetaFaceEngine.registerUser(userId, validFrames)
                }
            }.onFailure {
                Timber.e(it, "FaceProcess registerUser failed userId=%d", userId)
            }.getOrDefault(false)
        }

        override fun refreshUserCache() {
            runCatching { seetaFaceEngine.refreshUserCache() }
                .onFailure { Timber.e(it, "FaceProcess refreshUserCache failed") }
        }

        override fun release() {
            runCatching { seetaFaceEngine.release() }
                .onFailure { Timber.e(it, "FaceProcess release failed") }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        runCatching { seetaFaceEngine.release() }
        super.onDestroy()
    }
}
