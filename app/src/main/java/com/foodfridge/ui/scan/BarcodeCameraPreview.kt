package com.foodfridge.ui.scan

import android.Manifest
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 条形码扫描相机预览组件
 *
 * 使用后置摄像头 + ML Kit Barcode Scanning 实时检测 QR 码
 */
@Composable
fun BarcodeCameraPreview(
    onBarcodeDetected: (String) -> Unit,
    onCameraError: (String?) -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(true) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Stable callbacks
    val onBarcodeDetectedRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val onCameraErrorRef = remember { mutableStateOf<(String?) -> Unit>({}) }

    onBarcodeDetectedRef.value = onBarcodeDetected
    onCameraErrorRef.value = onCameraError

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.d("BarcodeCamera", "Camera permission: $hasPermission")
    }

    // ML Kit Barcode Scanner - configured for QR Code only
    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(hasPermission, enabled, isScanning) {
        var cameraProvider: ProcessCameraProvider? = null
        var cameraExecutor: ExecutorService? = null
        var imageAnalysis: ImageAnalysis? = null

        if (hasPermission && enabled && isScanning) {
            Log.d("BarcodeCamera", "Starting camera setup")
            try {
                cameraExecutor = Executors.newSingleThreadExecutor()
                cameraProvider = ProcessCameraProvider.getInstance(context).get()

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
                    processImageProxy(barcodeScanner, imageProxy) { rawValue ->
                        Log.d("BarcodeCamera", "QR detected: $rawValue")
                        isScanning = false
                        onBarcodeDetectedRef.value(rawValue)
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )
                    Log.d("BarcodeCamera", "Camera bound successfully")
                } catch (e: Exception) {
                    Log.w("BarcodeCamera", "First bind failed, retrying...", e)
                    try {
                        Thread.sleep(1000)
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis,
                        )
                        Log.d("BarcodeCamera", "Camera bound on retry")
                    } catch (e2: Exception) {
                        Log.e("BarcodeCamera", "Second bind also failed", e2)
                        throw e2
                    }
                }

                onCameraErrorRef.value(null)
                Log.d("BarcodeCamera", "Camera ready for scanning")

            } catch (e: Exception) {
                Log.e("BarcodeCamera", "Failed to bind camera", e)
                onCameraErrorRef.value(e.message ?: "摄像头绑定失败")
            }
        }

        onDispose {
            Log.d("BarcodeCamera", "Releasing camera resources")
            try {
                imageAnalysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
                cameraExecutor?.shutdown()
                val terminated = cameraExecutor?.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) ?: true
                if (!terminated) {
                    cameraExecutor?.shutdownNow()
                }
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
            for (barcode in barcodes) {
                val rawValue = barcode.rawValue
                if (!rawValue.isNullOrBlank()) {
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
