package com.rocketgod.warble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class WearCrashListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == CRASH_PATH) {
                val text = DataMapItem.fromDataItem(e.dataItem).dataMap.getString("text").orEmpty()
                if (text.isNotBlank()) notifyCrash(text)
            }
        }
    }

    private fun notifyCrash(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Watch crash reports", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEV_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Wardrive Go (watch) crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Send watch crash to dev").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 0, chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wardrive)
            .setContentTitle("Watch crash report received")
            .setContentText("Tap to send it to the dev")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    companion object {
        const val CRASH_PATH = "/wardrive/crash"
        private const val CHANNEL = "wear_crash"
        private const val NOTIF_ID = 4211
    }
}
