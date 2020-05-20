package com.stillwindsoftware.pomodorome.events

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.stillwindsoftware.pomodorome.MainActivity
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.RemindersHelper
import com.stillwindsoftware.pomodorome.db.TimerState
import com.stillwindsoftware.pomodorome.db.TimerType
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.DATA_ALARM_TRIGGER_FOR_TYPE
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.DATA_ALARM_TRIGGER_MILLIS
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE_NOTIFICATION
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE_PAUSE
import com.stillwindsoftware.pomodorome.events.Alarms.Companion.REQ_CODE_RESTART

/**
 * Handles notifications, updating and cancellation
 */
class Notifications(private val context: Context) {

    companion object {
        @Suppress("unused")
        private const val LOG_TAG = "Notifications"
        private const val NOTIFICATION_CHANNEL_ID = "Pomodoro Me"
        private const val NOTIFICATION_ID = 2
    }

    /**
     * Called from AutoStartStopHelper when the event fires in the alarm receiver
     * Includes a title depending on which it is, plus an action only
     * for auto start when the user elects to start
     */
    fun sendNotification(isStart: Boolean) {

        cancelNotifications()

        NotificationCompat.Builder(context,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) NOTIFICATION_CHANNEL_ID else "")

            .setContentTitle(context.getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            // content intent is what happens if the user just taps on the notification, open activity
            .setContentIntent(makePendingIntentForAlarmReceiver(REQ_CODE_NOTIFICATION))

            .also { builder ->
                if (isStart) {
                    builder.addAction(R.drawable.ic_timer_notification_play, context.getString(R.string.notification_auto_start_prompt),
                        makePendingIntentForAlarmReceiver(REQ_CODE_RESTART))
                }
                else {
                    builder.setContentText(context.getString(R.string.snack_and_notification_auto_stopped))
                }

                NotificationManagerCompat.from(context)
                    .apply {
                        builder.priority = NotificationManager.IMPORTANCE_HIGH
                        notify(NOTIFICATION_ID, builder.build())
                    }
            }
    }

    /**
     * Called from AlarmReceiver when an alarm expires but the app is not in the foreground
     * (only possible when the timer is running)
     */
    fun sendNotification(timerType: TimerType, triggerAtMillis: Long, currentState: TimerState) {

        NotificationCompat.Builder(context,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) NOTIFICATION_CHANNEL_ID else "")

            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(getNotificationMessage(currentState, timerType))
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
                // and if the preference allows it
                PreferenceManager.getDefaultSharedPreferences(context).also { sharedPrefs ->
                    if (sharedPrefs.getBoolean(context.getString(R.string.notifications_wake_up_pref_key), true)
                        && currentState == TimerState.PLAYING && timerType == TimerType.REST) {

                        builder.setFullScreenIntent(PendingIntent.getActivity(context, 0,
                            Intent(context, MainActivity::class.java)
                                .apply { putExtra(REQ_CODE, REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT) }
                            , PendingIntent.FLAG_ONE_SHOT), true)
                    }
                }

                NotificationManagerCompat.from(context)
                    .apply {
                        builder.priority = NotificationManager.IMPORTANCE_HIGH
                        notify(NOTIFICATION_ID, builder.build())
                    }
            }
    }

    /**
     * A simple string lookup, except for when it's a rest timer and need to lookup a reminder text
     */
    private fun getNotificationMessage(currentState: TimerState, timerType: TimerType): String {

        return when {
            currentState == TimerState.PAUSED -> context.getString(R.string.notification_paused)
            timerType == TimerType.REST -> context.getString(R.string.notification_pomodoro, RemindersHelper(context).getNextReminder(true))
            else -> context.getString(R.string.notification_rest)
        }
    }

    private fun makePendingIntentForAlarmReceiver(reqCode: Int, timerType: TimerType? = null, triggerAtMillis: Long? = null): PendingIntent {
        return PendingIntent.getBroadcast(context, reqCode,
            Intent(context, AlarmReceiver::class.java)
                .apply {
                    putExtra(REQ_CODE, reqCode)
                    if (timerType != null) putExtra(DATA_ALARM_TRIGGER_FOR_TYPE, timerType.name)
                    if (triggerAtMillis != null) putExtra(DATA_ALARM_TRIGGER_MILLIS, triggerAtMillis)
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

