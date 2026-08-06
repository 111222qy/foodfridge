package com.foodfridge.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.foodfridge.BuildConfig
import com.foodfridge.data.hardware.HardwareManager
import com.foodfridge.data.local.UserPreferencesRepository
import com.foodfridge.di.AppEntryPoint
import com.foodfridge.util.FileLoggingTree
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class FoodFridgeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activityCount = 0

    private val appEntryPoint: AppEntryPoint by lazy {
        EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
    }

    private val hardwareManager: HardwareManager by lazy {
        appEntryPoint.hardwareManager()
    }

    private val userPreferencesRepository: UserPreferencesRepository by lazy {
        appEntryPoint.userPreferencesRepository()
    }

    override fun onCreate() {
        super.onCreate()

        if (Timber.forest().isEmpty()) {
            try {
                Timber.plant(Timber.DebugTree())
                Timber.plant(FileLoggingTree(this))
                Timber.i("FoodFridgeApp started")
            } catch (e: Exception) {
                // 日志树初始化失败不应导致应用崩溃
                android.util.Log.e("FoodFridgeApp", "Failed to plant logging trees", e)
            }
        }

        // 每次新进程启动时清除登录标记，确保强制退出/杀进程后必须重新认证。
        // 配置变更/同进程切后台不会重新触发 Application.onCreate，因此仍保持已认证。
        appScope.launch {
            try {
                userPreferencesRepository.clearLoginFlag()
                Timber.i("Fresh process: cleared login flag")
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear login flag on fresh process")
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount <= 0) {
                    Timber.i("App went to background, ensuring door is locked")
                    lockDoorAndLightOff()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun lockDoorAndLightOff() {
        // 统一通过 HardwareManager 操作硬件。
        try {
            hardwareManager.lockDoor()
            hardwareManager.lightOff()
            Timber.i("Background lock: door locked, light off")
        } catch (e: Exception) {
            Timber.e(e, "Failed to lock door from background")
        }
    }

    /**
     * 注意：onTerminate() 仅在模拟器/测试环境下被调用，真实设备上系统杀进程时不会触发。
     * 因此不要把关键清理逻辑放在这里。后台锁门逻辑已迁移到 [onActivityStopped]。
     */
    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
