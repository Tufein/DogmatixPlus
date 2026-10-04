package com.cortinadev.dogmatix.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the stack trace of the last crash in `files/last_crash.txt`, so *Share diagnostics* can
 * include it (Android's own exit record only says that the app crashed, not where).
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, FILE).writeText("${java.util.Date()} on thread ${thread.name}\n${trace.take(16_000)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()
}
