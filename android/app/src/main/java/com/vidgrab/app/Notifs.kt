package com.vidgrab.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object Notifs {
    const val CHANNEL_ID = "vidgrab"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VidGrab", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun builder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_download)
}
