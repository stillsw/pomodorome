package com.stillwindsoftware.pomodorome.db

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Called from the AlarmsReceiver needs to act on the timer in the DB
 * use a coroutine off the main thread
 */
suspend fun withTimerIOThread(context: Context, update: Boolean = false, func: (ActiveTimer) -> Unit) {

    withContext(Dispatchers.IO) {
        PomodoromeDatabase.getDatabase(context.applicationContext, this)
            .activeTimerDao().apply {
                getTimerDirect().also {activeTimer ->
                    func(activeTimer)
                    if (update) {
                        update(activeTimer)
                    }
                }
            }
    }
}

