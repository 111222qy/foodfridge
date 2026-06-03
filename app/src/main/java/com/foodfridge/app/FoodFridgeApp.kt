package com.foodfridge.app

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG && Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
            Timber.i("FoodFridgeApp started")
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
