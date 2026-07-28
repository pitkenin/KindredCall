package com.kindredcall.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.kindredcall.app.CallActivity
import com.kindredcall.app.R
import com.kindredcall.app.BuildConfig

object CallNotificationHelper {
    const val INCOMING_CALL_NOTIFICATION_ID = 1001
    const val SERVICE_NOTIFICATION_ID = 1002
    const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel"
    const val SERVICE_CHANNEL_ID = "signaling_service_channel"

    fun createNotificationChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val incomingChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                context.getString(R.string.notif_channel_calls),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_calls_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                // Use default sound for the channel itself as a fallback
                setSound(
                    android.provider.Settings.System.DEFAULT_RINGTONE_URI,
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                )
            }

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                context.getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_service_desc)
            }

            manager.createNotificationChannel(incomingChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    fun buildIncomingCallNotification(context: Context): Notification {
        val isYulia = BuildConfig.USER_TYPE == "YULIA"
        val callerName = if (isYulia) context.getString(R.string.caller_grandma) else context.getString(R.string.caller_yulia)

        val fullScreenIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Add action buttons
        val answerIntent = Intent(context, CallActivity::class.java).apply {
            action = "ANSWER_CALL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallActivity::class.java).apply {
            action = "DECLINE_CALL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val declinePendingIntent = PendingIntent.getActivity(
            context,
            2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Custom Layout for Heads-Up Notification
        val remoteViews = RemoteViews(context.packageName, R.layout.notification_incoming_call).apply {
            setOnClickPendingIntent(R.id.btn_answer, answerPendingIntent)
            setOnClickPendingIntent(R.id.btn_decline, declinePendingIntent)
        }

        return NotificationCompat.Builder(context, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_title_incoming))
            .setContentText(context.getString(R.string.status_ringing_with_name, callerName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setCustomHeadsUpContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.btn_decline), declinePendingIntent)
            .addAction(android.R.drawable.stat_sys_phone_call, context.getString(R.string.btn_answer), answerPendingIntent)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI)
            .build()
    }

    fun buildServiceNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_title_service))
            .setContentText(context.getString(R.string.notif_text_service))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    fun cancelIncomingCallNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }
}
