package com.foodfridge.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

object DeviceInfoProvider {

    private const val TAG = "DeviceInfo"

    fun getDeviceNumber(context: Context): String {
        val serial = try {
            Build.SERIAL.takeIf { it != "unknown" && it != Build.UNKNOWN && it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get serial", e)
            null
        }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                .takeIf { it != "9774d56d682e549c" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get android id", e)
            null
        }

        val uniqueId = UUID.randomUUID().toString()

        return serial ?: androidId ?: uniqueId
    }

    fun getMacAddress(context: Context): String {
        val wifiMac = getWifiMacAddress(context)
        if (wifiMac != null && wifiMac != "02:00:00:00:00:00") {
            return wifiMac
        }

        val networkMac = getNetworkInterfaceMac()
        if (networkMac != null) {
            return networkMac
        }

        return "02:00:00:00:00:00"
    }

    private fun getWifiMacAddress(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.connectionInfo?.macAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get wifi mac", e)
            null
        }
    }

    private fun getNetworkInterfaceMac(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (networkInterface.name.equals("wlan0", ignoreCase = true)) {
                    val macBytes = networkInterface.hardwareAddress ?: continue
                    val macBuilder = StringBuilder()
                    for (b in macBytes) {
                        macBuilder.append(String.format("%02X:", b))
                    }
                    if (macBuilder.isNotEmpty()) {
                        macBuilder.deleteCharAt(macBuilder.length - 1)
                    }
                    return macBuilder.toString()
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get network interface mac", e)
            null
        }
    }

    fun getActivationCode(): String {
        return com.foodfridge.BuildConfig.ACTIVATION_CODE
    }
}
