package com.foodfridge.ui.scan

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val BARCODE_ZOOM_RATIO = 1.5f
private const val BARCODE_FIXED_FOCUS_ZOOM_RATIO = 1.0f
private const val BARCODE_EXPOSURE_COMPENSATION = 2
private const val AUTO_FOCUS_INTERVAL_MS = 2500L

/**
 * 条形码扫描相机预览组件
 *
 * 使用指定摄像头 + ML Kit Barcode Scanning 实时检测条形码
 * 通过 CameraCoordinator 协调摄像头资源，避免与首页人脸检测冲突
 */
@Composable
fun BarcodeCameraPreview(
    onBarcodeDetected: (String) -> Unit,
    onCameraError: (String?) -> Unit = {},
    enabled: Boolean = true,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
    cameraCoordinator: CameraCoordinator? = null,
    scanKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(false) }

    // scanKey 变化时重置扫描状态，但不触发相机生命周期重组
    val isScanning = remember(scanKey) { AtomicBoolean(true) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Stable callbacks
    val onBarcodeDetectedRef = rememberUpdatedState(onBarcodeDetected)
    val onCameraErrorRef = rememberUpdatedState(onCameraError)

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.d("BarcodeCamera", "Camera permission: $hasPermission")
    }

    // ML Kit Barcode Scanner - 支持所有常见一维/二维码格式
    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }

    // 相机生命周期：只依赖 hasPermission 和 enabled，不依赖扫描状态
    DisposableEffect(hasPermission, enabled) {
        var cameraProvider: ProcessCameraProvider? = null
        var cameraExecutor: ExecutorService? = null
        var imageAnalysis: ImageAnalysis? = null
        var preview: Preview? = null
        var focusRunnable: Runnable? = null
        var isDisposed = false
        var bindingRunnable: Runnable? = null

        fun buildAnalyzer(): ImageAnalysis.Analyzer {
            return ImageAnalysis.Analyzer { imageProxy ->
                if (!isScanning.get()) {
                    imageProxy.close()
                    return@Analyzer
                }
                processImageProxy(barcodeScanner, imageProxy) { rawValue ->
                    Log.d("BarcodeCamera", "QR detected: $rawValue")
                    isScanning.set(false)
                    onBarcodeDetectedRef.value(rawValue)
                }
            }
        }

        fun tryResolutionAndBind(
            targetSize: Size,
            selectorsToTry: List<CameraSelector>,
        ): Camera? {
            cameraExecutor?.shutdown()
            cameraExecutor = Executors.newSingleThreadExecutor()
            imageAnalysis?.clearAnalyzer()

            preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(cameraExecutor!!, buildAnalyzer())
                }

            var camera: Camera? = null
            var lastError: Exception? = null
            for (selector in selectorsToTry) {
                try {
                    cameraProvider?.unbindAll()
                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageAnalysis,
                    )
                    Log.d("BarcodeCamera", "Camera bound successfully with $selector at $targetSize")
                    return camera
                } catch (e: Exception) {
                    lastError = e
                    Log.w("BarcodeCamera", "Failed to bind camera with selector: $selector at $targetSize", e)
                }
            }
            throw lastError ?: IllegalStateException("没有可用的摄像头")
        }

        if (hasPermission && enabled) {
            Log.d("BarcodeCamera", "Starting camera setup")
            try {
                // 通过 CameraCoordinator 获取摄像头；禁止粗暴 forceRelease
                val acquired = cameraCoordinator?.acquire(CameraCoordinator.CameraPurpose.BARCODE_SCAN) ?: true
                if (!acquired) {
                    Log.w(
                        "BarcodeCamera",
                        "Camera acquisition denied by coordinator, current purpose=${cameraCoordinator?.getCurrentPurpose()}"
                    )
                    throw IllegalStateException("无法获取摄像头，当前被其他功能占用")
                }

                cameraProvider = ProcessCameraProvider.getInstance(context).get()

                val selectorsToTry = buildList {
                    add(cameraSelector)
                    // 外接摄像头回退
                    val external = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                        .build()
                    if (cameraSelector != external && cameraCoordinator?.hasExternalCamera() == true) {
                        add(external)
                    }
                    if (cameraSelector != CameraSelector.DEFAULT_BACK_CAMERA &&
                        cameraCoordinator?.hasBackCamera() != false
                    ) {
                        add(CameraSelector.DEFAULT_BACK_CAMERA)
                    }
                    if (cameraSelector != CameraSelector.DEFAULT_FRONT_CAMERA &&
                        cameraCoordinator?.hasFrontCamera() != false
                    ) {
                        add(CameraSelector.DEFAULT_FRONT_CAMERA)
                    }
                }

                // 等 PreviewView attach 到窗口后再绑定，避免 surface 未就绪
                bindingRunnable = Runnable {
                    if (isDisposed) return@Runnable
                    try {
                        var camera: Camera? = null
                        try {
                            camera = tryResolutionAndBind(Size(1920, 1080), selectorsToTry)
                        } catch (e: Exception) {
                            Log.w("BarcodeCamera", "Failed at 1080p, falling back to 720p", e)
                            camera = tryResolutionAndBind(Size(1280, 720), selectorsToTry)
                        }

                        applyCameraOptimizations(context, camera, previewView)
                        startPeriodicFocus(camera, previewView) { isScanning.get() }
                            .also { focusRunnable = it }

                        // 点击屏幕重新对焦/测光
                        previewView.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                val factory = previewView.meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(
                                    point,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                                )
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                camera?.cameraControl?.startFocusAndMetering(action)
                                    ?.addListener({
                                        Log.d("BarcodeCamera", "Tap focus and metering completed")
                                    }, ContextCompat.getMainExecutor(context))
                                Log.d("BarcodeCamera", "Tap to focus/meter at (${event.x}, ${event.y})")
                                true
                            } else {
                                false
                            }
                        }

                        Log.d("BarcodeCamera", "Camera ready for scanning")
                    } catch (e: Exception) {
                        Log.e("BarcodeCamera", "Failed to bind camera", e)
                        onCameraErrorRef.value(e.message ?: "摄像头绑定失败")
                    }
                }
                previewView.post(bindingRunnable)

            } catch (e: Exception) {
                Log.e("BarcodeCamera", "Failed to setup camera", e)
                onCameraErrorRef.value(e.message ?: "摄像头初始化失败")
            }
        }

        onDispose {
            Log.d("BarcodeCamera", "Releasing camera resources")
            isDisposed = true
            focusRunnable?.let { previewView.removeCallbacks(it) }
            bindingRunnable?.let { previewView.removeCallbacks(it) }
            previewView.setOnTouchListener(null)
            try {
                imageAnalysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
                cameraExecutor?.shutdown()
                val terminated = cameraExecutor?.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) ?: true
                if (!terminated) {
                    cameraExecutor?.shutdownNow()
                }
                cameraCoordinator?.release(CameraCoordinator.CameraPurpose.BARCODE_SCAN)
            } catch (e: Exception) {
                Log.e("BarcodeCamera", "Error releasing camera", e)
            }
            Log.d("BarcodeCamera", "Camera resources released")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * 应用相机优化：变焦、曝光补偿、连续自动对焦、中心对焦。
 */
private fun applyCameraOptimizations(context: Context, camera: Camera?, previewView: PreviewView) {
    if (camera == null) return

    try {
        val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        val minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val isFixedFocus = minFocusDistance == 0f || afModes == null || afModes.isEmpty()

        Log.d(
            "BarcodeCamera",
            "Camera optimizations: cameraId=$cameraId, minFocusDistance=$minFocusDistance, " +
                "afModes=${afModes?.contentToString()}, isFixedFocus=$isFixedFocus"
        )

        // 固定焦距摄像头近距离数字变焦会让条码更糊，使用 1.0x；可变焦则使用 1.5x
        val zoomRatio = if (isFixedFocus) BARCODE_FIXED_FOCUS_ZOOM_RATIO else BARCODE_ZOOM_RATIO
        try {
            camera.cameraControl.setZoomRatio(zoomRatio)
            Log.d("BarcodeCamera", "Set zoom ratio to $zoomRatio (fixedFocus=$isFixedFocus)")
        } catch (e: Exception) {
            Log.w("BarcodeCamera", "Failed to set zoom ratio", e)
        }

        try {
            val exposureState = camera.cameraInfo.exposureState
            if (exposureState?.isExposureCompensationSupported == true) {
                val range = exposureState.exposureCompensationRange
                val targetIndex = BARCODE_EXPOSURE_COMPENSATION.coerceIn(range.lower, range.upper)
                camera.cameraControl.setExposureCompensationIndex(targetIndex)
                Log.d("BarcodeCamera", "Set exposure compensation to $targetIndex")
            }
        } catch (e: Exception) {
            Log.w("BarcodeCamera", "Failed to set exposure compensation", e)
        }

        // 尝试开启连续自动对焦
        if (afModes != null && afModes.isNotEmpty() &&
            afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        ) {
            try {
                val camera2Control = Camera2CameraControl.from(camera.cameraControl)
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                    .build()
                camera2Control.setCaptureRequestOptions(options)
                Log.d("BarcodeCamera", "Continuous AF enabled")
            } catch (e: Exception) {
                Log.w("BarcodeCamera", "Failed to enable continuous AF", e)
            }
        }

        // 首次中心对焦
        triggerCenterFocus(camera, previewView)
    } catch (e: Exception) {
        Log.w("BarcodeCamera", "Failed to apply camera optimizations", e)
    }
}

private fun triggerCenterFocus(camera: Camera?, previewView: PreviewView) {
    try {
        val width = previewView.width
        val height = previewView.height
        if (width <= 0 || height <= 0) return

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(width / 2f, height / 2f)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
            ?.addListener({
                Log.d("BarcodeCamera", "Center focus and metering completed")
            }, ContextCompat.getMainExecutor(previewView.context))
        Log.d("BarcodeCamera", "Triggered center focus at (${width / 2f}, ${height / 2f})")
    } catch (e: Exception) {
        Log.w("BarcodeCamera", "Failed to trigger center focus", e)
    }
}

private fun startPeriodicFocus(camera: Camera?, previewView: PreviewView, isScanning: () -> Boolean): Runnable {
    val runnable = object : Runnable {
        override fun run() {
            if (isScanning()) {
                triggerCenterFocus(camera, previewView)
            }
            previewView.postDelayed(this, AUTO_FOCUS_INTERVAL_MS)
        }
    }
    previewView.postDelayed(runnable, AUTO_FOCUS_INTERVAL_MS)
    return runnable
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onDetected: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val rotation = imageProxy.imageInfo.rotationDegrees
    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
    val proxyClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    // 先用原始图像尝试识别
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            if (barcodes.isNotEmpty()) {
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        Log.d("BarcodeCamera", "Barcode detected (raw): $rawValue")
                        onDetected(rawValue)
                        break
                    }
                }
            } else {
                // 原始图像未识别到，尝试增强对比度后重新识别
                proxyClosed.set(true)
                tryEnhancedScan(scanner, imageProxy, rotation, onDetected)
            }
        }
        .addOnFailureListener { e ->
            Log.e("BarcodeCamera", "Barcode detection failed", e)
        }
        .addOnCompleteListener {
            if (!proxyClosed.get()) {
                imageProxy.close()
            }
        }
}

/**
 * 对图像进行对比度增强和锐化后重新尝试条码识别。
 * 固定焦距摄像头在近距离拍摄时图像模糊，增强对比度有助于 ML Kit 识别。
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun tryEnhancedScan(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    rotation: Int,
    onDetected: (String) -> Unit,
) {
    try {
        val bitmap = imageProxy.toBitmap()
        imageProxy.close()

        // 1. 对比度增强：使用 ColorMatrix 拉伸对比度
        val contrast = 1.8f // 对比度倍数
        val brightness = -30f // 亮度偏移（稍微降低整体亮度，突出暗色条码）
        val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f,
        ))

        val enhanced = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(enhanced)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            isAntiAlias = true
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        bitmap.recycle()

        // 2. 锐化：通过 Canvas 缩放实现轻微锐化效果
        val enhancedInput = InputImage.fromBitmap(enhanced, rotation)
        scanner.process(enhancedInput)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    Log.d("BarcodeCamera", "ML Kit detected ${barcodes.size} barcodes (enhanced)")
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            Log.d("BarcodeCamera", "Barcode raw value (enhanced): $rawValue")
                            onDetected(rawValue)
                            return@addOnSuccessListener
                        }
                    }
                }
                Log.d("BarcodeCamera", "No barcode detected even after enhancement")
            }
            .addOnFailureListener { e ->
                Log.e("BarcodeCamera", "Enhanced barcode detection failed", e)
            }
            .addOnCompleteListener {
                enhanced.recycle()
            }
    } catch (e: Exception) {
        Log.e("BarcodeCamera", "Enhanced scan error", e)
        imageProxy.close()
    }
}
