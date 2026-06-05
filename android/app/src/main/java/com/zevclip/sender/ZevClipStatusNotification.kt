package com.zevclip.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object ZevClipStatusNotification {
    fun update(context: Context) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) {
            return
        }

        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createStatusChannel()
        manager.notify(NOTIFICATION_ID, buildNotification(appContext))
    }

    fun cancel(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(context: Context): Notification {
        val syncEnabled = ZevClipPreferences.isClipboardSyncEnabled(context)
        val receiverRunning = ZevClipPreferences.isAndroidReceiverRunning(context)
        val title = if (syncEnabled && receiverRunning) {
            context.getString(R.string.notification_title_running)
        } else {
            context.getString(R.string.notification_title_stopped)
        }
        val text = if (syncEnabled && receiverRunning) {
            context.getString(R.string.notification_text_running)
        } else {
            context.getString(R.string.notification_text_stopped)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_clipboard)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(syncEnabled && receiverRunning)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun NotificationManager.createStatusChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZevClip status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether ZevClip clipboard sync is running."
            setShowBadge(false)
        }
        createNotificationChannel(channel)
    }

    private const val CHANNEL_ID = "zevclip_status"
    private const val NOTIFICATION_ID = 1042
}
