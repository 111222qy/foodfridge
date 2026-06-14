package com.foodfridge.ui.scan

import android.Manifest
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import androidx.annotation.OptIn
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
private const val BARCODE_EXPOSURE_COMPENSATION = 2

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
        var isDisposed = false
        var bindingRunnable: Runnable? = null

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

                cameraExecutor = Executors.newSingleThreadExecutor()
                cameraProvider = ProcessCameraProvider.getInstance(context).get()

                preview = Preview.Builder()
                    .build()
                    .apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    if (!isScanning.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    processImageProxy(barcodeScanner, imageProxy) { rawValue ->
                        Log.d("BarcodeCamera", "QR detected: $rawValue")
                        isScanning.set(false)
                        onBarcodeDetectedRef.value(rawValue)
                    }
                }

                // 等 PreviewView attach 到窗口后再绑定，避免 surface 未就绪
                bindingRunnable = Runnable {
                    if (isDisposed) return@Runnable
                    try {
                        cameraProvider?.unbindAll()

                        val selectorsToTry = buildList {
                            add(cameraSelector)
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

                        var camera: Camera? = null
                        var bound = false
                        var lastError: Exception? = null
                        for (selector in selectorsToTry) {
                            try {
                                camera = cameraProvider?.bindToLifecycle(
                                    lifecycleOwner,
                                    selector,
                                    preview,
                                    imageAnalysis,
                                )
                                bound = true
                                Log.d("BarcodeCamera", "Camera bound successfully with $selector")
                                break
                            } catch (e: Exception) {
                                lastError = e
                                Log.w("BarcodeCamera", "Failed to bind camera with selector: $selector", e)
                            }
                        }
                        if (!bound) {
                            throw lastError ?: IllegalStateException("没有可用的摄像头")
                        }

                        // 前置摄像头多为固定焦距，用数字变焦拉近条码 + 增加曝光补偿提亮
                        try {
                            camera?.cameraControl?.setZoomRatio(BARCODE_ZOOM_RATIO)
                            Log.d("BarcodeCamera", "Set zoom ratio to $BARCODE_ZOOM_RATIO")
                        } catch (e: Exception) {
                            Log.w("BarcodeCamera", "Failed to set zoom ratio", e)
                        }
                        try {
                            val exposureState = camera?.cameraInfo?.exposureState
                            if (exposureState?.isExposureCompensationSupported == true) {
                                val range = exposureState.exposureCompensationRange
                                val targetIndex = BARCODE_EXPOSURE_COMPENSATION.coerceIn(
                                    range.lower,
                                    range.upper,
                                )
                                camera?.cameraControl?.setExposureCompensationIndex(targetIndex)
                                Log.d("BarcodeCamera", "Set exposure compensation to $targetIndex")
                            }
                        } catch (e: Exception) {
                            Log.w("BarcodeCamera", "Failed to set exposure compensation", e)
                        }

                        // 点击屏幕重新对焦/测光（前置摄像头 AF 能力有限，主要触发 AE 锁定）
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
                                        Log.d("BarcodeCamera", "Focus and metering completed")
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

    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            Log.d("BarcodeCamera", "ML Kit detected ${barcodes.size} barcodes")
            for (barcode in barcodes) {
                val rawValue = barcode.rawValue
                if (!rawValue.isNullOrBlank()) {
                    Log.d("BarcodeCamera", "Barcode raw value: $rawValue")
                    onDetected(rawValue)
                    break
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("BarcodeCamera", "Barcode detection failed", e)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
