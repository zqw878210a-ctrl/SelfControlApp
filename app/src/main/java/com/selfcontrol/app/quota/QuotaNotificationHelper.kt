package com.selfcontrol.app.quota

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.selfcontrol.app.MainActivity

class QuotaNotificationHelper(context: Context) {
    private val appContext = context.applicationContext

    fun canPostQuotaNotifications(): Boolean {
        return try {
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID,
                    "每日额度提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.areNotificationsEnabled()
            } else {
                NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            }
            if (!enabled) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
                if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            }
            true
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Quota notification availability check failed", error)
            false
        }
    }

    fun showQuotaThresholdNotification(
        packageName: String,
        displayName: String,
        todayUsageMillis: Long,
        dailyQuotaMinutes: Int,
        threshold: DailyQuotaThreshold
    ) {
        try {
            val title = when (threshold) {
                DailyQuotaThreshold.BELOW_50 -> return
                DailyQuotaThreshold.REACHED_50 -> "${displayName}今日使用已达到 50%"
                DailyQuotaThreshold.REACHED_80 -> "${displayName}今日使用已达到 80%"
                DailyQuotaThreshold.REACHED_90 -> "${displayName}今日使用已达到 90%"
                DailyQuotaThreshold.REACHED_100 -> "${displayName}今日额度已用完"
            }
            if (!canPostQuotaNotifications()) {
                Log.w(LOG_TAG, "NOTIFICATION_UNAVAILABLE packageName=$packageName " +
                    "threshold=$threshold stage=send_recheck")
                return
            }
            Log.i(LOG_TAG, "NOTIFICATION_SEND_START packageName=$packageName threshold=$threshold")
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: run {
                Log.w(LOG_TAG, "NOTIFICATION_SEND_FAILED packageName=$packageName " +
                    "threshold=$threshold error=NotificationManager_unavailable")
                return
            }
            val notificationTag = "quota:$packageName"
            val notificationId = notificationTag.hashCode()
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                notificationId,
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(appContext, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appContext)
            }
            val notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(title)
                .setContentText("已使用 ${todayUsageMillis / 60_000L} / $dailyQuotaMinutes 分钟")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            // The package tag also isolates hash collisions and the untagged foreground notification.
            manager.notify(notificationTag, notificationId, notification)
            Log.i(LOG_TAG, "NOTIFICATION_SEND_SUCCESS packageName=$packageName " +
                "threshold=$threshold notificationId=$notificationId channelId=$CHANNEL_ID " +
                "result=notify_returned")
        } catch (error: Exception) {
            Log.w(LOG_TAG, "NOTIFICATION_SEND_FAILED packageName=$packageName " +
                "threshold=$threshold error=${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    private companion object {
        const val CHANNEL_ID = "daily_quota_alerts"
        const val LOG_TAG = "SELF_CONTROL_QUOTA"
    }
}
