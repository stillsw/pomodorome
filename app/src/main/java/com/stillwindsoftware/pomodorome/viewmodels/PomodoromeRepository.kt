package com.stillwindsoftware.pomodorome.viewmodels

import android.content.Context
import android.util.Log
import android.util.LogPrinter
import androidx.preference.PreferenceManager
import com.stillwindsoftware.pomodorome.R
import com.stillwindsoftware.pomodorome.db.ActiveTimer
import com.stillwindsoftware.pomodorome.db.PomodoromeDatabase
import com.stillwindsoftware.pomodorome.db.Reminder

/**
 * Following the suggested best practice for Room, the repository is created by the view model
 * and becomes the go between for database actions
 */
class PomodoromeRepository (private val database: PomodoromeDatabase) {

    companion object {
        const val LOG_TAG = "PomodoromeRepository"
        private var lastReminderIndex = -1L
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

    /**
     * lastReminderIndex is a singleton value so it will keep cycling every time
     * this method is called if order isn't set to random or just ensure
     * the same reminder doesn't come up twice in a row when it is set to random
     */
    fun getNextReminder(context: Context): Reminder? {

        fun findNextReminder(): Reminder? {
            reminders.value?.filter { it.selected }
                ?.apply {
                    when (size) {
                        0 -> return null
                        1 -> return get(0)
                        else -> {
                            // choose which depends on random or order and what was last displayed
                            with(PreferenceManager.getDefaultSharedPreferences(context))
                            {
                                // true means in order, to the user that means alphabetically which is the order of
                                // reminders from the cursor, however, it's also possible the user has removed
                                // the last shown reminder since it was shown

                                if (getBoolean(context.resources.getString(R.string.showReminders_random_pref_key), false)) {

                                    var prevReminder: Reminder? = null

                                    forEach {
                                        if (prevReminder != null) {
                                            // the next one, so use it and done
                                            return it
                                        }

                                        if (it.reminderId == lastReminderIndex) {
                                            prevReminder = it
                                        }
                                    }

                                    // popped out of the loop w/o assigning the next reminder, cycle back to the first again
                                    return get(0)
                                }
                                else { // random order, exclude the last one used
                                    filter { it.reminderId != lastReminderIndex }
                                        .random()
                                        .also {
                                            return it
                                        }
                                }

                            }

                        }
                    }
                }

                // didn't resolve to find any reminders
                Log.d(LOG_TAG, "getNextReminder: didn't resolve to find one")
                return null
        }

        return findNextReminder()?.also {
            lastReminderIndex = it.reminderId!!
        }
    }

}

