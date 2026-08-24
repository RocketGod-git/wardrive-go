package com.rocketgod.warble.wear

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

class WarbleWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val sw = StringWriter()
                err.printStackTrace(PrintWriter(sw))
                val header = buildString {
                    append("Wardrive Go (watch) crash report\n")
                    append("app ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
                    append("${Build.MANUFACTURER} ${Build.MODEL} · Wear OS / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    append("thread: ${thread.name}\n\n")
                }
                openFileOutput(CRASH_FILE, MODE_PRIVATE).use { it.write((header + sw).toByteArray()) }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, err)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"

        fun read(ctx: Context): String? = runCatching {
            val f = ctx.getFileStreamPath(CRASH_FILE)
            if (f != null && f.exists()) f.readText().takeIf { it.isNotBlank() } else null
        }.getOrNull()

        fun clear(ctx: Context) {
            runCatching { ctx.deleteFile(CRASH_FILE) }
        }
    }
}
