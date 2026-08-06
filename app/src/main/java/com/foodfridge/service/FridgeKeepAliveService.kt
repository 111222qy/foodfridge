package com.foodfridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.foodfridge.R
import com.foodfridge.data.hardware.HardwareManager
import com.foodfridge.data.hardware.TemperatureMonitor
import com.foodfridge.domain.repository.DeviceUploadRepository
import com.foodfridge.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 前台保活服务：防止系统息屏/清后台导致留样柜无法工作。
 *
 * 功能：
 * - 显示持久通知，保持前台优先级
 * - 持有 PARTIAL_WAKE_LOCK，防止 CPU 休眠
 * - 保持底层自动喂狗，避免应用进程停止时触发整机重启
 * - 通过系统应用监控保活（QZhengIFManager.setMonitorApp）
 */
@AndroidEntryPoint
class FridgeKeepAliveService : Service() {

    companion object {
        private const val TAG = "FridgeKeepAlive"
        private const val NOTIFICATION_CHANNEL_ID = "fridge_keep_alive"
        private const val NOTIFICATION_ID = 1
        private const val PENDING_UPLOAD_INTERVAL_MS = 5L * 60 * 1000
        private const val HEALTH_CHECK_INTERVAL_MS = 8_000L
    }

    @Inject
    lateinit var hardwareManager: HardwareManager

    @Inject
    lateinit var temperatureMonitor: TemperatureMonitor

    @Inject
    lateinit var deviceUploadRepository: DeviceUploadRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null
    private var pendingUploadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("$TAG onCreate")
        createNotificationChannel()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification())
        temperatureMonitor.start()
        // Keep boot auto-start and restore the app within 15 seconds after it
        // leaves the foreground. The hardware watchdog remains disabled below.
        hardwareManager.setAppMonitor(packageName, 15)
        Timber.i("$TAG App monitor set")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("$TAG onStartCommand")
        startHealthLoop()
        startPendingUploadLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("$TAG onDestroy")
        healthJob?.cancel()
        pendingUploadJob?.cancel()
        hardwareManager.disableWatchdog()
        serviceScope.cancel()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "留样柜运行中",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持留样柜持续运行，防止被系统清理"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("留样柜运行中")
            .setContentText("设备监控与温度上报服务正在运行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FoodFridge::KeepAliveWakeLock",
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
            Timber.i("$TAG WakeLock acquired (24h)")
        } catch (e: Exception) {
            Timber.e(e, "$TAG Failed to acquire WakeLock")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            Timber.i("$TAG WakeLock released")
        } catch (e: Exception) {
            Timber.e(e, "$TAG Failed to release WakeLock")
        }
    }

    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            // 应用层看门狗会在安装或调试强停进程时导致整机重启。
            // 保持其关闭，让厂商底层继续自动喂狗。
            val disabled = hardwareManager.disableWatchdog()
            Timber.i("$TAG Application watchdog disabled=$disabled")

            while (isActive) {
                try {
                    if (wakeLock?.isHeld != true) {
                        Timber.w("$TAG WakeLock lost, re-acquiring")
                        acquireWakeLock()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG Health loop error")
                }
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun startPendingUploadLoop() {
        pendingUploadJob?.cancel()
        pendingUploadJob = serviceScope.launch {
            while (isActive) {
                try {
                    val flushed = deviceUploadRepository.flushPendingUploads()
                    if (flushed > 0) {
                        Timber.i("$TAG flushed $flushed pending uploads")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG pending upload loop failed")
                }
                delay(PENDING_UPLOAD_INTERVAL_MS)
            }
        }
    }
}
