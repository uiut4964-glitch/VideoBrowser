package com.example.videobrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())

    private val loop = object : Runnable {
        override fun run() {
            DownloadCenter.tick()
            val active = DownloadCenter.tasks.filter {
                it.status == St.QUEUED || it.status == St.DOWNLOADING || it.status == St.MERGING
            }
            if (active.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(1, build(active))
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadCenter.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "التحميلات", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, build(emptyList()))
        h.removeCallbacks(loop)
        h.post(loop)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(loop)
        super.onDestroy()
    }

    private fun build(active: List<Task>): Notification {
        val speed = active.sumOf { it.speed }
        val title = if (active.isEmpty()) "التحميلات" else "جارٍ التحميل (" + active.size + ")"
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, "dl")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(Util.fmtSpeed(speed))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (active.size == 1) b.setProgress(1000, active[0].permille(), active[0].permille() == 0)
        return b.build()
    }
}
