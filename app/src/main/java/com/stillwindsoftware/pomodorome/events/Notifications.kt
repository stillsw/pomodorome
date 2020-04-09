package com.stillwindsoftware.pomodorome.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stillwindsoftware.pomodorome.MainActivity
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.db.TimerState
import com.stillwindsoftware.pomodorome.db.TimerType
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.DATA_ALARM_TRIGGER_FOR_TYPE
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.DATA_ALARM_TRIGGER_MILLIS
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE_NOTIFICATION
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE_PAUSE
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE_RESTART

/**
 * Handles notifications, updating and cancellation
 */
class Notifications(private val context: Context) {

    companion object {
        private const val LOG_TAG = "Notifications"
        private const val NOTIFICATION_CHANNEL_ID = "Pomodoro Me"
        private const val NOTIFICATION_ID = 2
    }

    /**
     * Called from AlarmReceiver when an alarm expires but the app is not in the foreground
     * (only possible when the timer is running)
     */
    fun sendNotification(timerType: TimerType, triggerAtMillis: Long, currentState: TimerState) {

        NotificationCompat.Builder(context,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) NOTIFICATION_CHANNEL_ID else "")

            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(when {
                    currentState == TimerState.PAUSED -> R.string.notification_paused
                    timerType == TimerType.REST -> R.string.notification_pomodoro
                    else -> R.string.notification_rest }))
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            // content intent is what happens if the user just taps on the notification, open activity
            .setContentIntent(makePendingIntentForAlarmReceiver(REQ_CODE_NOTIFICATION, timerType, triggerAtMillis))

            .also { builder ->

                if (currentState == TimerState.PLAYING) {
                    // allow pause directly from the notification
                    builder.addAction(R.drawable.ic_timer_notification_pause, context.getString(R.string.notification_button_pause),
                        makePendingIntentForAlarmReceiver(REQ_CODE_PAUSE, timerType, triggerAtMillis))
                }
                else {
                    builder.addAction(R.drawable.ic_timer_notification_play, context.getString(R.string.notification_button_restart),
                        makePendingIntentForAlarmReceiver(REQ_CODE_RESTART, timerType, triggerAtMillis))
                }

                // to get the notification to show on the lock screen, but only for rest time
                if (currentState == TimerState.PLAYING && timerType == TimerType.REST) {
                    builder.setFullScreenIntent(PendingIntent.getActivity(context, 0,
                        Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT), true)
                }

                NotificationManagerCompat.from(context)
                    .apply {
                        builder.priority = NotificationManager.IMPORTANCE_HIGH
                        notify(NOTIFICATION_ID, builder.build())
                    }
            }
    }

    private fun makePendingIntentForAlarmReceiver(reqCode: Int, timerType: TimerType, triggerAtMillis: Long): PendingIntent {
        return PendingIntent.getBroadcast(context, reqCode,
            Intent(context, AlarmReceiver::class.java)
                .apply {
                    putExtra(REQ_CODE, reqCode)
                    putExtra(DATA_ALARM_TRIGGER_FOR_TYPE, timerType.name)
                    putExtra(DATA_ALARM_TRIGGER_MILLIS, triggerAtMillis)
                }
            , PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Called from the Activity after restart when the user responds to an alarm by touching the screen
     * and also from AlarmsReceiver as a result of tapping Stop in the notification
     */
    fun cancelNotifications() {
        NotificationManagerCompat.from(context).apply {
            cancelAll()
        }
    }

    /**
     * Called from MainActivity.onCreate() to ensure the notification channel is created
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun createNotificationChannel() {

        NotificationChannel(NOTIFICATION_CHANNEL_ID,
            context.resources.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
                importance = NotificationManager.IMPORTANCE_HIGH
                enableLights(true)
                enableVibration(true)

                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).also {
                    it.createNotificationChannel(this)
                }
            }
    }
}

