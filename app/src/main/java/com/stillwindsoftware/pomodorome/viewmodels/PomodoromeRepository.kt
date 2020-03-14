package com.stillwindsoftware.pomodorome.viewmodels

import androidx.lifecycle.LiveData
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.ActiveTimerDao

/**
 * Following the suggested best practice for Room, the repository is created by the view model
 * and becomes the go between for database actions
 */
class PomodoromeRepository (private val activeTimerDao: ActiveTimerDao) {

    val timer: LiveData<ActiveTimer> = activeTimerDao.getTimer()

    suspend fun update(activeTimer: ActiveTimer) {
        activeTimerDao.update(activeTimer)
    }

}