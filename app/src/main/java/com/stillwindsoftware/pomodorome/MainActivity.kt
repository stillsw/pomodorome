package com.stillwindsoftware.pomodorome

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.stillwindsoftware.pomodorome.databinding.ActivityMainBinding
import com.stillwindsoftware.pomodorome.viewmodels.ActiveTimerViewModel
import com.stillwindsoftware.pomodorome.viewmodels.PomodoromeRepository
import kotlinx.android.synthetic.main.activity_main.*

/**
 *
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val LOG_TAG = "MainActivity"
        const val TIMER_DELAY = 100L        // ticking interval, not too long otherwise seconds might not change quick enough
    }

    // timer ticking is controlled by a runnable posted delayed every 1/3 second
    // it's started from the callback from the TimePickerCircle view which is
    // observing the View Model

    private var tick = 0                                // for ticking
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

    }

    fun callbackChangeToTimer(acceptInput: Boolean) {
        if (acceptInput) {
            work_emoji.visibility = View.INVISIBLE
            rest_emoji.visibility = View.INVISIBLE
        }
        else {
            work_emoji.visibility = View.VISIBLE
            rest_emoji.visibility = View.VISIBLE
        }

        updateTrackingOnTimedViews(!acceptInput)
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

    fun playClicked(view: View) {
        time_picker_circle.startTiming()
    }

    fun editTimers(view: View) {
        time_picker_circle.editTimers()
    }

    fun restartTimers(view: View) {
        //TODO
    }

}
