package com.stillwindsoftware.pomodorome

import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.stillwindsoftware.pomodorome.databinding.ActivityMainBinding
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.TimerType
import com.stillwindsoftware.pomodorome.events.Alarms
import com.stillwindsoftware.pomodorome.events.Notifications
import com.stillwindsoftware.pomodorome.viewmodels.ActiveTimerViewModel
import com.stillwindsoftware.pomodorome.viewmodels.PomodoromeRepository
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

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

    // timer ticking is controlled by a runnable posted delayed every 1/10 second
    // it's started from the callback from the TimePickerCircle view which is
    // observing the View Model

    private var isTimingBeingTrackedViews = false           // false when timers being modified
    private var isTimerTrackingRunnablePosted = false   // make sure only posted once
    private val timerTrackingRunnable = Runnable {
        isTimerTrackingRunnablePosted = false

        if (isTimingBeingTrackedViews) {
            updateTrackingOnTimedViews(true)
        }
    }

    private val viewModel by lazy { ViewModelProvider(this)[ActiveTimerViewModel::class.java] }
    private val alarms = Alarms(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PomodoromeRepository.initEmojis(applicationContext)

        val binding : ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewmodel = viewModel

        setSupportActionBar(toolbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notifications(this).createNotificationChannel()

        time_picker_circle.timerWidgets[TimerGui.WORK].timePickerTextView = work_time
        time_picker_circle.timerWidgets[TimerGui.REST].timePickerTextView = rest_time

        playToPauseDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.play_to_pause_avd)!!
        pauseToPlayDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.pause_to_play_avd)!!
        pencilToStopDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.pencil_to_stop_avd)!!
        stopToPencilDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.stop_to_pencil_avd)!!
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

        if (isTimingBeingTrackedViews) {
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

        with(viewModel.getActiveTimer()) {
            if (this.isPlaying()) {
                alarms.setBackgroundAlarm(this)
            }
        }
    }

    /**
     * Called from callbackChangeToTimer() and from onStart()/onStop()
     * If onOrOff is true timing begins by posting the tick runnable delayed
     * otherwise setting the var to false will prevent the next post
     * The runnable calls this method again while timing is being tracked
     */
    private fun updateTrackingOnTimedViews(onOrOff: Boolean) {

        isTimingBeingTrackedViews = onOrOff

        if (isTimingBeingTrackedViews && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {

            with(time_picker_circle.doTick()) {
                if (this != TimerType.NONE) {
                    Log.d(LOG_TAG, "updateTrackingOnTimedViews: timer expired this go round")
                    timerExpiredWhileTicking(this)
                }
            }

            if (!isTimerTrackingRunnablePosted) {
                isTimerTrackingRunnablePosted = true
                time_picker_circle.postDelayed(timerTrackingRunnable, TIMER_DELAY)
            }
        }
    }

    /**
     * React to timer expiring while the app is running:
     * - play a sound if set (in a coroutine so it can also wait a bit and then stop the ringtone)
     * - vibrate if set
     * - show rest time prompts
     */
    private fun timerExpiredWhileTicking(timerType: TimerType) {
        //todo add vibrate and prompting etc
        MainScope().launch {
            alarms.playAlarmSound(timerType)
        }
    }

    /**
     * Toggles between playing a paused
     */
    fun playPausePressed(@Suppress("UNUSED_PARAMETER") view: View) {

        // currently in editing needs to transition out
        time_picker_circle.transitionOutOfEditing()

        // not the current state might mean the editStop button needs to change
        if (viewModel.getActiveTimer().isStopped()) {
            edit_button.setImageDrawable(pencilToStopDrawable.also { it.start() })
        }

        viewModel.toggleStartPause().also { isStarted ->
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
        if (viewModel.getActiveTimer().isStopped()) {
            time_picker_circle.editTimers()             // start transition
            edit_button.setImageDrawable(pencilToStopDrawable.also { it.start() })
            viewModel.edit()
        }
        else { // choice is to stop whatever is happening
            time_picker_circle.transitionOutOfEditing()   // does nothing if not editing
            edit_button.setImageDrawable(stopToPencilDrawable.also { it.start() })

            // playing means the current button is showing pause icon, change to play
            if (viewModel.getActiveTimer().isPlaying()) {
                play_button.setImageDrawable(pauseToPlayDrawable.also { it.start() })
            }

            viewModel.stop()
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
