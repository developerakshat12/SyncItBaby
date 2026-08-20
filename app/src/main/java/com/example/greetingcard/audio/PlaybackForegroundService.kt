package com.example.greetingcard.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

private const val TAG = "PlaybackForegroundService"
private const val NOTIFICATION_ID = 2001
private const val CHANNEL_ID = "PlaybackCaptureChannel"

class PlaybackForegroundService : Service() {

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackForegroundService = this@PlaybackForegroundService
    }

    private val binder = PlaybackBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting PlaybackForegroundService to keep receiver active in background")

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Receiving Audio Stream")
            .setContentText("Listening to synced audio in the background")
            .setSmallIcon(android.R.drawable.ic_media_play) // Use built-in standard icon
            .build()

        // Start foreground to elevate process priority
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // If the service is killed, don't automatically restart it, since it's
        // tied to the lifecycle of the active connection which might be lost.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PlaybackForegroundService stopped")
        // Foreground state is automatically removed when the service is destroyed
    }
}

