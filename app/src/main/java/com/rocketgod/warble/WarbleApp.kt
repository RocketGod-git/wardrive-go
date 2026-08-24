package com.rocketgod.warble

import android.app.Application
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

class WarbleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        runCatching {
            org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val sw = StringWriter()
                err.printStackTrace(PrintWriter(sw))
                val header = buildString {
                    append("Wardrive Go crash report\n")
                    append("app ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
                    append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    append("thread: ${thread.name}\n\n")
                }
                openFileOutput(CRASH_FILE, MODE_PRIVATE).use { it.write((header + sw).toByteArray()) }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, err)
        }
    }

    companion object { const val CRASH_FILE = "last_crash.txt" }
}
