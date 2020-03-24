package com.stillwindsoftware.pomodorome.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase.Companion.ONE_MINUTE
import kotlinx.coroutines.launch

/**
 * View model connects to the repository and provides non-blocking scope for updates.
 * ViewModels have a coroutine scope based on their lifecycle called viewModelScope
 */
class ActiveTimerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val LOG_TAG = "ActiveTimerViewModel"
    }

    // The ViewModel maintains a reference to the repository to get data.
    private val repository: PomodoromeRepository
    val timer: LiveData<ActiveTimer>

    init {
        val activeTimerDao = PomodoromeDatabase.getDatabase(application, viewModelScope).activeTimerDao()
        repository = PomodoromeRepository(activeTimerDao)
        timer = repository.timer
    }

    /**
     * A wrapper for update() in the database
     */
    private fun update(activeTimer: ActiveTimer) = viewModelScope.launch {
        repository.update(activeTimer)
    }

    /**
     * Called from the "minutes" delegate of each timer when input is allowed (ie. editing)
     * and the value changed
     */
    fun updateTime(timeInMillis: Long, isWorkTime: Boolean) {
        val activeTimer = timer.value!!

        if (isWorkTime && timeInMillis != activeTimer.pomodoroDuration) {
            activeTimer.pomodoroDuration = timeInMillis
            update(activeTimer)
            Log.d(LOG_TAG, "updateTime: pomodoro duration updated")
        }
        else if (!isWorkTime && timeInMillis != activeTimer.restDuration) {
            activeTimer.restDuration = timeInMillis
            update(activeTimer)
            Log.d(LOG_TAG, "updateTime: rest duration updated")
        }
    }

    fun start() {
        timer.value!!.also {
            it.start()
            val nextEvent = it.getMillisTillNextEvent(System.currentTimeMillis())
            Log.d(LOG_TAG, "start: next event in ${nextEvent / ONE_MINUTE} mins and ${nextEvent % ONE_MINUTE} secs")

            update(it)

            //todo create alarm
        }

    }

    fun stopIfActive() {
        timer.value!!.also {
            if (it.isActive()) {
                it.stop()
                Log.d(LOG_TAG, "stopIfActive: toggled to stop")

                update(it)

                //todo cancel alarms
            }
        }

    }
}