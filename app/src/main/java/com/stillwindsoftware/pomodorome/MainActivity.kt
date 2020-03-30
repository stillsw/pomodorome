package com.stillwindsoftware.pomodorome

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.stillwindsoftware.pomodorome.databinding.ActivityMainBinding
import com.stillwindsoftware.pomodorome.viewmodels.ActiveTimerViewModel
import com.stillwindsoftware.pomodorome.viewmodels.PomodoromeRepository
import kotlinx.android.synthetic.main.activity_main.*

/**
 * Thanks to Alex Lockwood for his excellent shape shifter path morphing tool which I used
 * to create the animated vector drawables (https://beta.shapeshifter.design/)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val LOG_TAG = "MainActivity"
        const val TIMER_DELAY = 100L        // ticking interval, not too long otherwise seconds might not change quick enough
    }

    // the animated vector drawables cached in onCreate() then assigned in callbackChangeToTimer

    private lateinit var playToPauseDrawable: AnimatedVectorDrawableCompat
    private lateinit var pauseToPlayDrawable: AnimatedVectorDrawableCompat
    private lateinit var pencilToStopDrawable: AnimatedVectorDrawableCompat
    private lateinit var stopToPencilDrawable: AnimatedVectorDrawableCompat

    // timer ticking is controlled by a runnable posted delayed every 1/3 second
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PomodoromeRepository.initEmojis(applicationContext)

        val binding : ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewmodel = viewModel

        setSupportActionBar(toolbar)

        time_picker_circle.timerWidgets[TimePickerCircle.WORK].timePickerTextView = work_time
        time_picker_circle.timerWidgets[TimePickerCircle.REST].timePickerTextView = rest_time

        playToPauseDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.play_to_pause_avd)!!
        pauseToPlayDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.pause_to_play_avd)!!
        pencilToStopDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.pencil_to_stop_avd)!!
        stopToPencilDrawable = AnimatedVectorDrawableCompat.create(this, R.drawable.stop_to_pencil_avd)!!
    }

    fun callbackChangeToTimer(isEditing: Boolean, paused: Boolean) {
//        if (acceptInput) {
//            work_emoji.visibility = View.INVISIBLE
//            rest_emoji.visibility = View.INVISIBLE
//        }
//        else {
//            work_emoji.visibility = View.VISIBLE
//            rest_emoji.visibility = View.VISIBLE
//
//        }

        // change of state, ticking the timers turns on or off
        updateTrackingOnTimedViews(!isEditing)

        // when first start up the play button has no drawable
        if (play_button.drawable == null) {
            play_button.setImageDrawable(if (isEditing || paused) {
                Log.d(LOG_TAG, "callbackChangeToTimer: editing=$isEditing paused=$paused} set play to pause")
                playToPauseDrawable
            }
            else {
                Log.d(LOG_TAG, "callbackChangeToTimer: both false set pause to play")
                pauseToPlayDrawable
            })
            edit_button.setImageDrawable(if (!isEditing) pencilToStopDrawable else stopToPencilDrawable)
        }
    }

    /**
     * Timing will track automatically after the activity is created, but if it's
     * restarted have to test for getting it going again
     */
    override fun onStart() {
        super.onStart()
        if (isTimingBeingTrackedViews) {
            updateTrackingOnTimedViews(true)
        }
    }

    /**
     * Stop ticking the timers
     */
    override fun onStop() {
        super.onStop()
        updateTrackingOnTimedViews(false)
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

            time_picker_circle.doTick()

            if (!isTimerTrackingRunnablePosted) {
                isTimerTrackingRunnablePosted = true
                time_picker_circle.postDelayed(timerTrackingRunnable, TIMER_DELAY)
            }
        }
    }

    /**
     * Only reacts if there's a timer, which there will always be, but wrapped in let
     * for extra safety anyway
     */
    fun playClicked(@Suppress("UNUSED_PARAMETER") view: View) {
        time_picker_circle.toggleRunTiming()?.let {isStarted ->
            play_button.setImageDrawable((if (isStarted) playToPauseDrawable else pauseToPlayDrawable).also {
                it.start()
            })
        }
    }

    fun editTimers(@Suppress("UNUSED_PARAMETER") view: View) {
        time_picker_circle.editTimers()
        // todo refactor timer states to include edit
        edit_button.setImageDrawable((if (!viewModel.timer.value!!.isStopped()) stopToPencilDrawable else pencilToStopDrawable).also {
            it.start()
        })
    }


}
