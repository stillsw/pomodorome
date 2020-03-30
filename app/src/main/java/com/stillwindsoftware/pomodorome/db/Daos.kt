package com.stillwindsoftware.pomodorome.db

import androidx.room.*
import com.stillwindsoftware.pomodorome.R

/**
 * BaseDao interface with crud methods for Daos these all suspend to use coroutines
 * Also Types and converters
 */
interface BaseDao<T> {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(vararg obj: T)

    @Delete
    suspend fun delete(vararg obj: T)

    @Update
    suspend fun update(vararg obj: T)
}

enum class TimerStateType(val styledAttributeName: IntArray) {
    PLAYING(intArrayOf(R.attr.timerPlaying)),
    PAUSED(intArrayOf(R.attr.timerPaused)),
    STOPPED(intArrayOf(R.attr.timerPaused)),
    EDITED(intArrayOf(R.attr.timerEdited))
}

class Converters {

    @TypeConverter
    fun timerStateTypeToString(stateType: TimerStateType?): String? {
        return stateType?.toString()
    }

    @TypeConverter
    fun stringToTimerStateType(str: String?): TimerStateType? {
        return if (str != null) TimerStateType.valueOf(str) else null
    }

}