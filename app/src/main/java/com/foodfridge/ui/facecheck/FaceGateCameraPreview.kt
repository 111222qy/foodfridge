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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun FaceGateCameraPreview(
    onFrame: (Bitmap) -> Unit,
    onCameraError: (String?) -> Unit = {},
    onCameraBoundChanged: (Boolean) -> Unit = {},
    enabled: Boolean = true,
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

    // Stable callbacks - use remember with Unit to never change
    val onFrameRef = remember { mutableStateOf<(Bitmap) -> Unit>({}) }
    val onCameraErrorRef = remember { mutableStateOf<(String?) -> Unit>({}) }
    val onCameraBoundChangedRef = remember { mutableStateOf<(Boolean) -> Unit>({}) }

    // Update refs when callbacks change
    onFrameRef.value = onFrame
    onCameraErrorRef.value = onCameraError
    onCameraBoundChangedRef.value = onCameraBoundChanged

    LaunchedEffect(Unit) {
        Log.d("FaceGateCamera", "Checking camera permission")
        hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.d("FaceGateCamera", "Camera permission: $hasPermission")
    }

    // Use a single DisposableEffect with minimal dependencies
    DisposableEffect(hasPermission, enabled) {
        var cameraProvider: ProcessCameraProvider? = null
        var cameraExecutor: ExecutorService? = null
        var isBound = false

        if (hasPermission && enabled) {
            Log.d("FaceGateCamera", "Starting camera setup")
            try {
                cameraExecutor = Executors.newSingleThreadExecutor()
                cameraProvider = ProcessCameraProvider.getInstance(context).get()

                val preview = Preview.Builder()
                    .build()
                    .apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 360))
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

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)

                isBound = true
                onCameraBoundChangedRef.value(true)
                onCameraErrorRef.value(null)
                Log.d("FaceGateCamera", "Camera bound successfully!")

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
                cameraProvider?.unbindAll()
                cameraExecutor?.shutdownNow()
            } catch (e: Exception) {
                Log.e("FaceGateCamera", "Error releasing camera", e)
            }
            if (isBound) {
                onCameraBoundChangedRef.value(false)
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
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

        // Copy UV planes
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride
        val uBytes = ByteArray(uSize)
        val vBytes = ByteArray(vSize)
        uBuffer.get(uBytes)
        vBuffer.get(vBytes)

        var nv21Pos = width * height
        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                if (uvIndex < uSize && uvIndex < vSize) {
                    if (nv21Pos < nv21.size - 1) {
                        nv21[nv21Pos++] = vBytes[uvIndex]  // V
                        nv21[nv21Pos++] = uBytes[uvIndex]  // U
                    }
                }
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
        val matrix = Matrix()

        if (rotation != 0) {
            matrix.postRotate(rotation.toFloat())
        }
        // Horizontal flip for front camera (mirror effect)
        matrix.postScale(-1f, 1f)

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
