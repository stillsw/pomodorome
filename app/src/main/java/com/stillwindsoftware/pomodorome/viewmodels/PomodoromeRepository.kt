package com.stillwindsoftware.pomodorome.viewmodels

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.RemindersHelper
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase
import com.stillwindsoftware.pomodorome.db.Reminder

/**
 * Following the suggested best practice for Room, the repository is created by the view model
 * and becomes the go between for database actions
 */
class PomodoromeRepository(private val applicationContext: Context, private val database: PomodoromeDatabase) {

    companion object {
        const val LOG_TAG = "PomodoromeRepository"
    }

    val timer = database.activeTimerDao().getTimer()
    val reminders = database.remindersDao().getReminders()

    suspend fun update(activeTimer: ActiveTimer) {
        database.activeTimerDao().update(activeTimer)
    }

    suspend fun update(reminder: Reminder) {
        database.remindersDao().update(reminder)
        RemindersHelper(applicationContext).refreshList(database.remindersDao())
    }

    suspend fun insert(reminder: Reminder) {
        database.remindersDao().insert(reminder)
        RemindersHelper(applicationContext).refreshList(database.remindersDao())
    }

    suspend fun delete(reminder: Reminder) {
        database.remindersDao().delete(reminder)
        RemindersHelper(applicationContext).refreshList(database.remindersDao())
    }
}

