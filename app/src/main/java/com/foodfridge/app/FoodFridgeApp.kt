package com.foodfridge.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.foodfridge.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

@HiltAndroidApp
class FoodFridgeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG && Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
            Timber.i("FoodFridgeApp started")
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
        // 由于 Application 不能直接注入 HardwareManager，这里通过反射获取单例
        // 更推荐的方式是注入，但为了在 Application 生命周期中可用，这里使用 ApiManager 直接控制
        try {
            val apiManager = com.sdk.api.manager.ApiManager.getInstance(this)
            apiManager.setRelaysControly(false)
            apiManager.setWhiteLight(false)
            Timber.i("Background lock: door locked, light off")
        } catch (e: Exception) {
            Timber.e(e, "Failed to lock door from background")
        }
    }

    override fun onTerminate() {
        lockDoorAndLightOff()
        appScope.cancel()
        super.onTerminate()
    }
}
