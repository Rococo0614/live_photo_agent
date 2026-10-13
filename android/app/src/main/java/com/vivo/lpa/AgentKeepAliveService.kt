package com.vivo.lpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Keeps long VLM/SDK jobs alive when the chat Activity goes to background. */
class AgentKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("agent_work", "Live Photo Agent", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            4201,
            Notification.Builder(this, "agent_work")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Live Photo Agent")
                .setContentText("正在处理素材")
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
