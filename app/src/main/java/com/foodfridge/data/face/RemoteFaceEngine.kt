package com.foodfridge.data.face

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.foodfridge.data.face.ipc.FaceIpcContract
import com.foodfridge.data.face.ipc.IRemoteFaceService
import com.foodfridge.domain.face.FaceEngine
import com.foodfridge.domain.face.FaceRecognitionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteFaceEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : FaceEngine {

    private val lock = Any()

    @Volatile
    private var remoteService: IRemoteFaceService? = null

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    @Volatile
    private var connectLatch: CountDownLatch? = null

    @Volatile
    private var isBinding = false

    override fun isReady(): Boolean {
        return remoteService != null
    }

    override fun detect(bitmap: Bitmap): Boolean {
        // RemoteFaceEngine 用于完整的人脸识别流程，不用于实时帧检测
        // 主界面的人脸检测使用 SeetaFaceEngine（本地引擎）
        return false
    }

    override fun init(context: Context) {
        val service = ensureRemoteService() ?: return
        runCatching { service.init() }
            .onFailure { Timber.e(it, "RemoteFaceEngine init failed") }
    }

    override suspend fun detectAndRecognize(frame: Bitmap): FaceRecognitionResult? = withContext(Dispatchers.IO) {
        if (frame.isRecycled) return@withContext null

        val service = ensureRemoteService() ?: return@withContext null
        val bundle = runCatching {
            service.detectAndRecognize(frame)
        }.onFailure {
            Timber.e(it, "RemoteFaceEngine detectAndRecognize failed")
            onRemoteCallFailed(it)
        }.getOrNull() ?: return@withContext null

        val hasResult = bundle.getBoolean(FaceIpcContract.KEY_HAS_RESULT, false)
        val similarity = bundle.getFloat(FaceIpcContract.KEY_SIMILARITY, 0f)
        val userId = bundle.getInt(FaceIpcContract.KEY_USER_ID, -1).takeIf { it >= 0 }
        val userName = bundle.getString(FaceIpcContract.KEY_USER_NAME)

        if (!hasResult || userId == null) {
            return@withContext FaceRecognitionResult(
                userId = null,
                userName = null,
                similarity = similarity,
            )
        }

        FaceRecognitionResult(
            userId = userId,
            userName = userName,
            similarity = similarity,
        )
    }

    override suspend fun registerUser(userId: Int, frames: List<Bitmap>): Boolean = withContext(Dispatchers.IO) {
        val validFrames = frames.filterNot { it.isRecycled }
        if (validFrames.isEmpty()) return@withContext false

        val service = ensureRemoteService() ?: return@withContext false
        runCatching {
            service.registerUser(userId, ArrayList(validFrames))
        }.onFailure {
            Timber.e(it, "RemoteFaceEngine registerUser failed userId=%d", userId)
            onRemoteCallFailed(it)
        }.getOrDefault(false)
    }

    override fun refreshUserCache() {
        val service = ensureRemoteService() ?: return
        runCatching { service.refreshUserCache() }
            .onFailure {
                Timber.e(it, "RemoteFaceEngine refreshUserCache failed")
                onRemoteCallFailed(it)
            }
    }

    override fun release() {
        remoteService?.let { service ->
            runCatching { service.release() }
                .onFailure { Timber.e(it, "RemoteFaceEngine release remote failed") }
        }

        synchronized(lock) {
            remoteService = null
            isBinding = false
            connectLatch?.countDown()
            connectLatch = null
            serviceConnection?.let {
                runCatching { appContext.unbindService(it) }
            }
            serviceConnection = null
        }
    }

    private fun ensureRemoteService(): IRemoteFaceService? {
        remoteService?.let { return it }

        val latchToWait: CountDownLatch = synchronized(lock) {
            remoteService?.let { return it }

            val existingLatch = connectLatch
            if (existingLatch != null) {
                return@synchronized existingLatch
            }

            val newLatch = CountDownLatch(1)
            connectLatch = newLatch

            if (!isBinding) {
                isBinding = true
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        synchronized(lock) {
                            remoteService = IRemoteFaceService.Stub.asInterface(binder)
                            isBinding = false
                            connectLatch?.countDown()
                            connectLatch = null
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        synchronized(lock) {
                            remoteService = null
                            isBinding = false
                        }
                    }

                    override fun onBindingDied(name: ComponentName) {
                        synchronized(lock) {
                            remoteService = null
                            isBinding = false
                        }
                    }

                    override fun onNullBinding(name: ComponentName) {
                        synchronized(lock) {
                            remoteService = null
                            isBinding = false
                            connectLatch?.countDown()
                            connectLatch = null
                        }
                    }
                }
                serviceConnection = connection

                val intent = Intent(appContext, FaceProcessService::class.java)
                val bound = runCatching {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)

                if (!bound) {
                    isBinding = false
                    serviceConnection = null
                    connectLatch?.countDown()
                    connectLatch = null
                    Timber.e("RemoteFaceEngine bind service failed")
                }
            }

            newLatch
        }

        latchToWait.await(FaceIpcContract.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return remoteService
    }

    private fun onRemoteCallFailed(throwable: Throwable) {
        if (throwable is RemoteException || throwable is DeadObjectException) {
            synchronized(lock) {
                remoteService = null
            }
        }
    }
}
