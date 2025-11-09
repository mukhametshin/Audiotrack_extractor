package com.example.audioextract

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationUtils {

    const val CHANNEL_ID = "audioextract_channel"
    private const val CHANNEL_NAME = "Извлечение аудио"
    private const val CHANNEL_DESC = "Статус извлечения аудиодорожки"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = CHANNEL_DESC
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun progress(
        ctx: Context,
        id: Int,
        title: String,
        text: String,
        max: Int,
        progress: Int,
        indeterminate: Boolean = false
    ) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (indeterminate) b.setProgress(0, 0, true)
        else b.setProgress(if (max > 0) max else 1, progress.coerceAtLeast(0), false)

        nm.notify(id, b.build())
    }

    fun done(
        ctx: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent? = null
    ) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)

        if (contentIntent != null) b.setContentIntent(contentIntent)
        nm.notify(id, b.build())
    }
}
