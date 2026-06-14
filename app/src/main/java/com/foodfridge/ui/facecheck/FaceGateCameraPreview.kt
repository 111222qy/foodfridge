package com.foodfridge.ui.facecheck

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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

@Composable
fun FaceGateCameraPreview(
    onFrame: (Bitmap) -> Unit,
    onCameraError: (String?) -> Unit = {},
    onCameraBoundChanged: (Boolean) -> Unit = {},
    enabled: Boolean = true,
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
        var imageAnalysis: ImageAnalysis? = null
        var isBound = false

        if (hasPermission && enabled) {
            Log.d("FaceGateCamera", "Starting camera setup")
            try {
                // 通过 CameraCoordinator 获取摄像头；禁止 forceRelease，避免破坏引用计数
                val acquired = cameraCoordinator?.acquire(CameraCoordinator.CameraPurpose.FACE_RECOGNITION) ?: true
                if (!acquired) {
                    Log.w("FaceGateCamera", "Camera acquisition denied by coordinator, current purpose=${cameraCoordinator?.getCurrentPurpose()}")
                    throw IllegalStateException("无法获取摄像头，当前被其他功能占用")
                }

                cameraExecutor = Executors.newSingleThreadExecutor()
                cameraProvider = ProcessCameraProvider.getInstance(context).get()

                // 释放之前的摄像头绑定（确保从首页隐藏预览切过来时不会冲突）
                cameraProvider.unbindAll()

                val preview = Preview.Builder()
                    .build()
                    .apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    try {
                        val bitmap = convertToBitmap(imageProxy)
                        if (bitmap != null && !bitmap.isRecycled) {
                            try {
                                onFrameRef.value(bitmap)
                            } catch (e: Exception) {
                                Log.e("FaceGateCamera", "Frame processing error", e)
                                if (!bitmap.isRecycled) {
                                    runCatching { bitmap.recycle() }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FaceGateCamera", "Analyzer error", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                // 优先前置摄像头，失败则回退到后置摄像头
                val bound = tryBindCameraSelector(
                    cameraProvider,
                    lifecycleOwner,
                    listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA),
                    preview,
                    imageAnalysis,
                )
                if (!bound) {
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
                // 解绑所有用例
                cameraProvider?.unbindAll()
                // 温和关闭线程池，等待现有任务完成
                cameraExecutor?.shutdown()
                val terminated = cameraExecutor?.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) ?: true
                if (!terminated) {
                    Log.w("FaceGateCamera", "Executor did not terminate in 1s, forcing shutdown")
                    cameraExecutor?.shutdownNow()
                }
                // 通过 CameraCoordinator 释放摄像头
                cameraCoordinator?.release(CameraCoordinator.CameraPurpose.FACE_RECOGNITION)
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
 * 尝试按顺序绑定多个摄像头选择器，返回是否成功。
 */
private fun tryBindCameraSelector(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    selectors: List<CameraSelector>,
    preview: Preview,
    imageAnalysis: ImageAnalysis,
): Boolean {
    for (selector in selectors) {
        try {
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            Log.d("FaceGateCamera", "Bound camera with selector: $selector")
            return true
        } catch (e: Exception) {
            Log.w("FaceGateCamera", "Failed to bind camera with selector: $selector", e)
        }
    }
    return false
}

private fun calculateAverageBrightness(bitmap: Bitmap): Double {
    return try {
        var sum = 0L
        var count = 0
        val stepX = maxOf(1, bitmap.width / 10)
        val stepY = maxOf(1, bitmap.height / 10)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += (r + g + b) / 3
                count++
            }
        }
        if (count == 0) 0.0 else sum.toDouble() / count
    } catch (e: Exception) {
        -1.0
    }
}

@OptIn(ExperimentalGetImage::class)
private fun convertToBitmap(imageProxy: ImageProxy): Bitmap? {
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

        // Use the safe JPEG conversion approach
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

        // Create NV21 byte array
        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy Y plane
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

        // Copy UV planes
        val uvPixelStride = image.planes[1].pixelStride
        if (uvPixelStride == 1) {
            // Planar format: copy V then U
            vBuffer.get(nv21, width * height, vSize)
            uBuffer.get(nv21, width * height + vSize, uSize)
        } else {
            // Semi-planar format: interleave V and U
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

        // Convert NV21 to JPEG then to Bitmap
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, outputStream)

        val jpegBytes = outputStream.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        if (bitmap == null) {
            Log.e("FaceGateCamera", "Failed to decode JPEG to bitmap")
            return null
        }

        // Apply rotation for front camera
        val rotation = imageProxy.imageInfo.rotationDegrees
        Log.d("FaceGateCamera", "ImageProxy rotation: $rotation")
        val matrix = Matrix()

        if (rotation != 0) {
            matrix.postRotate(rotation.toFloat())
        }
        // Note: removed horizontal flip for face detection reliability

        val transformedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()

        // Ensure ARGB_8888 format
        val finalBitmap = if (transformedBitmap.config == Bitmap.Config.ARGB_8888) {
            transformedBitmap
        } else {
            val argbBitmap = transformedBitmap.copy(Bitmap.Config.ARGB_8888, false)
            transformedBitmap.recycle()
            argbBitmap
        }

        finalBitmap
    } catch (e: Exception) {
        Log.e("FaceGateCamera", "Image conversion failed", e)
        null
    }
}
