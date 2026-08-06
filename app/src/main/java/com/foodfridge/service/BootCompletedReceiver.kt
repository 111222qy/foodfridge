package com.foodfridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import timber.log.Timber

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FridgeKeepAliveService::class.java),
            )
        }.onSuccess {
            Timber.i("Boot completed; keep-alive service start requested")
        }.onFailure { error ->
            Timber.e(error, "Failed to start keep-alive service after boot")
        }
    }
}
