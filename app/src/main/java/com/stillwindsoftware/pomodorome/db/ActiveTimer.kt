package com.stillwindsoftware.pomodorome.db

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.*

/**
 * All things database to do with ActiveTimer table/dao
 * The Dao is very simple as there is only one active timer
 */
@Entity(tableName = "active_timers")
data class ActiveTimer(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "timer_id") var timerId: Long?,
    @ColumnInfo(name = "pomodoro_duration") var pomodoroDuration: Long,
    @ColumnInfo(name = "rest_duration") var restDuration: Long,
    @ColumnInfo(name = "start_time") var startTime: Long,
    @ColumnInfo(name = "pause_at_time") var pauseAtTime: Long,
    @ColumnInfo(name = "paused_accum") var previousPausesAccumulated: Long,
    @ColumnInfo(name = "timer_state") var timerState: TimerStateType
) {

    fun pause() {
        pauseAtTime = System.currentTimeMillis()
        timerState = TimerStateType.PAUSED
    }

    fun start() {
        val now = System.currentTimeMillis()
        if (timerState == TimerStateType.PAUSED) {
            previousPausesAccumulated += now - pauseAtTime
            pauseAtTime = 0L
        }
        else if (timerState == TimerStateType.STOPPED) {
            startTime = now
        }

        timerState = TimerStateType.ACTIVE
    }

    fun stop() {
        pauseAtTime = 0L
        previousPausesAccumulated = 0L
        timerState = TimerStateType.STOPPED
    }

    fun getMillisTillNextEvent(now: Long): Long {

        return when (timerState) {
            TimerStateType.ACTIVE -> {
                val totalMillis = startTime - previousPausesAccumulated - now
                val elapsedTiming = totalMillis % (pomodoroDuration + restDuration)
                Log.d(LOG_TAG, "getMillisTillNextEvent: totalMillis=$totalMillis elapsedTime=$elapsedTiming")
                if (elapsedTiming < pomodoroDuration) {
                    pomodoroDuration - elapsedTiming
                } else {
                    pomodoroDuration + restDuration - elapsedTiming
                }
            }
            else -> Long.MAX_VALUE
        }

//        return pomodoroDuration - when (timerState) {
//            TimerStateType.ACTIVE -> now - startTime - previousPausesAccumulated
//            TimerStateType.PAUSED -> pauseAtTime - startTime - previousPausesAccumulated
//            else -> // stopped
//                0L
//        }
    }

    fun isActive(): Boolean = timerState == TimerStateType.ACTIVE
    fun isPaused(): Boolean = timerState == TimerStateType.PAUSED
    fun isStopped(): Boolean = timerState == TimerStateType.STOPPED
    fun isPausedOrStopped(): Boolean = timerState == TimerStateType.PAUSED || timerState == TimerStateType.STOPPED

    companion object {
        private const val LOG_TAG = "ActiveTimer"
    }
}

@Dao
abstract class ActiveTimerDao : BaseDao<ActiveTimer> {

    @Query("SELECT * FROM active_timers")
    abstract fun getTimer(): LiveData<ActiveTimer>

    @Query("DELETE FROM active_timers")
    abstract suspend fun deleteAll()
}