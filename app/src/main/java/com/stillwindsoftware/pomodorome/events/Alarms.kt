package com.stillwindsoftware.pomodorome.events

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import com.stillwindsoftware.pomodorome.BuildConfig
import com.stillwindsoftware.pomodorome.MainActivity
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.db.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles all alarms, setting and receiving
 */
class Alarms(private val context: Context) {

    companion object {
        private const val LOG_TAG = "Alarms"
        internal const val REQ_CODE = "request"
        internal const val REQ_CODE_ALARM = 88888
        internal const val REQ_CODE_NOTIFICATION = 77777
        internal const val REQ_CODE_PAUSE = 88877
        internal const val REQ_CODE_RESTART = 88866
        internal const val REQ_CODE_OPEN_FROM_NOTIFICATION = 88855
        internal const val DATA_ALARM_TRIGGER_FOR_TYPE = "alarm_for_type"
        internal const val DATA_ALARM_TRIGGER_MILLIS = "alarm_trigger_millis"
        private const val ALARM_SOUND_MILLIS = 5000L
    }

    /**
     * Called from MainActivity.onStop() only if the timer is playing
     * The idea is while the app is not being used a background alarm is scheduled which
     * will cause a notification to fire (see AlarmReceiver below)
     */
    fun setBackgroundAlarm(activeTimer: ActiveTimer) {

        if (activeTimer.isPlaying()) {
            val now = System.currentTimeMillis()

            activeTimer.getMillisTillNextEvent(now)?.let { (timerType, millisTillNext) ->

                registerBackgroundAlarm(timerType, now + millisTillNext)

                // format just to log nicely, for efficiency only do it in debug
                if (BuildConfig.DEBUG) {
                    with(SimpleDateFormat("mm:ss", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("GMT") }
                        .format(millisTillNext)) {
                        Log.d(LOG_TAG, "setAlarm: register alarm for $this $timerType")
                    }
                }
            }
        }
        else {
            Log.w(LOG_TAG, "setAlarm: called in error, state is not playing (${activeTimer.timerState})")
        }
    }

    private fun registerBackgroundAlarm(timerType: TimerType, triggerAtMillis: Long) {
        try {
            Intent(context, AlarmReceiver::class.java)
                .apply {
                    putExtra(REQ_CODE, REQ_CODE_ALARM)
                    putExtra(DATA_ALARM_TRIGGER_FOR_TYPE, timerType.name)
                    putExtra(DATA_ALARM_TRIGGER_MILLIS, triggerAtMillis)
                }
                .also {
                    with(context.getSystemService(ALARM_SERVICE) as AlarmManager) {
                        setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis,
                            PendingIntent.getBroadcast(context, REQ_CODE_ALARM, it, PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
        }
        catch (e: Exception) {
            Log.d(LOG_TAG, "registerBackgroundAlarm: failed with exception", e)
        }
    }

    /**
     * Called when when the user touches the screen after onStart(),
     * alarms should only fire while running in the background
     */
    fun cancelBackgroundAlarm() {
        try {
            with(context.getSystemService(ALARM_SERVICE) as AlarmManager) {
                cancel(PendingIntent.getBroadcast(context, REQ_CODE_ALARM, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT))
                Log.d(LOG_TAG, "cancelBackgroundAlarm: done")
            }
        }
        catch (e: Exception) {
            Log.d(LOG_TAG, "cancelBackgroundAlarm: failed with exception", e)
        }
    }

    /**
     * Timer has expired, play the relevant sound if set, and vibrate if set
     */
    suspend fun playAlarmSound(timerType: TimerType) {

        try {
            getPreferredRingtoneUri(timerType)?.let {uri ->
                RingtoneManager.getRingtone(context, uri)?.also {ringtone ->

                    withContext(Dispatchers.IO) {

                        // play it as media, so user has better volume controls
                        MediaPlayer.create(context, uri).apply {
                            start()
                            val playLen = if (duration > 0) duration.toLong() else ALARM_SOUND_MILLIS
                            // delay is called on the coroutine scope
                            Log.d(LOG_TAG, "playAlarmSound: playing ringtone ${ringtone.getTitle(context)} for $timerType in coroutine stop after=$playLen")
                            delay(playLen)
                            stop()
                        }
                    }

                } ?: Log.w(LOG_TAG, "playAlarmSound: alarm not played, failed to load a ringtone for the uri ($uri)")

            } ?: Log.w(LOG_TAG, "playAlarmSound: alarm not played, failed to get a ringtone uri")

        }
        catch (e: Exception) {
            Log.e(LOG_TAG, "playAlarmSound: alarm not sent, could not load a ringtone", e)
        }
    }

    private fun getPreferredRingtoneUri(timerType: TimerType): Uri? {

        // return the uri stored for the preference if there is one

        with(PreferenceManager.getDefaultSharedPreferences(context)) {
            with(getString(context.getString(if (timerType == TimerType.POMODORO) R.string.ringtone_pref_key_POMODORO
                                             else R.string.ringtone_pref_key_REST), null)) {

                if (this != null && isNotEmpty()) {
                    Log.d(LOG_TAG, "getPreferredRingtoneUri: found existing = $this")
                    return Uri.parse(this)
                }
            }
        }

        // none stored, so try to get the default

        return if (Settings.System.DEFAULT_RINGTONE_URI != null) {
            Settings.System.DEFAULT_RINGTONE_URI
        }
        else {
            val applicationContext = context.applicationContext
            Log.d(LOG_TAG, "getPreferredRingtoneUri: no pref stored, using application default (app context=$applicationContext)")
            RingtoneManager.getActualDefaultRingtoneUri(applicationContext, RingtoneManager.TYPE_ALARM)
        }

    }

    fun setPreferredRingtone(timerType: TimerType, ringtoneUri: Uri) {

        with(PreferenceManager.getDefaultSharedPreferences(context)) {

            val prefKey = getString(context.getString(if (timerType == TimerType.POMODORO) R.string.ringtone_pref_key_POMODORO
                                             else R.string.ringtone_pref_key_REST), null)

            val editor = this.edit()
            editor.putString(prefKey!!, ringtoneUri.toString())
            editor.apply()
        }
    }

}

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val LOG_TAG = "AlarmReceiver"
    }

    /**
     * Invoked when playing a timer and activity onStop() via registerBackgroundAlarm()
     * And also from the notification which is invoked here as the result of receiving that.
     */
    override fun onReceive(context: Context, intent: Intent) {

        val triggerAtMillis = intent.getLongExtra(Alarms.DATA_ALARM_TRIGGER_MILLIS, -1L)
        val timerType = TimerType.valueOf(intent.getStringExtra(Alarms.DATA_ALARM_TRIGGER_FOR_TYPE)!!)

        when (intent.getIntExtra(Alarms.REQ_CODE, -1)) {
            Alarms.REQ_CODE_ALARM -> {

                // timer has either expired now or this is a repeat alarm since no responses to previous alarm(s)
                val elapsedSinceTrigger = (System.currentTimeMillis() - triggerAtMillis) / 1000f
                Log.d(LOG_TAG, "onReceive: backgroundAlarm for ($timerType) triggered $elapsedSinceTrigger seconds ago")

                MainScope().launch {
                    Alarms(context).apply {
                        playAlarmSound(timerType)

                        withTimerIOThread(context) {
                            Log.d(LOG_TAG, "onReceive: setup next background alarm: timer=${it}")
                            setBackgroundAlarm(it)
                        }
                    }
                }

                // show the user a notification as the activity is not in foreground (or the broadcast would've been cancelled)
                Notifications(context).sendNotification(timerType, triggerAtMillis, TimerState.PLAYING)
            }
            Alarms.REQ_CODE_PAUSE -> {
                // user pressed pause from the notification, cancel further alarms
                // because the alarm will have triggered a repeat

                Alarms(context).cancelBackgroundAlarm()

                MainScope().launch {
                    withTimerIOThread(context, update = true) {
                        Log.d(LOG_TAG, "onReceive: pause and update notification timer=${it}")
                        it.pause()
                        Notifications(context).sendNotification(timerType, triggerAtMillis, TimerState.PAUSED)
                    }
                }
            }
            Alarms.REQ_CODE_RESTART -> {
                // user pressed restart from the notification, cancel the notification
                // and set the alarm for when it next needs to wake up

                Notifications(context).cancelNotifications()

                MainScope().launch {
                    withTimerIOThread(context, update = true) {
                        Log.d(LOG_TAG, "onReceive: restart and update notification timer=${it}")
                        it.start()
                        Alarms(context).setBackgroundAlarm(it)
//                        Notifications(context).sendNotification(timerType, triggerAtMillis, TimerState.PLAYING)
                    }
                }
            }
            Alarms.REQ_CODE_NOTIFICATION -> {
                // user pressed on the notification itself, goto activity

                Notifications(context).cancelNotifications()
                Log.d(LOG_TAG, "onReceive: from sendNotification: start activity")
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(Alarms.REQ_CODE, Alarms.REQ_CODE_OPEN_FROM_NOTIFICATION)
                })
            }
            else -> Log.d(LOG_TAG, "onReceive: sendNotification: request code not recognized")
        }
    }

}