package com.foodfridge.util

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 把 Timber 日志追加写到应用私有目录的文件中，按日期轮转，保留最近 7 天。
 * 使用单线程顺序写入，避免并发问题；不做全文件重写，降低 I/O 开销。
 * 日志目录：context.filesDir/logs/
 */
class FileLoggingTree(context: Context) : Timber.Tree() {

    companion object {
        private const val TAG = "FileLoggingTree"
        private const val MAX_DAYS = 7L
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024L // 单个日志文件最大 5MB
        private const val LOG_DIR = "logs"

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "file-logging-tree").apply { isDaemon = true }
        }

        fun getLogDir(context: Context): File = File(context.filesDir, LOG_DIR)

        fun getCurrentLogFile(context: Context): File {
            val dir = getLogDir(context)
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "app-${DATE_FORMAT.format(Date())}.log")
        }

        fun listLogFiles(context: Context): List<File> {
            val dir = getLogDir(context)
            return dir.listFiles { file -> file.extension == "log" }?.sortedBy { it.name } ?: emptyList()
        }

        fun cleanupOldLogs(context: Context) {
            runCatching {
                val dir = getLogDir(context)
                val cutoff = System.currentTimeMillis() - MAX_DAYS * 24 * 60 * 60 * 1000
                dir.listFiles { file -> file.extension == "log" }?.forEach { file ->
                    if (file.lastModified() < cutoff) {
                        file.delete()
                    }
                }
            }
        }

        fun flushPendingWrites(timeoutMs: Long = 5_000L): Boolean {
            return runCatching {
                writerExecutor.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Timed out flushing pending log writes", error)
                false
            }
        }
    }

    private val appContext = context.applicationContext

    init {
        cleanupOldLogs(appContext)
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        val timestamp = synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date()) }
        val line = buildString {
            append("$timestamp $level/${tag ?: ""}: $message")
            t?.let { throwable ->
                append("\n")
                append(throwable.stackTraceToString())
            }
            append("\n")
        }
        writerExecutor.execute { appendLineToFile(line) }
    }

    private fun appendLineToFile(line: String) {
        try {
            val file = getCurrentLogFile(appContext)
            // 如果文件超过上限，截断保留最近 80% 内容
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                rotateOversizedFile(file)
            }
            FileOutputStream(file, true).use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    private fun rotateOversizedFile(file: File) {
        runCatching {
            val content = file.readText(Charsets.UTF_8)
            val keepFrom = (content.length * 0.2).toInt()
            file.writeText(content.substring(keepFrom), Charsets.UTF_8)
        }
    }
}
