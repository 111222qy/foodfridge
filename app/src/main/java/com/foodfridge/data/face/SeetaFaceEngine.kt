package com.foodfridge.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.smartcheck.sdk.face.FaceSdk
import com.foodfridge.domain.face.FaceEngine
import com.foodfridge.domain.face.FaceRecognitionResult
import com.foodfridge.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SeetaFaceEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userRepository: UserRepository,
) : FaceEngine {

    companion object {
        // 进一步降低阈值适配红外黑白摄像头（相似度通常比彩色图像低0.15-0.25）
        private const val MULTI_USER_MATCH_THRESHOLD = 0.55f
        private const val SINGLE_USER_MATCH_THRESHOLD = 0.60f
        private const val MIN_TOP_GAP = 0.01f
        private const val FACE_CROP_EXPAND_RATIO = 0.20f
    }

    private data class CachedUser(
        val id: Int,
        val name: String,
        val feature: FloatArray,
    )

    private val isInitialized = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val initInProgress = AtomicBoolean(false)
    private val userFeatureCache = ConcurrentHashMap<Int, CachedUser>()
    private val initLock = Any()
    // Lock for native FaceSdk calls - FaceSdk is NOT thread-safe
    private val nativeLock = Any()
    // Reference count for singleton lifecycle - only release when count reaches 0
    private val refCount = AtomicInteger(0)
    @Volatile
    private var lastPerfLogAt = 0L

    override fun isReady(): Boolean {
        return isInitialized.get()
    }

    override fun detect(bitmap: Bitmap): Boolean {
        if (!isInitialized.get()) {
            Log.d("SeetaFaceEngine", "detect called but not initialized, starting async init")
            startAsyncInit(appContext)
            return false
        }

        if (bitmap.isRecycled) {
            Log.w("SeetaFaceEngine", "detect called with recycled bitmap")
            return false
        }

        return try {
            Log.d("SeetaFaceEngine", "Calling FaceSdk.detect with bitmap ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")
            // FaceSdk native methods are NOT thread-safe, synchronize all calls
            val faces = synchronized(nativeLock) {
                FaceSdk.detect(bitmap)
            }
            Log.d("SeetaFaceEngine", "FaceSdk.detect returned ${faces.size} faces")
            faces.isNotEmpty()
        } catch (e: Exception) {
            Log.e("SeetaFaceEngine", "Face detection failed", e)
            false
        }
    }

    override fun init(context: Context) {
        refCount.incrementAndGet()
        ensureInit(context, "sync")
    }

    private fun startAsyncInit(context: Context) {
        if (isInitialized.get()) return
        if (!initInProgress.compareAndSet(false, true)) return
        Thread {
            try {
                ensureInit(context, "async")
            } finally {
                initInProgress.set(false)
            }
        }.start()
    }

    private fun ensureInit(context: Context, source: String) {
        if (isInitialized.get()) return
        synchronized(initLock) {
            if (isInitialized.get()) return
            val start = SystemClock.elapsedRealtime()
            val ret = synchronized(nativeLock) {
                FaceSdk.init(context)
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            if (ret != 0) {
                Log.e("SeetaFaceEngine", "init failed ($source): ret=$ret, err=${FaceSdk.getLastInitError()}, time=${elapsed}ms")
                return
            }
            isInitialized.set(true)

            // Warm-up: ensure native detector is fully ready before accepting real frames
            val warmUpBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                val warmUpFaces = synchronized(nativeLock) {
                    FaceSdk.detect(warmUpBitmap)
                }
                Log.i("SeetaFaceEngine", "init ok ($source) time=${elapsed}ms, warm-up detect returned ${warmUpFaces.size} faces")
            } catch (e: Exception) {
                Log.w("SeetaFaceEngine", "Warm-up detect failed (non-critical), engine may still work", e)
            } finally {
                warmUpBitmap.recycle()
            }

            refreshUserCache()
        }
    }

    override suspend fun detectAndRecognize(frame: Bitmap): FaceRecognitionResult? {
        if (!isInitialized.get()) {
            Log.d("SeetaFaceEngine", "detectAndRecognize called but not initialized")
            startAsyncInit(appContext)
            return null
        }
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d("SeetaFaceEngine", "detectAndRecognize skipped: already processing")
            return null
        }

        return try {
            withContext(Dispatchers.Default) {
                val totalStart = SystemClock.elapsedRealtime()
                val detectStart = SystemClock.elapsedRealtime()
                val faces = synchronized(nativeLock) {
                    FaceSdk.detect(frame)
                }
                val detectMs = SystemClock.elapsedRealtime() - detectStart
                Log.d("SeetaFaceEngine", "detectAndRecognize: detected ${faces.size} faces in ${detectMs}ms")

                if (faces.isEmpty()) {
                    maybeLogPerf(
                        totalMs = SystemClock.elapsedRealtime() - totalStart,
                        detectMs = detectMs,
                        featureMs = 0L,
                        compareMs = 0L,
                        users = userFeatureCache.size,
                        faces = 0,
                        bestSim = 0f,
                    )
                    return@withContext null
                }

                val bestFace = faces.maxByOrNull { it.score }
                val bbox = bestFace?.box?.let {
                    Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                } ?: Rect()

                Log.d("SeetaFaceEngine", "Best face score: ${bestFace?.score}, bbox: $bbox")

                val featureStart = SystemClock.elapsedRealtime()
                val featureBitmap = if (!bbox.isEmpty) cropFaceBitmap(frame, bbox) else frame
                val feature = try {
                    synchronized(nativeLock) {
                        FaceSdk.extractFeature(featureBitmap)
                    }
                } finally {
                    if (featureBitmap !== frame && !featureBitmap.isRecycled) {
                        featureBitmap.recycle()
                    }
                }
                val featureMs = SystemClock.elapsedRealtime() - featureStart
                Log.d("SeetaFaceEngine", "Feature extraction took ${featureMs}ms, feature=${feature != null}")

                if (feature == null) {
                    Log.w("SeetaFaceEngine", "Feature extraction returned null for best face")
                    maybeLogPerf(
                        totalMs = SystemClock.elapsedRealtime() - totalStart,
                        detectMs = detectMs,
                        featureMs = featureMs,
                        compareMs = 0L,
                        users = userFeatureCache.size,
                        faces = faces.size,
                        bestSim = 0f,
                    )
                    return@withContext FaceRecognitionResult(
                        userId = null,
                        userName = null,
                        similarity = 0f,
                    )
                }

                Log.d("SeetaFaceEngine", "Feature extracted: size=${feature.size}, cacheUsers=${userFeatureCache.size}")

                if (userFeatureCache.isEmpty()) {
                    refreshUserCache()
                }
                val cachedUsers = userFeatureCache.values.toList()
                if (cachedUsers.isEmpty()) {
                    Log.d("SeetaFaceEngine", "No cached users for recognition")
                    return@withContext FaceRecognitionResult(
                        userId = null,
                        userName = null,
                        similarity = 0f,
                    )
                }

                var best: CachedUser? = null
                var bestScore = 0f
                var secondScore = 0f

                val compareStart = SystemClock.elapsedRealtime()
                for (candidate in cachedUsers) {
                    if (candidate.feature.size != feature.size) continue
                    val sim = synchronized(nativeLock) {
                        FaceSdk.calculateSimilarity(candidate.feature, feature)
                    }
                    if (!sim.isFinite()) continue

                    if (sim > bestScore) {
                        secondScore = bestScore
                        bestScore = sim
                        best = candidate
                    } else if (sim > secondScore) {
                        secondScore = sim
                    }
                }
                val compareMs = SystemClock.elapsedRealtime() - compareStart

                maybeLogPerf(
                    totalMs = SystemClock.elapsedRealtime() - totalStart,
                    detectMs = detectMs,
                    featureMs = featureMs,
                    compareMs = compareMs,
                    users = cachedUsers.size,
                    faces = faces.size,
                    bestSim = bestScore,
                )

                val threshold = if (cachedUsers.size <= 1) {
                    SINGLE_USER_MATCH_THRESHOLD
                } else {
                    MULTI_USER_MATCH_THRESHOLD
                }
                val minGap = if (cachedUsers.size <= 1) 0f else MIN_TOP_GAP
                val topGap = if (secondScore > 0f) bestScore - secondScore else Float.MAX_VALUE

                Log.d("SeetaFaceEngine", "Recognition: best=${best?.name}($bestScore), second=$secondScore, threshold=$threshold, gap=$topGap, users=${cachedUsers.size}")

                if (best != null && bestScore >= threshold && topGap >= minGap) {
                    FaceRecognitionResult(
                        userId = best.id,
                        userName = best.name,
                        similarity = bestScore,
                    )
                } else {
                    if (best != null) {
                        Log.d("SeetaFaceEngine", "Rejected: score=$bestScore < threshold=$threshold or gap=$topGap < minGap=$minGap")
                    }
                    FaceRecognitionResult(
                        userId = null,
                        userName = null,
                        similarity = bestScore,
                    )
                }
            }
        } finally {
            isProcessing.set(false)
        }
    }

    override suspend fun registerUser(userId: Int, frames: List<Bitmap>): Boolean {
        if (!isInitialized.get()) {
            Log.w("SeetaFaceEngine", "register called before init, trigger async init")
            startAsyncInit(appContext)
            val waitUntil = SystemClock.elapsedRealtime() + 500L
            while (!isInitialized.get() && SystemClock.elapsedRealtime() < waitUntil) {
                delay(50)
            }
            if (!isInitialized.get()) {
                Log.e("SeetaFaceEngine", "register failed: init timeout")
                return false
            }
        }

        return withContext(Dispatchers.Default) {
            val user = userRepository.getUserById(userId)
            if (user == null) {
                Log.w("SeetaFaceEngine", "registerUser: user not found, userId=$userId")
                return@withContext false
            }

            if (frames.isEmpty()) {
                Log.w("SeetaFaceEngine", "registerUser: empty frames, userId=$userId")
                return@withContext false
            }

            Log.d("SeetaFaceEngine", "registerUser: processing ${frames.size} frames for userId=$userId")

            var sumFeature: FloatArray? = null
            var validCount = 0

            // 提前退出目标：至少3帧有效即可停止，不必处理所有帧
            val targetValidCount = minOf(3, frames.size)

            for (index in frames.indices) {
                val frame = frames[index]
                // 提前退出：已收集足够有效帧
                if (validCount >= targetValidCount) {
                    Log.d("SeetaFaceEngine", "registerUser: early stop at frame $index, validCount=$validCount >= target=$targetValidCount")
                    break
                }

                if (frame.isRecycled) {
                    Log.w("SeetaFaceEngine", "registerUser: frame $index is recycled")
                    continue
                }

                Log.d("SeetaFaceEngine", "registerUser: processing frame $index, ${frame.width}x${frame.height}")
                val faces = synchronized(nativeLock) {
                    FaceSdk.detect(frame)
                }
                Log.d("SeetaFaceEngine", "registerUser: frame $index detected ${faces.size} faces")

                if (faces.isEmpty()) {
                    Log.d("SeetaFaceEngine", "registerUser: no face in frame $index")
                    continue
                }

                val bestFace = faces.maxByOrNull { it.score }
                val bbox = bestFace?.box?.let {
                    Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                } ?: Rect()
                val featureBitmap = if (!bbox.isEmpty) cropFaceBitmap(frame, bbox) else frame
                val feature = try {
                    synchronized(nativeLock) {
                        FaceSdk.extractFeature(featureBitmap)
                    }
                } finally {
                    if (featureBitmap !== frame && !featureBitmap.isRecycled) {
                        featureBitmap.recycle()
                    }
                }
                if (feature == null) {
                    Log.d("SeetaFaceEngine", "registerUser: failed to extract feature from frame $index")
                    continue
                }

                if (sumFeature == null) {
                    sumFeature = FloatArray(feature.size)
                }
                if (sumFeature!!.size != feature.size) {
                    Log.w("SeetaFaceEngine", "registerUser: feature size mismatch in frame $index")
                    continue
                }
                for (idx in feature.indices) {
                    sumFeature!![idx] += feature[idx]
                }
                validCount++
                Log.d("SeetaFaceEngine", "registerUser: frame $index processed successfully")
            }

            if (sumFeature == null || validCount == 0) {
                Log.w("SeetaFaceEngine", "registerUser: no valid face features, userId=$userId")
                return@withContext false
            }

            for (idx in sumFeature!!.indices) {
                sumFeature!![idx] /= validCount.toFloat()
            }

            val updated = user.copy(faceEmbedding = floatArrayToByteArray(sumFeature!!))
            userRepository.updateUser(updated)

            userFeatureCache[userId] = CachedUser(
                id = user.id,
                name = user.fullName,
                feature = sumFeature!!,
            )

            Log.i("SeetaFaceEngine", "registerUser success userId=$userId samples=$validCount")
            true
        }
    }

    override fun refreshUserCache() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread {
                refreshUserCacheInternal()
            }.start()
            return
        }
        refreshUserCacheInternal()
    }

    private fun refreshUserCacheInternal() {
        ensureInit(appContext, "refresh")
        if (!isInitialized.get()) return

        try {
            val users = runCatching {
                runBlocking {
                    userRepository.getAllUsers().first()
                }
            }.getOrElse {
                Log.e("SeetaFaceEngine", "refreshUserCache failed to load users", it)
                return
            }

            val newCache = ConcurrentHashMap<Int, CachedUser>()
            for (user in users) {
                val bytes = user.faceEmbedding ?: continue
                val feature = byteArrayToFloatArray(bytes) ?: continue
                newCache[user.id] = CachedUser(
                    id = user.id,
                    name = user.fullName,
                    feature = feature,
                )
            }

            userFeatureCache.clear()
            userFeatureCache.putAll(newCache)
            Log.d("SeetaFaceEngine", "User cache refreshed: ${newCache.size} users")
        } catch (e: Exception) {
            Log.e("SeetaFaceEngine", "refreshUserCache failed", e)
        }
    }

    override fun release() {
        val count = refCount.decrementAndGet()
        Log.d("SeetaFaceEngine", "release called, refCount=$count")
        if (count <= 0) {
            synchronized(nativeLock) {
                runCatching { FaceSdk.release() }
            }
            isInitialized.set(false)
            userFeatureCache.clear()
            refCount.set(0)
            Log.i("SeetaFaceEngine", "FaceEngine fully released")
        }
    }

    private fun maybeLogPerf(
        totalMs: Long,
        detectMs: Long,
        featureMs: Long,
        compareMs: Long,
        users: Int,
        faces: Int,
        bestSim: Float,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerfLogAt < 2000L) return
        lastPerfLogAt = now
        Log.d("SeetaFaceEngine", "Perf: total=${totalMs}ms detect=${detectMs}ms feature=${featureMs}ms compare=${compareMs}ms users=$users faces=$faces bestSim=$bestSim")
    }

    private fun floatArrayToByteArray(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { value -> buffer.putFloat(value) }
        return buffer.array()
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray? {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val floatCount = bytes.size / 4
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(floatCount)
        for (index in 0 until floatCount) {
            result[index] = buffer.getFloat()
        }
        return result
    }

    private fun cropFaceBitmap(source: Bitmap, box: Rect): Bitmap {
        if (box.isEmpty) return source

        val width = box.width().coerceAtLeast(1)
        val height = box.height().coerceAtLeast(1)

        val expandW = (width * FACE_CROP_EXPAND_RATIO).toInt()
        val expandH = (height * FACE_CROP_EXPAND_RATIO).toInt()

        val left = max(0, box.left - expandW)
        val top = max(0, box.top - expandH)
        val right = min(source.width, box.right + expandW)
        val bottom = min(source.height, box.bottom + expandH)

        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)

        return try {
            Bitmap.createBitmap(source, left, top, cropW, cropH)
        } catch (_: IllegalArgumentException) {
            source
        }
    }
}
