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

enum class TimerState(val styledAttributeName: IntArray) {
    PLAYING(intArrayOf(R.attr.timerPlaying)),
    PAUSED(intArrayOf(R.attr.timerPaused)),
    STOPPED(intArrayOf(R.attr.timerPaused)),
    EDITED(intArrayOf(R.attr.timerEdited))
}

/**
 * A bit nicer than using constants, see TimePickerCircle
 * The associate values are for preference keys and reverse lookup via
 * the companion lookup overloaded operator functions
 * NOTE: the ringtone keys to these should not be changed, they have to match the key values in
 * root_preferences.xml
 */
enum class TimerType(val ringToneKey: String, val requestCode: Int) {
    POMODORO("com.stillwindsoftware.pomodorome.ringtone.POMODORO", 99),
    REST("com.stillwindsoftware.pomodorome.ringtone.REST", 90),
    NONE("", -1);

    companion object {
        // really neat, can now use array syntax with string key or request code to lookup
        // eg. TimerType[aRingToneKey], (see SettingActivity)
        private val ringtoneMap = values().associateBy(TimerType::ringToneKey)
        operator fun get(key: String) = ringtoneMap[key]

        private val requestCodeMap = values().associateBy(TimerType::requestCode)
        operator fun get(key: Int) = requestCodeMap[key]
    }
}

class Converters {

    @TypeConverter
    fun timerStateToString(stateType: TimerState?): String? {
        return stateType?.toString()
    }

    @TypeConverter
    fun stringToTimerState(str: String?): TimerState? {
        return if (str != null) TimerState.valueOf(str) else null
    }

}