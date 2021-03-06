package com.stillwindsoftware.pomodorome.db

import com.stillwindsoftware.pomodorome.R
import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stillwindsoftware.pomodorome.RemindersHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The database instance
 * Also handles creation and initialization
 */
@Database(entities = [ActiveTimer::class, Reminder::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class PomodoromeDatabase : RoomDatabase() {

    abstract fun activeTimerDao(): ActiveTimerDao
    abstract fun remindersDao(): RemindersDao

    companion object {
        const val LOG_TAG = "PomodoromeDatabase"
        const val ONE_MINUTE = 60L * 1000L

        // Singleton prevents multiple instances of database opening at the
        // same time.
        @Volatile
        private var INSTANCE: PomodoromeDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): PomodoromeDatabase {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }
            synchronized(this) {
                val instance = Room.databaseBuilder(
                        context.applicationContext,
                        PomodoromeDatabase::class.java,
                        "pomodorome_database"
                    )
                    .addCallback(PomodoromeDatabaseCallback(context, scope))
                    .build()
                INSTANCE = instance
                return instance
            }
        }
    }

    private class PomodoromeDatabaseCallback(val context: Context, private val scope: CoroutineScope) : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                scope.launch {
                    populateDatabase(database.activeTimerDao(), database.remindersDao())
                }
            }
        }

        suspend fun populateDatabase(activeTimerDao: ActiveTimerDao, remindersDao: RemindersDao) {
            // only called on create, so delete everything, which should be redundant
            activeTimerDao.deleteAll()
            remindersDao.deleteAll()

            // add a single active timer
            activeTimerDao.insert(
                ActiveTimer(0L,   // hopefully ignored since it's autoGenerated
                    25 * ONE_MINUTE,        // default to 25 minutes
                    5 * ONE_MINUTE,             // with a 5 minute rest
                    0L,
                    0L,
                    0L,
                    TimerState.STOPPED                 // state is stopped until user kicks it off
                ))

            // and all the reminders from strings
            remindersDao.insert(*context.resources.getStringArray(R.array.default_reminders)
                // only the first 3 are selected by default
                .mapIndexed { index, it -> Reminder(null, it, index < 3) }
                .toTypedArray()
            )

            // put the selected reminders into shared preferences, so the notifications can access them
            // running on the main thread, so avoid a delay by launching another co-routine

            scope.launch {
                withContext(Dispatchers.IO) {
                    RemindersHelper(context).refreshList(remindersDao.getRemindersDirectly())
                }
            }
        }
    }
}

