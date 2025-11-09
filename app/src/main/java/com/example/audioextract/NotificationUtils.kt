package com.example.audioextract

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationUtils {
    const val CHANNEL_ID = "audioextract.progress"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Извлечение аудио", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }
}
