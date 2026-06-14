package com.foodfridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.foodfridge.data.hardware.HardwareManager
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
        checkAndRequestPermissions()

        setContent {
            AppNavigation(startDestination = Screen.Home.route)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("MainActivity onDestroy - locking door and turning off light")
        try {
            hardwareManager.lockDoor()
            hardwareManager.lightOff()
            hardwareManager.stopTemperatureReading()
        } catch (e: Exception) {
            Timber.e(e, "Failed to lock door on destroy")
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
}
