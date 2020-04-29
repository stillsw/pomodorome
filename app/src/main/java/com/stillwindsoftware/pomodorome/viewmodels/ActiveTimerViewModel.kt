package com.stillwindsoftware.pomodorome.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import kotlinx.coroutines.launch

/**
 * View model connects to the repository and provides non-blocking scope for updates.
 * ViewModels have a coroutine scope based on their lifecycle called viewModelScope
 */
class ActiveTimerViewModel(application: Application) : PomodoromeViewModel(application) {

    companion object {
        private const val LOG_TAG = "ActiveTimerViewModel"
    }

    /**
     * A wrapper for update() in the database
     * Also detects any change that is to a playing timer and notifies the alarm manager
     * (updates it, changes it to another state, changes from another state to playing)
    */
    private fun update(activeTimer: ActiveTimer) = viewModelScope.launch {
        repository.update(activeTimer)
    }

    /**
     * Called from the "minutes" delegate of each timer when the value changed in edit mode
     */
    fun updateTime(timeInMillis: Long, isWorkTime: Boolean) {
        val activeTimer = repository.timer.value!!

        if (isWorkTime && timeInMillis != activeTimer.pomodoroDuration) {
            activeTimer.pomodoroDuration = timeInMillis
            update(activeTimer)
        }
        else if (!isWorkTime && timeInMillis != activeTimer.restDuration) {
            activeTimer.restDuration = timeInMillis
            update(activeTimer)
        }
    }

    fun toggleStartPause(): Boolean {
        var isStart = true

        repository.timer.value!!.also {
            if (it.isPlaying()) {
                it.pause()
                isStart = false
            }
            else {
                it.start()
            }
            update(it)

        }

        return isStart
    }

    fun stop() {
        repository.timer.value!!.also {
            if (!it.isStopped()) {
                it.stop()
                update(it)
            }
        }
    }

    fun edit() {
        repository.timer.value!!.also {
            if (!it.isEdited()) {
                it.edit()
                update(it)
            }
        }
    }

    /**
     * Convenience method
     */
    fun getActiveTimer() = repository.timer.value!!

    /**
     * Called when ticking the timers, returns how many millis left for
     * the timer in question
     */
    fun getElapsedMillis(isForWork: Boolean, now: Long): Long {
        repository.timer.value!!.also {
            with(it.getElapsedMillis(now)) {
                return if (isForWork) {                     // work time
                    if (this < it.pomodoroDuration) {       // elapsed if under pomodoro duration
                        this
                    }
                    else {                                  // otherwise the total
                        it.pomodoroDuration
                    }
                }
                else {                                      // rest time
                    if (this <= it.pomodoroDuration) {      // 0 if haven't completed pomodoro yet
                        0L
                    }
                    else {
                        this - it.pomodoroDuration          // otherwise total less the pomodoro
                    }
                }
            }
        }
    }
}