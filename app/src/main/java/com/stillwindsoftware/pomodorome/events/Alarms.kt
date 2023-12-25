package com.stillwindsoftware.pomodorome.events

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Context.VIBRATOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import com.stillwindsoftware.pomodorome.BuildConfig
import com.stillwindsoftware.pomodorome.MainActivity
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.daysHoursMinsToString
import com.stillwindsoftware.pomodorome.db.*
import com.stillwindsoftware.pomodorome.viewmodels.WakeupForReminders
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

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
        internal const val REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT = 88844
        internal const val REQ_CODE_AUTO_START = 88830
        internal const val REQ_CODE_AUTO_STOP = 88831
        internal const val REQ_CODE_AUTO_START_YES = 88832
        internal const val DATA_ALARM_TRIGGER_FOR_TYPE = "alarm_for_type"
        internal const val DATA_ALARM_TRIGGER_MILLIS = "alarm_trigger_millis"
        private const val ALARM_SOUND_MILLIS = 5000L
    }

    private var hasAlarmPermission = false

    /**
     * Called from the main activity.onCreate
     */
    fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hasAlarmPermission = true
            Log.d(LOG_TAG, "requestPermissionsIfNeeded: permission not needed")
            return
        }

        context.registerReceiver(AlarmPermissionReceiver(this), IntentFilter(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        hasAlarmPermission = alarmManager.canScheduleExactAlarms()

        if (!hasAlarmPermission) {
            Log.d(LOG_TAG, "requestPermissionsIfNeeded: needed for exact alarms, requesting...")
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
        else {
            Log.d(LOG_TAG, "requestPermissionsIfNeeded: have permission already")
        }
    }

    private class AlarmPermissionReceiver(val alarms: Alarms) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
                alarms.hasAlarmPermission = true;
                Log.d(LOG_TAG, "AlarmPermissionReceiver: alarm permission granted")
            }
        }
    }

    /**
     * Called from MainActivity.onStop() only if the timer is playing
     * and from when the alarms receiver gets a regular alarm, so to set the next one
     * The idea is while the app is not being used a background alarm is scheduled which
     * will cause a notification to fire (see AlarmReceiver below)
     * AutoStartStopHelper also calls this method if one of the relevant preferences changes
     * in that case the param is to false because the logic is already taking that into account
     */
    fun setRegularBackgroundAlarm(activeTimer: ActiveTimer, checkForOverlapWithAuto: Boolean = true) {

        cancelBackgroundAlarm(REQ_CODE_ALARM) // resetting, if there is already an alarm

        if (activeTimer.isPlaying()) {
            val now = System.currentTimeMillis()

            activeTimer.getMillisTillNextEvent(now)?.also { (timerType, millisTillNext) ->

                if (checkForOverlapWithAuto && AutoStartStopHelper(context).isAutoStopBeforeTime(millisTillNext)) {
                    Log.d(LOG_TAG, "setRegularBackgroundAlarm: not setting alarm as auto stop occurs before would be due")
                }
                else {
                    registerBackgroundAlarm(timerType, now + millisTillNext)
                    Log.d(LOG_TAG, "setRegularBackgroundAlarm: register alarm for ${millisTillNext.daysHoursMinsToString()} $timerType")
                }
            }
        }
        else {
            Log.w(LOG_TAG, "setAlarm: called in error, state is not playing (${activeTimer.timerState})")
        }
    }

    /**
     * Called from AutoStartStopHelper.scheduleAutoAlarm()
     */
    fun setAutoStartStopAlarm(which: Int, triggerAtMillis: Long) {

        Log.d(LOG_TAG, "setAutoStartStopAlarm: cancel and set new auto alarm ($which)")

        // cancel existing first
        cancelBackgroundAlarm(which)

        try {
            val pendingIntent = PendingIntent.getBroadcast(context, which,
                Intent(context, AlarmReceiver::class.java).apply { putExtra(REQ_CODE, which) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            setAlarmForIntentAfterMillis(triggerAtMillis, pendingIntent)

            // format just to log nicely, for efficiency only do it in debug
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "setAutoStartStopAlarm: registered alarm for auto alarm at ${SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT).format(
                    Date().apply { time = triggerAtMillis })}")
            }
        }
        catch (e: Exception) {
            Log.d(LOG_TAG, "setAutoStartStopAlarm: failed with exception", e)
        }
    }

    private fun registerBackgroundAlarm(timerType: TimerType, triggerAtMillis: Long) {
        try {
            val intent = Intent(context, AlarmReceiver::class.java)
                .apply {
                    putExtra(REQ_CODE, REQ_CODE_ALARM)
                    putExtra(DATA_ALARM_TRIGGER_FOR_TYPE, timerType.name)
                    putExtra(DATA_ALARM_TRIGGER_MILLIS, triggerAtMillis)
                }
            val pendingIntent = PendingIntent.getBroadcast(context, REQ_CODE_ALARM, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            setAlarmForIntentAfterMillis(triggerAtMillis, pendingIntent)
        }
        catch (e: Exception) {
            Log.d(LOG_TAG, "registerBackgroundAlarm: failed with exception", e)
        }
    }

    private fun setAlarmForIntentAfterMillis(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
        if (hasAlarmPermission &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms())
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            hasAlarmPermission = false // in case it was given before
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    /**
     * Called when when the user touches the screen after onStart(), regular
     * alarms should only fire while running in the background
     * And also during scheduling of auto start/stop (see AutoStartStopHelper)
     */
    fun cancelBackgroundAlarm(which: Int) {
        try {
            with(context.getSystemService(ALARM_SERVICE) as AlarmManager) {
                cancel(PendingIntent.getBroadcast(context, which, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
    suspend fun playAlarm(timerType: TimerType) {

        PreferenceManager.getDefaultSharedPreferences(context)
            .also { sharedPreferences ->

                // does preference say ok to vibrate? (this one defaults to false)
                // do this first because don't want to wait for the sound to play first

                if (sharedPreferences.getBoolean(context.getString(R.string.vibrate_pref_key), false)) {

                    (context.getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                }

                // does preference say ok to play sound?

                if (sharedPreferences.getBoolean(context.getString(R.string.play_sounds_pref_key), true)) {

                    try {
                        getPreferredRingtoneUri(timerType, sharedPreferences)?.let {uri ->
                            RingtoneManager.getRingtone(context, uri)?.also {ringtone ->

                                withContext(Dispatchers.IO) {

                                    // play it as media, so user has better volume controls
                                    MediaPlayer.create(context, uri).apply {
                                        // getting errors where doesn't play anymore after a while
                                        // trying reset() at the end, but register a listener anyway

                                        setOnErrorListener { mediaPlayer: MediaPlayer, what: Int, extra: Int ->

                                            val whatStr = when (what) {
                                                MediaPlayer.MEDIA_ERROR_UNKNOWN -> "MEDIA_ERROR_UNKNOWN"
                                                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "MEDIA_ERROR_SERVER_DIED"
                                                else -> "WHAT=$what"
                                            }

                                            when (extra) {
                                                MediaPlayer.MEDIA_ERROR_IO -> Log.w(LOG_TAG, "playAlarmSound: onError: MEDIA_ERROR_IO for $whatStr")
                                                MediaPlayer.MEDIA_ERROR_MALFORMED -> Log.w(LOG_TAG, "playAlarmSound: onError: MEDIA_ERROR_MALFORMED for $whatStr")
                                                MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> Log.w(LOG_TAG, "playAlarmSound: onError: MEDIA_ERROR_UNSUPPORTED for $whatStr")
                                                MediaPlayer.MEDIA_ERROR_TIMED_OUT -> Log.w(LOG_TAG, "playAlarmSound: onError: MEDIA_ERROR_TIMED_OUT for $whatStr")
                                                -2147483648 -> Log.w(LOG_TAG, "playAlarmSound: onError: MEDIA_ERROR_SYSTEM for $whatStr")
                                                else -> Log.w(LOG_TAG, "playAlarmSound: onError: extra=$extra for $whatStr")
                                            }

                                            Log.w(LOG_TAG, "playAlarmSound: onError: calling reset on the ringtone player")
                                            mediaPlayer.reset()

                                            true
                                        }
                                        start()
                                        val playLen = if (duration > 0) duration.toLong() else ALARM_SOUND_MILLIS
                                        // delay is called on the coroutine scope
                                        Log.d(LOG_TAG, "playAlarmSound: playing ringtone ${ringtone.getTitle(context)} for $timerType in coroutine stop after=$playLen")
                                        val beginDelayMillis = System.currentTimeMillis()
                                        delay(playLen)
                                        Log.d(LOG_TAG, "playAlarmSound: played for ${System.currentTimeMillis() - beginDelayMillis} millis before stopped")
                                        stop()
                                        reset() // not sure if will help with the problem
                                    }
                                }

                            } ?: Log.w(LOG_TAG, "playAlarmSound: alarm not played, failed to load a ringtone for the uri ($uri)")

                        } ?: Log.w(LOG_TAG, "playAlarmSound: alarm not played, failed to get a ringtone uri")
                    }
                    catch (e: Exception) {
                        Log.e(LOG_TAG, "playAlarmSound: alarm not sent, could not load a ringtone", e)
                    }
                }
            }
    }

    /**
     * return the uri stored for the preference if there is one and if not
     * try to get the default ringtone, failing that, the application default
     */
    internal fun getPreferredRingtoneUri(timerType: TimerType, sharedPreferences: SharedPreferences? = null): Uri? {

        (sharedPreferences ?: PreferenceManager.getDefaultSharedPreferences(context))
            .also { sharedPrefs ->
                sharedPrefs.getString(timerType.ringToneKey, null)?.let {
                    Log.d(LOG_TAG, "getPreferredRingtoneUri: found existing = $it")
                    return Uri.parse(it)
                }
            }

        // none stored, so try to get a default

        return if (Settings.System.DEFAULT_RINGTONE_URI != null) {
            Settings.System.DEFAULT_RINGTONE_URI
        }
        else {
            context.applicationContext.let {
                Log.d(LOG_TAG, "getPreferredRingtoneUri: no pref stored, using application default (app context=$it)")
                RingtoneManager.getActualDefaultRingtoneUri(it, RingtoneManager.TYPE_ALARM)
            }
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
        val timerType: TimerType? = if (intent.hasExtra(Alarms.DATA_ALARM_TRIGGER_FOR_TYPE)) TimerType.valueOf(intent.getStringExtra(Alarms.DATA_ALARM_TRIGGER_FOR_TYPE)!!) else null

        when (intent.getIntExtra(Alarms.REQ_CODE, -1)) {
            Alarms.REQ_CODE_ALARM -> {

                // timer has either expired now or this is a repeat alarm since no responses to previous alarm(s)
                val elapsedSinceTrigger = (System.currentTimeMillis() - triggerAtMillis) / 1000f
                Log.d(LOG_TAG, "onReceive: backgroundAlarm for ($timerType) triggered $elapsedSinceTrigger seconds ago")

                doAlarm(context, timerType!!)

                // show the user a notification as the activity is not in foreground (or the broadcast would've been cancelled)
                // (only if the preference allows notifications though)
                PreferenceManager.getDefaultSharedPreferences(context).also { sharedPrefs ->

                    // first check when it's a rest time reminder, if the pref is set want the activity to start
                    // but the screen may be off and cpu down, in that case it seems that (at least on nexus)
                    // it doesn't wake up for the full screen intent, so acquire the wake locks
                    // but only if the device is charging

                    if (timerType == TimerType.REST
                        && sharedPrefs.getBoolean(context.getString(R.string.notifications_wake_up_pref_key), true)) {

                        MainScope().launch {
                            withTimerIOThread(context) {

                                it.getMillisTillNextEvent(System.currentTimeMillis())?.also { (_, millisTillNext) ->

                                    if (WakeupForReminders.createWakeLocks(context, millisTillNext)) {

                                        context.startActivity(Intent(context, MainActivity::class.java)
                                            .apply {
                                                putExtra(Alarms.REQ_CODE, Alarms.REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                    }
                                    else
                                        Notifications(context).sendNotification(timerType, triggerAtMillis, TimerState.PLAYING)
                                }
                            }
                        }
                    }
                    else if (sharedPrefs.getBoolean(context.getString(R.string.notifications_on_pref_key), true)) {
                        // 2 seconds either side of the wakelocks expiry is plenty of margin, allow notifications while rest reminders
                        // were showing but are now closing out
                        val checkActivity = abs(System.currentTimeMillis() - WakeupForReminders.wakeLocksExpireAtMillis) > 2000L
                        Notifications(context).sendNotification(timerType, triggerAtMillis, TimerState.PLAYING, checkActivity)
                    }
                }
            }
            Alarms.REQ_CODE_PAUSE -> {
                // user pressed pause from the notification, cancel further alarms
                // because the alarm will have triggered a repeat

                Alarms(context).cancelBackgroundAlarm(Alarms.REQ_CODE_ALARM)

                MainScope().launch {
                    withTimerIOThread(context, update = true) {
                        Log.d(LOG_TAG, "onReceive: pause and update notification timer=${it}")
                        it.pause(context)

                        // it could be that a notification is already present when the user changes
                        // the preference to disallow them (hard to see how, but just in case...)
                        PreferenceManager.getDefaultSharedPreferences(context).also { sharedPrefs ->
                            if (sharedPrefs.getBoolean(context.getString(R.string.notifications_on_pref_key), true)) {
                                Notifications(context).sendNotification(timerType!!, triggerAtMillis, TimerState.PAUSED)
                            }
                        }
                    }
                }
            }
            Alarms.REQ_CODE_RESTART -> {
                Log.d(LOG_TAG, "onReceive: restart and update notification")
                restartTiming(context)
            }
            Alarms.REQ_CODE_NOTIFICATION -> {
                // user pressed on the notification itself, goto activity
                Log.d(LOG_TAG, "onReceive: from sendNotification: start activity")
                gotoActivity(context)
            }
            Alarms.REQ_CODE_AUTO_START -> {
                Log.d(LOG_TAG, "onReceive: from sendNotification: process auto start")
                AutoStartStopHelper(context).onAutoStartAlarm()
                doAlarm(context, TimerType.POMODORO, repeating = false)
            }
            Alarms.REQ_CODE_AUTO_START_YES -> {
                Log.d(LOG_TAG, "onReceive: from sendNotification: answer yes to auto start")
                restartTiming(context)
                Notifications(context).sendNotification(false, isConfirmAutoStart = true)
            }
            Alarms.REQ_CODE_AUTO_STOP -> {
                Log.d(LOG_TAG, "onReceive: from sendNotification: process auto stop")
                AutoStartStopHelper(context).onAutoStopAlarm()
                doAlarm(context, TimerType.REST, repeating = false)
            }
            else -> Log.d(LOG_TAG, "onReceive: sendNotification: request code not recognized")
        }
    }

    private fun restartTiming(context: Context) {
        // user pressed restart from the notification, cancel the notification
        // and set the alarm for when it next needs to wake up
        Notifications(context).cancelNotifications()

        MainScope().launch {
            withTimerIOThread(context, update = true) {
                it.start(context)
                Alarms(context).setRegularBackgroundAlarm(it)
            }
        }
    }

    private fun gotoActivity(context: Context) {
        Notifications(context).cancelNotifications()
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Alarms.REQ_CODE, Alarms.REQ_CODE_OPEN_FROM_NOTIFICATION)
        })
    }

    /**
     * Calls the Alarm method to play the sound, or vibrate
     * in the case of regular alarm also schedules the next one
     */
    private fun doAlarm(context: Context, timerType: TimerType, repeating: Boolean = true) {
        MainScope().launch {
            Alarms(context).apply {
                playAlarm(timerType)

                if (repeating) {
                    withTimerIOThread(context) {
                        Log.d(LOG_TAG, "onReceive: setup next background alarm: timer=${it}")
                        setRegularBackgroundAlarm(it)
                    }
                }
            }
        }
    }

}
