package com.foodfridge.data.hardware

import java.io.File

internal object SerialPortLeaseRegistry {
    private val owners = mutableMapOf<String, String>()

    @Synchronized
    fun tryAcquire(devicePath: String, owner: String): Lease? {
        val key = normalizedKey(devicePath)
        if (owners.containsKey(key)) return null
        owners[key] = owner
        return Lease(key, owner)
    }

    @Synchronized
    internal fun currentOwner(devicePath: String): String? = owners[normalizedKey(devicePath)]

    private fun normalizedKey(devicePath: String): String {
        val trimmed = devicePath.trim()
        if (trimmed.startsWith("usb://")) return trimmed
        return runCatching { File(trimmed).canonicalPath }
            .getOrElse { File(trimmed).absolutePath }
    }

    internal class Lease internal constructor(
        private val devicePath: String,
        private val owner: String,
    ) : AutoCloseable {
        @Volatile
        private var released = false

        override fun close() {
            if (released) return
            synchronized(SerialPortLeaseRegistry) {
                if (!released && owners[devicePath] == owner) {
                    owners.remove(devicePath)
                }
                released = true
            }
        }
    }
}
