package com.frontieraudio.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

@Singleton
class ServiceNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while audio monitoring is active"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createNotification(elapsed: Duration): Notification {
        val minutes = elapsed.inWholeMinutes
        val hours = elapsed.inWholeHours
        val text = when {
            hours > 0 -> "Recording — ${hours}h ${minutes % 60}m"
            minutes > 0 -> "Recording — ${minutes}m"
            else -> "Recording"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Always-On Monitoring")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "recording_foreground"
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_NAME = "Always-On Monitoring"
    }
}
