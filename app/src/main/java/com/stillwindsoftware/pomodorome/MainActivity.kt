package com.stillwindsoftware.pomodorome

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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Thanks to Alex Lockwood for his excellent shape shifter path morphing tool which I used
 * to create the animated vector drawables (https://beta.shapeshifter.design/)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        @Suppress("unused")
        private const val LOG_TAG = "MainActivity"
        const val TIMER_DELAY = 100L        // ticking interval, not too long otherwise seconds might not change quick enough
    }

    // the animated vector drawables cached in onCreate() then assigned in callbackChangeToTimer

    private lateinit var playToPauseDrawable: AnimatedVectorDrawableCompat
    private lateinit var pauseToPlayDrawable: AnimatedVectorDrawableCompat
    private lateinit var pencilToStopDrawable: AnimatedVectorDrawableCompat
    private lateinit var stopToPencilDrawable: AnimatedVectorDrawableCompat

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
    private val alarms = Alarms(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmojiHelper.initEmojis(applicationContext)

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
    }

    fun callbackChangeToTimer(activeTimer: ActiveTimer) {
//        if (acceptInput) {
//            work_emoji.visibility = View.INVISIBLE
//            rest_emoji.visibility = View.INVISIBLE
//        }
//        else {
//            work_emoji.visibility = View.VISIBLE
//            rest_emoji.visibility = View.VISIBLE
//
//        }

        Log.d(LOG_TAG, "callbackChangeToTimer: call update tracking isTracking=${activeTimer.isTrackingTiming()}")
        // change of state, ticking the timers turns on or off
        updateTrackingOnTimedViews(activeTimer.isTrackingTiming())

        // when first start up the animated vector buttons have no drawable assigned
        if (play_button.drawable == null) {
//todo bug, not sure why stop to pencil is sometimes wrong image            val stopBitmap = BitmapDrawable(resources, stopToPencilDrawable.toBitmap())
// todo seems pause sometimes too, when already on pause, turn device and it's showing pause again
            play_button.setImageDrawable(if (activeTimer.isPlaying()) pauseToPlayDrawable else playToPauseDrawable)
            edit_button.setImageDrawable(if (activeTimer.isStopped()) pencilToStopDrawable else stopToPencilDrawable)
        }
    }

    /**
     * Timing will track automatically after the activity is created, but if it's
     * restarted have to test for getting it going again
     */
    override fun onResume() {
        super.onResume()

        if (isTrackingTiming) {
            updateTrackingOnTimedViews(true)

            //todo also here detect if need to be showing rest prompts... ie. in rest time
        }

    }

    /**
     * Background alarm and notification canceled, here rather than in onStop() because the
     * notification may start this activity to show
     * wake up on the notification, the drawback is the drain on the battery to keep a
     * background alarm going.
     */
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        val ret = super.dispatchTouchEvent(event)
        if (event?.action == MotionEvent.ACTION_DOWN) {
            Log.d(LOG_TAG, "dispatchTouchEvent: action down, cancel background alarm and notification")
            alarms.cancelBackgroundAlarm()
            Notifications(this).cancelNotifications()
        }
        return ret
    }

    /**
     * Set an alarm for the next event only if currently playing
     */
    override fun onStop() {
        super.onStop()

        with(timerViewModel.getActiveTimer()) {
            if (isPlaying()) {
                alarms.setBackgroundAlarm(this)
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

        if (isTrackingTiming && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {

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

        //todo add prompting etc

        startReminderText(timerType)

        MainScope().launch {
            alarms.playAlarm(timerType)
        }
    }

    /**
     * Called when a timer expires, so if it's rest time it resets the timing of the
     * reminder text view
     */
    private fun startReminderText(timerType: TimerType) {
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(getString(R.string.showReminders_pref_key), false)) {
            Log.d(LOG_TAG, "startReminderText: preference is not to show reminder prompt")
            return
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
    }

    /**
     * Called while rest time is showing, so if it's rest time it resets the timing of the
     * reminder text view
     */
    private fun animateReminderText(restElapsed: Long) {
        // in case the activity starts mid rest time and the prompt isn't visible yet
        val startsInvisible = rest_reminder.visibility != View.VISIBLE

        if (startsInvisible) {
            startReminderText(TimerType.REST)
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
     * Toggles between playing a paused
     */
    fun playPausePressed(@Suppress("UNUSED_PARAMETER") view: View) {

        // currently in editing needs to transition out
        timer_gui.transitionOutOfEditing()

        // not the current state might mean the editStop button needs to change
        if (timerViewModel.getActiveTimer().isStopped()) {
            edit_button.setImageDrawable(pencilToStopDrawable.also { it.start() })
        }

        timerViewModel.toggleStartPause().also { isStarted ->
            play_button.setImageDrawable((if (isStarted) playToPauseDrawable else pauseToPlayDrawable).also {
                it.start()
            })
        }

    }

    /**
     * Edit or stop depending on the current state, play/pause or edit
     */
    fun editStopPressed(@Suppress("UNUSED_PARAMETER") view: View) {

        // choose edit... only allowed to edit from stopped
        if (timerViewModel.getActiveTimer().isStopped()) {
            timer_gui.editTimers()             // start transition
            edit_button.setImageDrawable(pencilToStopDrawable.also { it.start() })
            timerViewModel.edit()
        }
        else { // choice is to stop whatever is happening
            timer_gui.transitionOutOfEditing()   // does nothing if not editing
            edit_button.setImageDrawable(stopToPencilDrawable.also { it.start() })

            // playing means the current button is showing pause icon, change to play
            if (timerViewModel.getActiveTimer().isPlaying()) {
                play_button.setImageDrawable(pauseToPlayDrawable.also { it.start() })
            }

            timerViewModel.stop()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
