package com.foodfridge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.foodfridge.data.hardware.HardwareManager
import com.foodfridge.data.hardware.KeyboardBarcodeCollector
import com.foodfridge.service.FridgeKeepAliveService
import com.foodfridge.ui.navigation.AppNavigation
import com.foodfridge.ui.navigation.Screen
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var hardwareManager: HardwareManager

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (!granted) {
                Toast.makeText(this, "需要摄像头和图片读取权限才能正常工作", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installKeyEventInterceptor()
        checkAndRequestPermissions()

        setContent {
            AppNavigation(startDestination = Screen.Home.route)
        }

        startKeepAliveService()
        requestIgnoreBatteryOptimizations()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 配置变更（如屏幕旋转）会导致 Activity 重建，此时不应清理硬件状态，
        // 否则当前用户会话会被打断、温度监控会停止。
        // 真正的后台/退出清理由 FoodFridgeApp 的 ActivityLifecycleCallbacks.onActivityStopped 兜底。
        if (isChangingConfigurations) {
            Timber.i("MainActivity onDestroy due to config change - skipping hardware cleanup")
            return
        }
        Timber.i("MainActivity onDestroy - locking door and turning off light")
        try {
            hardwareManager.lockDoor()
            hardwareManager.lightOff()
        } catch (e: Exception) {
            Timber.e(e, "Failed to lock door on destroy")
        }
    }

    private fun installKeyEventInterceptor() {
        val originalCallback = window.callback
        window.callback = object : Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                event?.let { keyEvent ->
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        val device = keyEvent.device
                        Timber.d("KeyEvent: keyCode=${keyEvent.keyCode} unicode=${keyEvent.unicodeChar} " +
                            "device=${device?.name} id=${device?.id} isVirtual=${device?.isVirtual} " +
                            "sources=${device?.sources}")
                    }
                    if (KeyboardBarcodeCollector.processKeyEvent(keyEvent)) {
                        return true
                    }
                }
                return originalCallback?.dispatchKeyEvent(event) ?: false
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            Timber.w("Requesting permissions: $notGranted")
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            Timber.i("All permissions granted")
        }
    }

    private fun startKeepAliveService() {
        try {
            val intent = Intent(this, FridgeKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Timber.i("Keep-alive service started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start keep-alive service")
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
                startActivity(intent)
                Timber.i("Requesting ignore battery optimizations")
            } else {
                Timber.i("Already ignoring battery optimizations")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to request ignore battery optimizations")
        }
    }
}
