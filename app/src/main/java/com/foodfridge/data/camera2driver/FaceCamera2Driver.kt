package com.foodfridge.data.camera2driver

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.Arrays
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration

/**
 * Camera2 driver integrated from external Camera2 test app.
 *
 * This class is intentionally independent from CameraX so the project can switch
 * to direct Camera2 pipeline when needed.
 */
class FaceCamera2Driver(
    private val context: Context,
    private val callback: Callback,
) {

    interface Callback {
        fun onCameraOpened(cameraId: String, cameraSummary: String, cameraInfo: String)
        fun onFrame(bitmap: Bitmap)
        fun onError(message: String, throwable: Throwable? = null)
    }

    data class CameraDescriptor(
        val cameraId: String,
        val lensFacing: Int?,
        val isExternal: Boolean,
    )

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private val cameraThread = HandlerThread("face-camera2-thread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    @Throws(CameraAccessException::class)
    fun getCameraIdList(): Array<String> = cameraManager.cameraIdList

    fun getCameraDescriptors(): List<CameraDescriptor> {
        return runCatching {
            cameraManager.cameraIdList.map { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                CameraDescriptor(
                    cameraId = cameraId,
                    lensFacing = lensFacing,
                    isExternal = lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL,
                )
            }
        }.getOrElse { error ->
            callback.onError("Failed to list camera descriptors", error)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun openCameraByIndex(
        cameraIndex: Int,
        previewSurface: Surface,
        imageSize: Size = Size(1920, 1080),
    ) {
        runCatching {
            val cameraIdList = cameraManager.cameraIdList
            if (cameraIdList.isEmpty()) {
                callback.onError("No camera found")
                return
            }
            if (cameraIndex !in cameraIdList.indices) {
                callback.onError("Invalid camera index: $cameraIndex")
                return
            }
            openCameraById(cameraIdList[cameraIndex], previewSurface, imageSize)
        }.onFailure { error ->
            callback.onError("Failed to open camera by index", error)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCameraById(
        cameraId: String,
        previewSurface: Surface,
        imageSize: Size = Size(1920, 1080),
    ) {
        if (!hasCameraPermission()) {
            callback.onError("Camera permission denied")
            return
        }

        closeCamera()

        runCatching {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(cameraId, camera, previewSurface, imageSize)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        callback.onError("Camera disconnected: $cameraId")
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        callback.onError("Camera open error: id=$cameraId code=$error")
                        camera.close()
                        cameraDevice = null
                    }
                },
                cameraHandler,
            )
        }.onFailure { error ->
            callback.onError("openCameraById failed: $cameraId", error)
        }
    }

    private fun createCaptureSession(
        cameraId: String,
        camera: CameraDevice,
        previewSurface: Surface,
        imageSize: Size,
    ) {
        runCatching {
            imageReader = ImageReader.newInstance(
                imageSize.width,
                imageSize.height,
                ImageFormat.YUV_420_888,
                3,
            ).apply {
                setOnImageAvailableListener(onImageAvailableListener, cameraHandler)
            }

            val readerSurface = imageReader?.surface
                ?: throw IllegalStateException("ImageReader surface unavailable")

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    runCatching {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        requestBuilder.addTarget(previewSurface)
                        requestBuilder.addTarget(readerSurface)
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)

                        callback.onCameraOpened(
                            cameraId = cameraId,
                            cameraSummary = buildCameraSummary(cameraId),
                            cameraInfo = buildCameraInfo(cameraId),
                        )
                    }.onFailure { error ->
                        callback.onError("setRepeatingRequest failed", error)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    callback.onError("createCaptureSession failed for camera: $cameraId")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val executor = Executor { runnable -> cameraHandler.post(runnable) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(previewSurface), OutputConfiguration(readerSurface)),
                    executor,
                    stateCallback,
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    Arrays.asList(previewSurface, readerSurface),
                    stateCallback,
                    cameraHandler,
                )
            }
        }.onFailure { error ->
            callback.onError("createCaptureSession failed", error)
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@OnImageAvailableListener
        image.use {
            val bitmap = runCatching { yuv420ToBitmap(it) }.getOrNull()
            if (bitmap != null) {
                callback.onFrame(bitmap)
            }
        }
    }

    fun closeCamera() {
        runCatching {
            captureSession?.close()
            captureSession = null
        }.onFailure { Timber.w(it, "Failed closing captureSession") }

        runCatching {
            imageReader?.close()
            imageReader = null
        }.onFailure { Timber.w(it, "Failed closing imageReader") }

        runCatching {
            cameraDevice?.close()
            cameraDevice = null
        }.onFailure { Timber.w(it, "Failed closing cameraDevice") }
    }

    fun release() {
        closeCamera()
        runCatching {
            cameraThread.quitSafely()
        }.onFailure { Timber.w(it, "Failed quitting camera thread") }
    }

    private fun yuv420ToBitmap(image: Image): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null,
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalStateException("Failed to decode YUV frame")
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val out = ByteArray(ySize + uSize + vSize)
        yBuffer.get(out, 0, ySize)

        val uvPixelStride = uPlane.pixelStride
        if (uvPixelStride == 1) {
            vBuffer.get(out, ySize, vSize)
            uBuffer.get(out, ySize + vSize, uSize)
        } else {
            // Most devices report 2 for NV21/NV12 style interleaved UV planes.
            val vBytes = ByteArray(vSize)
            val uBytes = ByteArray(uSize)
            vBuffer.get(vBytes)
            uBuffer.get(uBytes)
            interleaveUvToNv21(vBytes, uBytes, out, ySize)
        }

        return out
    }

    private fun interleaveUvToNv21(vBytes: ByteArray, uBytes: ByteArray, out: ByteArray, start: Int) {
        var outputIndex = start
        var uvIndex = 0
        val maxPairs = minOf(vBytes.size, uBytes.size)
        while (uvIndex < maxPairs && outputIndex + 1 < out.size) {
            out[outputIndex] = vBytes[uvIndex]
            out[outputIndex + 1] = uBytes[uvIndex]
            outputIndex += 2
            uvIndex += 2
        }
    }

    private fun buildCameraSummary(cameraId: String): String {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

        val facingLabel = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        return "Camera id=$cameraId facing=$facingLabel"
    }

    private fun buildCameraInfo(cameraId: String): String {
        val builder = StringBuilder()
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamMap: StreamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return "No StreamConfigurationMap for $cameraId"

        appendSizes(builder, "preview", streamMap.getOutputSizes(SurfaceTexture::class.java), 8)
        appendSizes(builder, "jpeg", streamMap.getOutputSizes(ImageFormat.JPEG), 8)
        appendSizes(builder, "yuv", streamMap.getOutputSizes(ImageFormat.YUV_420_888), 8)

        return builder.toString()
    }

    private fun appendSizes(builder: StringBuilder, title: String, sizes: Array<Size>?, limit: Int) {
        if (sizes.isNullOrEmpty()) {
            builder.append(title).append(": none\n")
            return
        }

        val sorted = sizes.sortedByDescending { it.width.toLong() * it.height.toLong() }
        builder.append(title).append(" sizes(").append(sorted.size).append("): ")
        sorted.take(limit).forEach { size ->
            builder.append(size.width).append("x").append(size.height).append(" ")
        }
        if (sorted.size > limit) {
            builder.append("...")
        }
        builder.append("\n")
    }
}
