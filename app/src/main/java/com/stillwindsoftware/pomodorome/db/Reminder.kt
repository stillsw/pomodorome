package com.stillwindsoftware.pomodorome.db

import androidx.lifecycle.LiveData
import androidx.room.*

/**
 * Rest time reminders, a list the user can choose from
 * and add/remove
 */
@Entity(tableName = "reminders")
data class Reminder (
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "reminder_id") var reminderId: Long?,
    @ColumnInfo(name = "text") var text: String,
    @ColumnInfo(name = "selected") var selected: Boolean)

@Dao
abstract class RemindersDao : BaseDao<Reminder> {

    @Query("SELECT * FROM reminders ORDER BY LOWER(text)")
    abstract fun getRemindersInOrder(): LiveData<List<Reminder>>

    @Query("DELETE FROM reminders")
    abstract suspend fun deleteAll()

}

