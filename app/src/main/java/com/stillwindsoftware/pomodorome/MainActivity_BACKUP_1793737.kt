package com.stillwindsoftware.pomodorome

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.gms.ads.AdView
import com.google.android.material.snackbar.Snackbar
import com.stillwindsoftware.pomodorome.customviews.TimerGui
import com.stillwindsoftware.pomodorome.databinding.ActivityMainBinding
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase
import com.stillwindsoftware.pomodorome.db.TimerType
import com.stillwindsoftware.pomodorome.events.Alarms
import com.stillwindsoftware.pomodorome.events.Notifications
import com.stillwindsoftware.pomodorome.viewmodels.ActiveTimerViewModel
import com.stillwindsoftware.pomodorome.viewmodels.RemindersViewModel
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.lang.Math.toRadians
import kotlin.math.min
import kotlin.math.sin
import com.stillwindsoftware.pomodorome.ads.AdmobLoader
import com.stillwindsoftware.pomodorome.db.TimerState

/**
 * Thanks to Alex Lockwood for his excellent shape shifter path morphing tool which I used
 * to create the animated vector drawables (https://beta.shapeshifter.design/)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        var current: MainActivity? = null // singleton instance so AutoStartStopHelper can trigger

        @Suppress("unused")
        private const val LOG_TAG = "MainActivity"
        private const val PLAY_CLICKS_BEFORE_ADS_CONSENT = 2
        const val TIMER_DELAY = 100L        // ticking interval, not too long otherwise seconds might not change quick enough

        private var playClicksMade = 0 // for firing ads consent form
    }

    // the animated vector drawables are cached for animation from the button presses
    // and then the static vector drawables assigned if necessary in callbackChangeToTimer
    // these exist because the animated versions sometimes show the end frame when they should
    // show the start frame, it appears to be a bug and nothing I've tried works, so in those
    // cases just show the static image that should be shown

    private val playToPauseDrawable by lazy {AnimatedVectorDrawableCompat.create(this, R.drawable.play_to_pause_avd)!! }
    private val pauseToPlayDrawable by lazy { AnimatedVectorDrawableCompat.create(this, R.drawable.pause_to_play_avd)!! }
    private val pencilToStopDrawable by lazy { AnimatedVectorDrawableCompat.create(this, R.drawable.pencil_to_stop_avd)!! }
    private val stopToPencilDrawable by lazy { AnimatedVectorDrawableCompat.create(this, R.drawable.stop_to_pencil_avd)!! }
    private val stopDrawable by lazy { getDrawable(R.drawable.stop_vd)!! }
    private val pencilDrawable by lazy { getDrawable(R.drawable.pencil_vd)!! }
    private val playDrawable by lazy { getDrawable(R.drawable.play_vd)!! }
    private val pauseDrawable by lazy { getDrawable(R.drawable.pause_vd)!! }
    private var transitioningToTimerState: TimerState? = null // handles the case where a button is pressed and want the animation to play out

    private var lastReminderTextDelta = Float.MAX_VALUE

    // timer ticking is controlled by a runnable posted delayed every 1/10 second
    // it's started from the callback from the TimePickerCircle view which is
    // observing the View Model

    private var isTrackingTiming = false           // false when timers being modified
    private var isTimerTrackingRunnablePosted = false   // make sure only posted once
    private val timerTrackingRunnable = Runnable {
        isTimerTrackingRunnablePosted = false

        if (isTrackingTiming) {
            updateTrackingOnTimedViews(true)
        }
    }

    private val timerViewModel by lazy { ViewModelProvider(this)[ActiveTimerViewModel::class.java] }
    private val remindersViewModel by lazy { ViewModelProvider(this)[RemindersViewModel::class.java] }

    private val admobLoader by lazy {
        AdmobLoader(this, AdView(this@MainActivity)
            .apply {
                adUnitId = getString(R.string.admob_banner_id)
                ad_space.addView(this)
            }, windowManager.defaultDisplay) }

    private val alarms = Alarms(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmojiHelper.initEmojis(applicationContext)

        // provided the preference allows it, attempt to have the activity show in front of the lock screen,
        // test the intent to see that's where it's come from
        wakeupForFullScreenIntent()

        val binding : ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewmodel = timerViewModel

        setSupportActionBar(toolbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notifications(this).createNotificationChannel()

        timer_gui.timerWidgets[TimerGui.POMODORO].timePickerTextView = pomodoro_time
        timer_gui.timerWidgets[TimerGui.REST].timePickerTextView = rest_time

        remindersViewModel.repository.reminders.observe(this, Observer {  }) // for now just have an active observer to trigger list population

        // admob
        admobLoader.initialize()

    }

    private fun wakeupForFullScreenIntent() {
        intent?.let {
            PreferenceManager.getDefaultSharedPreferences(this).also { sharedPrefs ->
                if (sharedPrefs.getBoolean(getString(R.string.notifications_wake_up_pref_key), true)
                    && it.getIntExtra(Alarms.REQ_CODE, -1) == Alarms.REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT) {

                    Log.d(LOG_TAG, "onCreate: notification from lock screen, turn on and set to show")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        setShowWhenLocked(true)
                        setTurnScreenOn(true)
                        // fairly sure this is going to work inconsistently across devices
                        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
                    }
                    else {
                        @Suppress("DEPRECATION")
                        // experimentation suggests this does work as hoped, the activity shows in front of the lock screen and after
                        // dismissing it, it goes back to locked, dismiss keyguard might not add anything, but leave it in
                        // in case of differences in devices
                        this.window.addFlags(
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        )
                    }
                }
            }
        }
<<<<<<< HEAD
=======

        val binding : ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewmodel = timerViewModel

        setSupportActionBar(toolbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notifications(this).createNotificationChannel()

        timer_gui.timerWidgets[TimerGui.POMODORO].timePickerTextView = pomodoro_time
        timer_gui.timerWidgets[TimerGui.REST].timePickerTextView = rest_time

        playToPauseDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.play_to_pause_avd)!!
        pauseToPlayDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.pause_to_play_avd)!!
        pencilToStopDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.pencil_to_stop_avd)!!
        stopToPencilDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.stop_to_pencil_avd)!!

        remindersViewModel.repository.reminders.observe(this, Observer {  }) // for now just have an active observer to trigger list population

        // admob
        admobLoader.initialize()

//        AutoStartStopHelper(this).getNextSelectedTimeInMillis(true)
//        for (j in 1 .. 7) {
//            for (i in GregorianCalendar().get(Calendar.HOUR_OF_DAY) - 1 .. GregorianCalendar().get(Calendar.HOUR_OF_DAY) + 1) {
//                AutoStartStopHelper(this)
//                    .getNextSelectedTimeInMillis(true, j,
//                        GregorianCalendar(TimeZone.getTimeZone("GMT"))
//                            .apply {
//                                clear()
//                                set(Calendar.HOUR_OF_DAY, i)
//                                set(Calendar.DAY_OF_WEEK, j)
//                            }.timeInMillis)
//            }
//        }
>>>>>>> 64bf82db1dc51a7cfafb1c1af9747c3382fa1824
    }

    fun callbackChangeToTimer(activeTimer: ActiveTimer) {

        Log.d(LOG_TAG, "callbackChangeToTimer: call update tracking isTracking=${activeTimer.isTrackingTiming()}")
        // change of state, ticking the timers turns on or off
        updateTrackingOnTimedViews(activeTimer.isTrackingTiming())

        // leave the animated buttons to their own devices if transitions are happening already
        // otherwise set them
        if (transitioningToTimerState == null) {
            play_button.setImageDrawable(if (activeTimer.isPlaying()) pauseDrawable else playDrawable)
            edit_button.setImageDrawable(if (activeTimer.isStopped()) pencilDrawable else stopDrawable)
        }
        else {
            transitioningToTimerState = null
        }
    }

    /**
     * AutoStartStopHelper needs to tell activity when the timer is stopped automatically
     * it could just react to the observer, but that doesn't indicate that it was
     * part of the auto stop process
     */
    override fun onPause() {
        super.onPause()
        current = null
    }

    /**
     * Timing will track automatically after the activity is created, but if it's
     * restarted have to test for getting it going again
     */
    override fun onResume() {
        super.onResume()
        current = this

        if (isTrackingTiming) {
            updateTrackingOnTimedViews(true)
        }

        admobLoader.onActivityResume()
    }

    /**
     * Background alarm and notification canceled, here as well as in onStart() because the
     * notification may start this activity when wake up on the notification
     */
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        val ret = super.dispatchTouchEvent(event)
        if (event?.action == MotionEvent.ACTION_DOWN && intent?.getIntExtra(Alarms.REQ_CODE, -1) == Alarms.REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT) {
            Log.d(LOG_TAG, "dispatchTouchEvent: action down, cancel background alarm and notification")
            alarms.cancelBackgroundAlarm(Alarms.REQ_CODE_ALARM)
            Notifications(this).cancelNotifications()
        }
        return ret
    }

    override fun onStart() {
        super.onStart()

        // from the lock screen don't cancel alarm/notifications
        intent?.let {
                if (it.getIntExtra(Alarms.REQ_CODE, -1) != Alarms.REQ_CODE_NOTIFICATION_FULL_SCREEN_INTENT) {
                    Log.d(LOG_TAG, "onStart: not started from lock screen, cancel background alarm and notification")
                    alarms.cancelBackgroundAlarm(Alarms.REQ_CODE_ALARM)
                    Notifications(this).cancelNotifications()
                }
            }
    }

    /**
     * Set an alarm for the next event only if currently playing
     */
    override fun onStop() {
        super.onStop()

        timerViewModel.getActiveTimer()?.run {
            if (isPlaying()) {
                alarms.setRegularBackgroundAlarm(this)
                setKeepScreenOnWhileRunning(false)
            }
        }
    }

    /**
     * Called from callbackChangeToTimer() and from onResume()
     * If onOrOff is true timing begins by posting the tick runnable delayed
     * otherwise setting the var to false will prevent the next post
     * The runnable calls this method again while timing is being tracked
     */
    private fun updateTrackingOnTimedViews(onOrOff: Boolean) {

        // being turned on or off for the first time
        if (isTrackingTiming != onOrOff) {
            setKeepScreenOnWhileRunning(onOrOff)
        }

        isTrackingTiming = onOrOff

        if (isTrackingTiming) {

            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                timer_gui.doTick().also { (timerType, restElapsed) ->
                    if (timerType != TimerType.NONE) {
                        Log.d(LOG_TAG, "updateTrackingOnTimedViews: timer expired this go round")
                        timerExpiredWhileTicking(timerType)
                    }

                    if (restElapsed != 0L) {
                        animateReminderText(restElapsed)
                    }
                }

                if (!isTimerTrackingRunnablePosted) {
                    isTimerTrackingRunnablePosted = true
                    timer_gui.postDelayed(timerTrackingRunnable, TIMER_DELAY)
                }
            }
        }
        else {
            // if the timer is stopped automatically or by the notification
            // make sure the reminder text is not showing
            rest_reminder.visibility = View.GONE
        }
    }

    private fun setKeepScreenOnWhileRunning(turnOn: Boolean) {

        if (turnOn && PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.keepScreenAwake_pref_key), true)) {
            Log.d(LOG_TAG, "setKeepScreenOnWhileRunning: keeping screen on")
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        else {
            Log.d(LOG_TAG, "setKeepScreenOnWhileRunning: turned off keep screen on")
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * React to timer expiring while the app is running:
     * - play a sound if set (in a coroutine so it can also wait a bit and then stop the ringtone)
     * - vibrate if set
     * - show rest time prompts
     */
    private fun timerExpiredWhileTicking(timerType: TimerType) {

        startReminderText(timerType)

        MainScope().launch {
            alarms.playAlarm(timerType)
        }
    }

    /**
     * Called when a timer expires, so if it's rest time it resets the timing of the
     * reminder text view
     */
    private fun startReminderText(timerType: TimerType): Boolean {
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(getString(R.string.showReminders_pref_key), true)) {
            Log.v(LOG_TAG, "startReminderText: preference is not to show reminder prompt")
            return false
        }

        if (timerType == TimerType.REST ) {
            remindersViewModel.getReminderText()?.let {
                rest_reminder.visibility = View.VISIBLE
                rest_reminder.text = it
            }
        }
        else {
            rest_reminder.visibility = View.GONE
        }

        return true
    }

    /**
     * Called while rest time is showing, so if it's rest time it resets the timing of the
     * reminder text view
     */
    private fun animateReminderText(restElapsed: Long) {
        // in case the activity starts mid rest time and the prompt isn't visible yet
        val startsInvisible = rest_reminder.visibility != View.VISIBLE

        if (startsInvisible && !startReminderText(TimerType.REST)) {
            return // didn't set to visible so don't continue
        }

        // use the progress within a minute to get a y value from sine
        ((restElapsed % PomodoromeDatabase.ONE_MINUTE) / PomodoromeDatabase.ONE_MINUTE.toFloat())
            .also { delta ->
                // detect going over a minute to get another text
                if (delta < lastReminderTextDelta && !startsInvisible) {
                    remindersViewModel.getReminderText()?.let {
                        rest_reminder.text = it
                    }
                }

                lastReminderTextDelta = delta // so can assess when a minute elapses

                // animate the text down to middle and back to top, it should swap to a new text then
                if (rest_reminder.text.isNotEmpty()) {
                    // scale up to full size quickly but not larger
                    rest_reminder.scaleY = min(10f * if (delta <= .5f) delta else 1.0f - delta, 1f)
                    rest_reminder.translationY = (sin(toRadians((360.0 * delta)-90)) * timer_gui.height / 4f).toFloat()
//                    Log.d(LOG_TAG, "delta=$delta scale=${rest_reminder.scaleY} sin=$y")
                }
            }
    }

    /**
     * Toggles between playing a paused, this is where ads consent is triggered too after playing a couple of times
     * the user will have to consent to ads (EU only) before playing again
     */
    fun playPausePressed(@Suppress("UNUSED_PARAMETER") view: View) {

        // currently in editing needs to transition out
        timer_gui.transitionOutOfEditing()

        timerViewModel.getActiveTimer()?.also { timer ->

            // check for ads consent form triggered
            if (!timer.isPlaying() && ++playClicksMade > PLAY_CLICKS_BEFORE_ADS_CONSENT && admobLoader.isTriggerConsentForm(timer)) {
                Log.d(LOG_TAG, "playPausePressed: play pressed abort for consent form to load (clicks=$playClicksMade)")
                setStateForConsentFormOpen(true)
            }
            else {

                // not the current state might mean the editStop button needs to change
                if (timer.isStopped()) {
                    edit_button.setImageDrawable(pencilToStopDrawable.also { it.start() })
                }

                transitioningToTimerState = timerViewModel.toggleStartPauseToState()
                play_button.setImageDrawable((if (transitioningToTimerState == TimerState.PLAYING) playToPauseDrawable else pauseToPlayDrawable)
                    .also {
                        it.start()
                    })
            }
        }
    }

    /**
     * Edit or stop depending on the current state, play/pause or edit
     */
    fun editStopPressed(@Suppress("UNUSED_PARAMETER") view: View) {

        // choose edit... only allowed to edit from stopped
        if (timerViewModel.getActiveTimer()!!.isStopped()) {
            timer_gui.editTimers()             // start transition
            edit_button.setImageDrawable(pencilToStopDrawable.also { it.start() })
            transitioningToTimerState = TimerState.EDITED
            timerViewModel.edit()
        }
        else { // choice is to stop whatever is happening
            transitioningToTimerState = TimerState.STOPPED
            timer_gui.transitionOutOfEditing()   // does nothing if not editing
            edit_button.setImageDrawable(stopToPencilDrawable.also { it.start() })

            // playing means the current button is showing pause icon, change to play
            if (timerViewModel.getActiveTimer()!!.isPlaying()) {
                play_button.setImageDrawable(pauseToPlayDrawable.also { it.start() })
            }

            timerViewModel.stop()

            rest_reminder.visibility = View.GONE
        }
    }

    /**
     * Show the dialog for setting auto-start range
     */
    fun autoStartPressed(@Suppress("UNUSED_PARAMETER") view: View) {
        startActivity(Intent(this, SettingsActivity::class.java)
            .apply {
                putExtra(SettingsActivity.INTENT_EXTRA_DIRECT_TO_ALARMS, 99)
            })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    /**
     * Called by AutoStartStopHelper when triggered from an alarm
     * simulate pressed and put up a snack
     */
    fun onAutoStop() {

        editStopPressed(play_button)

        // show snack bar for undo
        Snackbar.make(play_button, R.string.snack_and_notification_auto_stopped, Snackbar.LENGTH_INDEFINITE)
            .apply {
                setAction(R.string.snack_dismiss) { dismiss() }
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun onConsentFormClosed() {
        setStateForConsentFormOpen(false)
    }

    private fun setStateForConsentFormOpen(isOpening: Boolean) {
        fade_out.visibility = if (isOpening) View.VISIBLE else View.GONE
    }

}
