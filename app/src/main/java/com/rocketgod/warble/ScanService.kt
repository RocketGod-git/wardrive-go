package com.rocketgod.warble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ScanService : Service() {
    @Volatile private var promoted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_STICKY
    }

    private fun promote() {
        if (promoted) return
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Wardriving", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the radar scanning while the app is in the background."
                    setShowBadge(false)
                }
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Wardrive Go")
            .setContentText("Scanning for signals…")
            .setSmallIcon(R.drawable.ic_stat_wardrive)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            promoted = true
        } catch (t: Throwable) {
            try {
                startForeground(NOTIF_ID, notif)
                promoted = true
            } catch (_: Throwable) {
                stopSelf()
            }
        }
    }

    companion object {
        private const val CHANNEL = "wardrive_scan"
        private const val NOTIF_ID = 1
    }
}
