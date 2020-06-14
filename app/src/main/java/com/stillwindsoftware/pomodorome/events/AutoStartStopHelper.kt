package com.stillwindsoftware.pomodorome.events

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import androidx.preference.PreferenceManager
import com.stillwindsoftware.pomodorome.*
import com.stillwindsoftware.pomodorome.db.withTimerIOThread
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.*

/**
 * There's quite a few use cases, each has its own method so it's easier to follow.
 * As it's a helper class it interacts with the Alarms (including AlarmReceiver) and Notifications
 * and also with MainActivity when that's in the foreground and also with the db
 *
 * Use runTestsOnGetNextTime() to run a series of a week's worth of test times to make sure it
 * getMillisTillNextTime() works properly
 */
class AutoStartStopHelper(private val context: Context) {

    companion object {
        const val LOG_TAG = "AutoStartStopHelper"
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }

    /**
     * Add an alarm to auto stop
     * Called from the dao
     */
    fun onPlayingStarted() {

        // check auto stop is enabled, otherwise ignore it
        if (!isAutoStopEnabled()) {
            Log.d(LOG_TAG, "onPlayingStarted: auto stop is not enabled, nothing to do")
        }

        Log.d(LOG_TAG, "onPlayingStarted: ")

        // calculate when the next auto stop time is and set an alarm to fire
        scheduleAutoAlarm(false)
    }

    /**
     * Add an alarm for the next auto start time
     * Called anytime playing stops or is paused via the dao:
     * - when the user stops play from main activity (including pausing)
     * - or pauses from the notification
     * - or the alarm receiver gets the signal to auto stop via onAutoStopAlarm()
     */
    fun onPlayingPausedOrStopped() {

        // check auto start is enabled, otherwise nothing to do
        if (!isAutoStartEnabled()) {
            Log.d(LOG_TAG, "onPlayingPausedOrStopped: auto start is not enabled, nothing to do")
        }

        Log.d(LOG_TAG, "onPlayingPausedOrStopped: ")

        // calculate when the next auto start time is and set an alarm to fire
        scheduleAutoAlarm(true)
    }

    /**
     * Start alarm fires, send a notification to notify the user unless the
     * activity is in the foreground, if so, then put up a snack bar message
     * Called by the alarm receiver
     */
    fun onAutoStartAlarm() {

        // check auto start is enabled, otherwise ignore it (it should not have fired at all)
        if (!isAutoStartEnabled()) {
            Log.w(LOG_TAG, "onAutoStartAlarm: auto start is not enabled, should not call this method")
        }

        if (MainActivity.current != null) {
            Log.d(LOG_TAG, "onAutoStartAlarm: calling activity onAutoStart")
            MainActivity.current!!.apply { onAutoStart() }
        }
        else {
            Log.d(LOG_TAG, "onAutoStartAlarm: send notification")

            // check timer is not already running, otherwise it shouldn't have fired
            MainScope().launch {
                withTimerIOThread(context) {
                    if (it.isTrackingTiming()) {
                        Log.d(LOG_TAG, "onAutoStartAlarm: timer is already running, nothing to do")
                    } else {
                        // send a notification to notify the user, question: Start Pomodoro Me?
                        Notifications(context).sendNotification(isStart = true)
                    }
                }
            }
        }
    }

    /**
     * Stop alarm fires, send a notification to notify the user unless the
     * activity is in the foreground, if so, then put up a snack bar message
     * Called by the alarm receiver
     */
    fun onAutoStopAlarm() {

        // check auto stop is enabled, otherwise ignore it (it should not have fired at all)
        if (!isAutoStopEnabled()) {
            Log.d(LOG_TAG, "onAutoStopAlarm: auto stop is not enabled, nothing to do")
        }

        // check for main activity in the foreground, it would need to:
        // - put out snack bar message Pomodoro Me auto stopped (indefinite time?, dismiss on touch?)
        // - change the playing icon to stopped (the view model is being observed)

        if (MainActivity.current != null) {
            Log.d(LOG_TAG, "onAutoStopAlarm: calling activity onAutoStop")
            MainActivity.current!!.apply { onAutoStop() }
        }
        else {
            Log.d(LOG_TAG, "onAutoStopAlarm: send notification")

            // otherwise send a notification to notify the user
            Notifications(context).sendNotification(isStart = false)

            MainScope().launch {
                withTimerIOThread(context, update = true) {
                    Log.d(LOG_TAG, "onAutoStopAlarm: stop timer running in background")
                    it.stop(context)
                }
            }
        }

        // cause the next auto start
        onPlayingPausedOrStopped()

        // cancel any pending regular alarms
        Alarms(context).cancelBackgroundAlarm(Alarms.REQ_CODE_ALARM)
    }

    /**
     * Alarms calls this method to check if it's ok to set an alarm for the regular
     * timer expiring, or if that will be after the auto stop
     */
    fun isAutoStopBeforeTime(timerExpiresInMillis: Long): Boolean {

        // check auto stop is enabled, otherwise can safely ignore it
        if (!isAutoStopEnabled()) {
            Log.d(LOG_TAG, "isAutoStopBeforeTime: auto stop is not enabled, just return false (it's not before)")
            return false
        }

        Log.d(LOG_TAG, "isAutoStopBeforeTime: ")

        // calculate the next auto stop time (there should be alarm set for it, so perhaps better way to check that)
        val nextAutoStop = getMillisTillNextAuto(R.string.auto_stop_time_pref_key)
        return (nextAutoStop != 1L && nextAutoStop <= timerExpiresInMillis)
            .also { if (it) Log.d(LOG_TAG, "isAutoStopBeforeTime: returning true, (auto stop is due before timer expiry)") }
    }

    /**
     * Called from the preferences fragment (SettingsActivity)
     * - when auto stop is changed
     */
    fun onAutoStopPreferenceChanged(isEnabled: Boolean) {

        MainScope().launch {
            withTimerIOThread(context) {

                val alarms = Alarms(context)

                if (isEnabled) { // auto stop

                    // if currently playing or paused, put an alarm up for auto stop (cancel any first, just in case of duplicates)
                    if (it.isTrackingTiming()) {
                        Log.d(LOG_TAG, "onAutoStopPreferenceChanged: scheduling auto stop (incl resetting background alarm)")

                        scheduleAutoAlarm(false)

                        // recalc the next alarm (also cancels any pending)
                        alarms.setRegularBackgroundAlarm(it, checkForOverlapWithAuto = false)
                    }
                    else {
                        Log.d(LOG_TAG, "onAutoStopPreferenceChanged: timer not running, nothing to do")
                    }

                }
                else { // auto stop to false

                    // remove any alarms for auto stop (should only be there if currently playing, but just in case don't check
                    alarms.cancelBackgroundAlarm(Alarms.REQ_CODE_AUTO_STOP)

                    // if currently playing, recalc alarm for next time expiring, it might be after the previous
                    // auto stop time
                    if (it.isPlaying()) {
                        Log.d(LOG_TAG, "onAutoStopPreferenceChanged: cancelled auto-stop alarm, resetting background alarm")
                        alarms.setRegularBackgroundAlarm(it, checkForOverlapWithAuto = false)
                    }
                    else {
                        Log.d(LOG_TAG, "onAutoStopPreferenceChanged: cancelled auto-stop alarm, not playing so probably wasn't one anyway")
                    }
                }
            }
        }
    }

    /**
     * Called from the preferences fragment (SettingsActivity)
     * - when auto start is changed
     * - also when the days and times change
     */
    fun onAutoStartPreferenceChanged(isEnabled: Boolean) {

        MainScope().launch {
            withTimerIOThread(context) {

                // if currently stopped or paused, put an alarm up for auto start (cancel any first, just in case of duplicates)
                if (!it.isPlaying()) {
                    Log.d(LOG_TAG, "onAutoStartPreferenceChanged: scheduling auto start")

                    if (isEnabled) {
                        scheduleAutoAlarm(true)
                        Log.d(LOG_TAG, "onAutoStartPreferenceChanged: cancelled auto start and scheduled new auto start time")
                    }
                    else {
                        Log.d(LOG_TAG, "onAutoStartPreferenceChanged: cancelled auto start")
                    }
                }
                else {
                    Log.d(LOG_TAG, "onAutoStartPreferenceChanged: timer is playing, nothing to do")
                }
            }
        }
    }

    /**
     * Calculates when the next alarm is to start and calls Alarms to schedule it
     * called from
     * - onPlayingPausedOrStopped
     * - onAutoStartPreferenceChanged
     * and to stop called from
     * - onPlayingStarted
     * - onAutoStopPreferenceChanged
     */
    private fun scheduleAutoAlarm(isStart: Boolean) {

        // schedule a start might return -1 if there are no days, so do nothing in that case
        getMillisTillNextAuto(if (isStart) R.string.auto_start_time_pref_key else R.string.auto_stop_time_pref_key).also {
            if (it != -1L) {
                Alarms(context).setAutoStartStopAlarm(if (isStart) Alarms.REQ_CODE_AUTO_START else Alarms.REQ_CODE_AUTO_STOP,
                    System.currentTimeMillis() + it)
            }
            else {
                Log.d(LOG_TAG, "scheduleAutoAlarm: no days for auto alarms, can't schedule it")
            }
        }
    }

    /**
     * Gets the millis till the next occurrence of auto start or stop
     * The param determines which
     * Note: this method is only called when auto start has been set up, so there should
     * always be a set of days in the prefs too (although it could be empty if no days are selected)
     */
    private fun getMillisTillNextAuto(autoPrefKey: Int): Long {
        PreferenceManager.getDefaultSharedPreferences(context)
            .also { prefs ->

                val tillTimeMillis = prefs.getLong(context.getString(autoPrefKey), -1L)

                if (prefs.contains(context.getString(R.string.auto_days_pref_key))) {

                    prefs.getStringSet(context.getString(R.string.auto_days_pref_key), null)?.run {

                        if (isEmpty() || tillTimeMillis == -1L) {
                            Log.d(LOG_TAG, "getMillisTillNextAuto: no selected days in preference, nothing to do")
                        }
                        else {
                            // chop out the preference full days to get just hours/minutes
                            // that will be compared to the local time hours/minutes passed today

                            return getMillisTillNextTime(tillTimeMillis.elapsedMillisModulusDay(isLocalTime = false),
                                GregorianCalendar().get(Calendar.DAY_OF_WEEK), this)
                        }
                    }
                }
                else {
                    // would be an error here as this method should only be called because a preference has been set
                    Log.w(LOG_TAG, "getMillisTillNextAuto: no days prefs set up, this can only happen if user hasn't visited prefs")
                }
            }

        return -1L
    }

    /**
     * If dayOfWeek is today check time as it might be past already
     * if no further days this week, then choose the first day next week
     * daysSet is normally read from preferences, but it can be a set passed for testing (see next method)
     * nowTime substitutes current system time and is only for keeping tests consistent
     */
    private fun getMillisTillNextTime(tillTimeMillis: Long, dayOfWeek: Int, daysSet: MutableSet<String>,
        /* for testing only*/ nowTime: Long? = null): Long {

        // difference now(local) to start of day (local)
        val elapsedMillisToday = (nowTime ?: System.currentTimeMillis()) - GregorianCalendar().millisAtStartOfDay()

        Log.d(LOG_TAG, "getMillisTillNextTime: now time of day is before tillTime=${elapsedMillisToday < tillTimeMillis} tillTime=${tillTimeMillis.daysHoursMinsToString()} elapsedToday=${elapsedMillisToday.daysHoursMinsToString()}")

        if (daysSet.isEmpty() || tillTimeMillis == -1L) {
            Log.d(LOG_TAG, "getMillisTillNextTime: no selected days, nothing to do")
            return -1L
        }

        var firstDay: Int // will need the first day if there are none remaining in the current week
        daysSet.map { dayNum -> dayNum.toInt() }
            .toSortedSet()
            .also {  firstDay = it.first().toInt() }
            .filter { it > dayOfWeek || (it == dayOfWeek && elapsedMillisToday < tillTimeMillis) }
            .min()
            .also {
                val useDay = it ?: firstDay + 7 // null returned means none returned in this week, so take the first day from next week
                val daysFromToday = useDay - dayOfWeek
                val millisToGo = tillTimeMillis - elapsedMillisToday + daysFromToday * MILLIS_PER_DAY

                // lots of debug info, but don't do any of it for production build
                if (BuildConfig.DEBUG) {
                    val prefHours = tillTimeMillis / 1000L / 60L / 60L
                    val prefMinutes = (tillTimeMillis / 1000L / 60L) - (prefHours * 60L)
                    val localHoursToDay = elapsedMillisToday / 1000L / 60L / 60L
                    val localMinutes = (elapsedMillisToday / 1000L / 60L) - (localHoursToDay * 60L)
                    val diffDays = millisToGo / MILLIS_PER_DAY
                    val diffHours = millisToGo / 1000L / 60L / 60L - (diffDays * 24L)
                    val diffMinutes = (millisToGo / 1000L / 60L) - ((diffDays * 24L + diffHours) * 60L)
                    Log.d(LOG_TAG, "getMillisTillNextTime: day=$useDay (${daysFromToday} from today) now=$localHoursToDay:$localMinutes pref=$prefHours:$prefMinutes alarm in $diffDays days, $diffHours hours, $diffMinutes minutes (${DateFormat.getTimeFormat(context).format(Date().apply {time = ((nowTime ?: System.currentTimeMillis()) + millisToGo)})})")
                }

                return millisToGo
            }
    }

    /**
     * Runs fixed tests to ensure next auto start/stop time calculations are correct taking into account local times
     */
    @Suppress("unused")
    internal fun runTestsOnGetNextTime() {
        // set up various tests to check get millis works, and outputs date/times in format that makes sense
        val oneMin = 1000L * 60
        val oneHour = oneMin * 60
        val nowTime = GregorianCalendar().millisAtStartOfDay() + (oneHour * 12)
        val nine15Am = GregorianCalendar().millisAtStartOfDay() + (oneHour * 9) + (oneMin * 15)
        val four30Pm = GregorianCalendar().millisAtStartOfDay() + (oneHour * 16) + (oneMin * 30)
        val timeFormat = DateFormat.getTimeFormat(context)

        Log.d(LOG_TAG, "runTestsOnGetNextTime: BEGIN TESTS: first time is ${timeFormat.format(Date().apply { time = nine15Am })} second time is ${timeFormat.format(Date().apply { time = four30Pm })} now time is ${timeFormat.format(Date().apply { time = nowTime })}\n\n")

        val testDays = mutableSetOf(Calendar.SUNDAY.toString())

        for (todayInWeek in Calendar.SUNDAY..Calendar.SATURDAY) {
            // test 2 times for each day
            for (forTime in listOf(nine15Am, four30Pm)) {
                Log.d(LOG_TAG, "runTestsOnGetNextTime: RUNNING TEST: todayInWeek=$todayInWeek nowTime=${timeFormat.format(Date().apply { time = nowTime })} tillNextTime=${timeFormat.format(Date().apply { time = forTime })} testDays=$testDays")
                val result = getMillisTillNextTime(forTime.elapsedMillisModulusDay(true), todayInWeek, testDays, nowTime)
                Log.d(LOG_TAG, "runTestsOnGetNextTime: result to go: ${result.daysHoursMinsToString()}\n\n")
            }
        }
    }

    private fun isAutoStartEnabled() =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.auto_start_pref_key), false)

    private fun isAutoStopEnabled() =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.auto_stop_pref_key), false)

}
