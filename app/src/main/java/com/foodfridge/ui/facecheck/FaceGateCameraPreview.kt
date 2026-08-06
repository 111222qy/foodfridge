package com.foodfridge.ui.facecheck

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.foodfridge.data.camera.CameraCoordinator
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val FACE_CAMERA_FRAME_INTERVAL_MS = 250L

@Composable
fun FaceGateCameraPreview(
    onFrame: (Bitmap) -> Unit,
    onCameraError: (String?) -> Unit = {},
    onCameraBoundChanged: (Boolean) -> Unit = {},
    enabled: Boolean = true,
    highQuality: Boolean = false,
    cameraCoordinator: CameraCoordinator? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Stable callbacks - use rememberUpdatedState to always get latest without recomposing
    val onFrameRef = rememberUpdatedState(onFrame)
    val onCameraErrorRef = rememberUpdatedState(onCameraError)
    val onCameraBoundChangedRef = rememberUpdatedState(onCameraBoundChanged)

    LaunchedEffect(Unit) {
        Log.d("FaceGateCamera", "Checking camera permission")
        hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.d("FaceGateCamera", "Camera permission: $hasPermission")
    }

    // Use a single DisposableEffect with minimal dependencies
    DisposableEffect(hasPermission, enabled) {
        var cameraProvider: ProcessCameraProvider? = null
        var cameraExecutor: ExecutorService? = null
        var preview: Preview? = null
        var imageAnalysis: ImageAnalysis? = null
        var coordinatorAcquired = false
        var isBound = false
        var lastAnalyzedAtMs = 0L

        if (hasPermission && enabled) {
            Log.d("FaceGateCamera", "Starting camera setup")
            try {
                // 通过 CameraCoordinator 获取摄像头；禁止 forceRelease，避免破坏引用计数
                val acquired = cameraCoordinator?.acquire(CameraCoordinator.CameraPurpose.FACE_RECOGNITION) ?: true
                if (!acquired) {
                    Log.w("FaceGateCamera", "Camera acquisition denied by coordinator, current purpose=${cameraCoordinator?.getCurrentPurpose()}")
                    throw IllegalStateException("无法获取摄像头，当前被其他功能占用")
                }
                coordinatorAcquired = true

                cameraExecutor = Executors.newSingleThreadExecutor()
                cameraProvider = ProcessCameraProvider.getInstance(context).get()

                preview = Preview.Builder()
                    .build()
                    .apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(if (highQuality) Size(640, 480) else Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalyzedAtMs < FACE_CAMERA_FRAME_INTERVAL_MS) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastAnalyzedAtMs = now

                    var bitmap: Bitmap? = null
                    try {
                        bitmap = convertToBitmap(imageProxy, highQuality)
                        if (bitmap != null && !bitmap.isRecycled) {
                            try {
                                onFrameRef.value(bitmap)
                                bitmap = null
                            } catch (e: Exception) {
                                Log.e("FaceGateCamera", "Frame processing error", e)
                            }
                        } else {
                            Log.w("FaceGateCamera", "Bitmap conversion returned null or recycled")
                        }
                    } catch (oom: OutOfMemoryError) {
                        Log.e("FaceGateCamera", "Analyzer ran out of memory; dropping frame", oom)
                    } catch (e: Exception) {
                        Log.e("FaceGateCamera", "Analyzer error", e)
                    } finally {
                        if (bitmap != null && !bitmap.isRecycled) {
                            runCatching { bitmap.recycle() }
                        }
                        imageProxy.close()
                    }
                }

                // 使用 CameraCoordinator 推荐的人脸识别摄像头
                val faceCameraSelector = cameraCoordinator?.getRecommendedFaceCameraSelector()
                    ?: CameraSelector.DEFAULT_FRONT_CAMERA
                Log.i("FaceGateCamera", "人脸识别使用摄像头: $faceCameraSelector")

                val boundCamera = tryBindCameraSelector(
                    cameraProvider,
                    lifecycleOwner,
                    listOf(faceCameraSelector, CameraSelector.DEFAULT_BACK_CAMERA),
                    preview,
                    imageAnalysis,
                )
                if (boundCamera == null) {
                    throw IllegalStateException("No available camera can be bound")
                }
                Log.d("FaceGateCamera", "Camera bound successfully")

                isBound = true
                onCameraBoundChangedRef.value(true)
                onCameraErrorRef.value(null)
                Log.d("FaceGateCamera", "Camera ready for scanning")

            } catch (e: Exception) {
                Log.e("FaceGateCamera", "Failed to bind camera", e)
                isBound = false
                onCameraBoundChangedRef.value(false)
                onCameraErrorRef.value(e.message ?: "摄像头绑定失败")
            }
        }

        onDispose {
            Log.d("FaceGateCamera", "Releasing camera resources")
            try {
                // 先清除分析器，停止接收新帧
                imageAnalysis?.clearAnalyzer()

                // 只解绑当前预览拥有的用例，不能使用 unbindAll() 影响另一个页面的新相机。
                val provider = cameraProvider
                val previewUseCase = preview
                val analysisUseCase = imageAnalysis
                if (provider != null && previewUseCase != null && analysisUseCase != null) {
                    provider.unbind(previewUseCase, analysisUseCase)
                }

                cameraExecutor?.shutdownNow()

                // 未取得租约的初始 Effect 不能释放其他预览的租约。
                if (coordinatorAcquired) {
                    cameraCoordinator?.release(CameraCoordinator.CameraPurpose.FACE_RECOGNITION)
                }
            } catch (e: Exception) {
                Log.e("FaceGateCamera", "Error releasing camera", e)
            }
            if (isBound) {
                onCameraBoundChangedRef.value(false)
            }
            Log.d("FaceGateCamera", "Camera resources released")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * 尝试按顺序绑定多个摄像头选择器，返回绑定成功的 Camera 实例（失败返回 null）。
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun tryBindCameraSelector(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    selectors: List<CameraSelector>,
    preview: Preview,
    imageAnalysis: ImageAnalysis,
): Camera? {
    for (selector in selectors) {
        try {
            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            Log.i("FaceGateCamera", "摄像头绑定成功: cameraId=$cameraId, selector=$selector")
            // 启用连续自动对焦，提升 50cm 距离的人脸清晰度
            try {
                val camera2Control = Camera2CameraControl.from(camera.cameraControl)
                camera2Control.captureRequestOptions = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    .build()
                Log.d("FaceGateCamera", "Continuous AF enabled")
            } catch (afEx: Exception) {
                Log.w("FaceGateCamera", "Failed to enable continuous AF: ${afEx.message}")
            }
            return camera
        } catch (e: Exception) {
            Log.w("FaceGateCamera", "Failed to bind camera with selector: $selector", e)
        }
    }
    return null
}

@OptIn(ExperimentalGetImage::class)
private fun convertToBitmap(imageProxy: ImageProxy, highQuality: Boolean = false): Bitmap? {
    var outputStream: ByteArrayOutputStream? = null
    var bitmap: Bitmap? = null

    return try {
        val image = imageProxy.image ?: run {
            Log.w("FaceGateCamera", "Image is null")
            return null
        }

        val width = image.width
        val height = image.height

        if (width <= 0 || height <= 0) {
            Log.w("FaceGateCamera", "Invalid dimensions: ${width}x${height}")
            return null
        }

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        if (ySize <= 0 || uSize <= 0 || vSize <= 0) {
            Log.w("FaceGateCamera", "Empty buffer detected: y=$ySize, u=$uSize, v=$vSize")
            return null
        }

        val nv21 = ByteArray(width * height * 3 / 2)

        val yRowStride = image.planes[0].rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            val yBytes = ByteArray(ySize)
            yBuffer.get(yBytes)
            for (y in 0 until height) {
                val srcPos = y * yRowStride
                val dstPos = y * width
                val length = minOf(width, ySize - srcPos)
                if (length > 0) {
                    System.arraycopy(yBytes, srcPos, nv21, dstPos, length)
                }
            }
        }

        val uvPixelStride = image.planes[1].pixelStride
        if (uvPixelStride == 1) {
            vBuffer.get(nv21, width * height, vSize)
            uBuffer.get(nv21, width * height + vSize, uSize)
        } else {
            val vBytes = ByteArray(vSize)
            val uBytes = ByteArray(uSize)
            vBuffer.get(vBytes)
            uBuffer.get(uBytes)

            var nv21Pos = width * height
            val maxPairs = minOf(vSize, uSize)
            var i = 0
            while (i < maxPairs && nv21Pos + 1 < nv21.size) {
                nv21[nv21Pos++] = vBytes[i]
                nv21[nv21Pos++] = uBytes[i]
                i += 2
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), if (highQuality) 90 else 60, outputStream)

        val jpegBytes = outputStream.toByteArray()
        outputStream.close()
        outputStream = null

        val targetWidth = if (highQuality) 640 else 320
        val targetHeight = if (highQuality) 480 else 240

        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        try {
            bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
        } catch (e: OutOfMemoryError) {
            Log.e("FaceGateCamera", "OOM while decoding bitmap, trying smaller size", e)
            val smallerOptions = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 2
            }
            try {
                bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, smallerOptions)
            } catch (e2: OutOfMemoryError) {
                Log.e("FaceGateCamera", "Still OOM even with inSampleSize=2", e2)
                return null
            }
        }

        if (bitmap == null) {
            Log.e("FaceGateCamera", "Failed to decode JPEG to bitmap")
            return null
        }

        // 处理旋转
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            bitmap = rotated
        }

        // 显式缩放到目标尺寸
        if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            bitmap.recycle()
            bitmap = scaled
        }

        bitmap
    } catch (oom: OutOfMemoryError) {
        if (bitmap?.isRecycled == false) {
            runCatching { bitmap.recycle() }
        }
        Log.e("FaceGateCamera", "Image conversion ran out of memory", oom)
        null
    } catch (e: Exception) {
        if (bitmap?.isRecycled == false) {
            runCatching { bitmap.recycle() }
        }
        Log.e("FaceGateCamera", "Image conversion failed", e)
        null
    } finally {
        outputStream?.close()
    }
}
