package com.wadio.app

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mirrors the Rust app's startup diagnostics: a private internal-storage log
 * that works on non-rooted devices without needing ADB.
 */
class StartupLogger(private val logFile: File) {

    init {
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.writeText("")
        }
    }

    @Synchronized
    fun log(message: String, error: Throwable? = null) {
        try {
            logFile.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(logFile, true).use { w ->
                w.append("[")
                 .append(stamp)
                 .append("] [Kotlin] ")
                 .append(message)
                 .append('\n')
                if (error != null) w.append(Log.getStackTraceString(error)).append('\n')
                w.flush()
            }
        } catch (t: Throwable) {
            Log.e("WadioStartup", "Could not write startup log", t)
        }
    }

    val filePath: String get() = logFile.absolutePath
}
