package com.stillwindsoftware.pomodorome

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.stillwindsoftware.pomodorome.db.Reminder
import com.stillwindsoftware.pomodorome.db.RemindersDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The current list of active reminders is kept in a shared preference
 * so the notification can access it. This class handles access to them
 * including refreshing the whole list when there's a db change
 */
class RemindersHelper(private val context: Context) {

    companion object {
        const val LOG_TAG = "RemindersHelper"
        const val REMINDERS_LIST_PREF = "remindersListPref"
        private var lastReminderIndex = -1
        private var notificationSawReminder: String? = null
    }

    fun refreshList(reminders: List<Reminder>) {
        PreferenceManager.getDefaultSharedPreferences(context).also { prefs ->

            prefs.edit().also { editor ->
                editor.putStringSet(REMINDERS_LIST_PREF,
                    reminders.filter { it.selected }.map { it.text }.toSet())
                editor.apply()
            }
        }

        lastReminderIndex = -1
        notificationSawReminder = null
    }

    fun refreshList(remindersDao: RemindersDao) {
        MainScope().launch {
            withContext(Dispatchers.IO) {
                refreshList(remindersDao.getRemindersDirectly())
            }
        }
    }

    /**
     * lastReminderIndex is a singleton value so it will keep cycling every time
     * this method is called if order isn't set to random or just ensure
     * the same reminder doesn't come up twice in a row when it is set to random
     */
    fun getNextReminder(isNotification: Boolean = false): String {

        // in the case where a notification has seen a reminder, it is cached so the activity will see the same thing when it opens up
        // so when the activity is looking and there's a cached value, just hand it back that one and go no further

        if (!isNotification && notificationSawReminder != null) {
            return notificationSawReminder!!.also { notificationSawReminder = null }
        }

        // utility function

        fun findNextReminderText(inOrder: Boolean, reminders: List<String>): String {

            reminders.let {
                when {
                    reminders.isEmpty() -> return ""
                    reminders.size == 1 -> return reminders.first()
                    inOrder -> {

                        // in order, to the user that means alphabetically which is the order of
                        // reminders as inserted into the set

                        return if (lastReminderIndex >= reminders.size - 1) {
                            reminders.first()
                        } else {
                            reminders.elementAt(++lastReminderIndex)
                        }
                    }
                    else -> { // random

                        reminders.filterIndexed { index, _ -> index != lastReminderIndex }
                            .random()
                            .also {
                                return it
                            }
                    }
                }
            }
        }

        PreferenceManager.getDefaultSharedPreferences(context).also { prefs ->

            if (!prefs.contains(REMINDERS_LIST_PREF)) {
                Log.d(LOG_TAG, "getNextReminder: no reminders in shared prefs")
                return ""
            }

            prefs.getStringSet(REMINDERS_LIST_PREF, null)!!.sorted().also {reminders->
                return findNextReminderText(prefs.getBoolean(context.resources.getString(R.string.showReminders_random_pref_key), true), reminders)
                    .also {
                        lastReminderIndex = reminders.indexOf(it)

                        // when the notification has been viewed by a notification, the next time the activity is shown, it should also see that
                        if (isNotification) {
                            notificationSawReminder = it
                        }
                    }
            }
        }

    }

}