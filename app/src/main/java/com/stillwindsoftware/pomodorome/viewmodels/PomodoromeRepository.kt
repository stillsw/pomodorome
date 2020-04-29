package com.stillwindsoftware.pomodorome.viewmodels

import androidx.lifecycle.LiveData
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.ActiveTimerDao
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase
import com.stillwindsoftware.pomodorome.db.Reminder

/**
 * Following the suggested best practice for Room, the repository is created by the view model
 * and becomes the go between for database actions
 */
class PomodoromeRepository (private val database: PomodoromeDatabase) {

    companion object {
        const val LOG_TAG = "PomodoromeRepository"
    }

    val timer = database.activeTimerDao().getTimer()
    val reminders = database.remindersDao().getRemindersInOrder()

    suspend fun update(activeTimer: ActiveTimer) {
        database.activeTimerDao().update(activeTimer)
    }

    suspend fun update(reminder: Reminder) {
        database.remindersDao().update(reminder)
    }

    suspend fun insert(reminder: Reminder) {
        database.remindersDao().insert(reminder)
    }

    suspend fun delete(reminder: Reminder) {
        database.remindersDao().delete(reminder)
    }
}

