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
    @ColumnInfo(name = "timer_state") var timerState: TimerState
) {

    fun pause() {
        pauseAtTime = System.currentTimeMillis()
        timerState = TimerState.PAUSED
    }

    fun start() {
        val now = System.currentTimeMillis()
        if (isPaused()) {
            previousPausesAccumulated += now - pauseAtTime
            pauseAtTime = 0L
        }
        else if (!isPlaying()) {
            startTime = now
        }

        timerState = TimerState.PLAYING
    }

    fun stop() {
        startTime = 0L
        pauseAtTime = 0L
        previousPausesAccumulated = 0L
        timerState = TimerState.STOPPED
    }

    fun edit() {
        startTime = 0L
        pauseAtTime = 0L
        previousPausesAccumulated = 0L
        timerState = TimerState.EDITED
    }

    /**
     * Alarms processing uses this to create alarm to fire for the next time one of the timers expires
     */
    fun getMillisTillNextEvent(now: Long): Pair<TimerType, Long>? {

        return when (timerState) {
            TimerState.PLAYING -> {
                val elapsedTiming = getElapsedMillis(now)
                // the next event is the other one than is current, unless rest duration is 0, then always pomodoro
                if (elapsedTiming < pomodoroDuration && restDuration != 0L) {
                    TimerType.REST to pomodoroDuration - elapsedTiming
                } else {
                    TimerType.POMODORO to pomodoroDuration + restDuration - elapsedTiming
                }
            }
            else -> null
        }
    }

    fun getElapsedMillis(now: Long): Long {
        val fromTime = if (isPaused()) pauseAtTime else now
        with(fromTime - startTime - previousPausesAccumulated) {       // this = total elapsed since start
            return (this % (pomodoroDuration + restDuration)).also {     // it = elapsed in this timing
                Log.v(LOG_TAG, "getElapsedMillis: totalMillis=$this elapsedTime=$it bothDurations=${pomodoroDuration + restDuration} pom=$pomodoroDuration rest=$restDuration")
            }
        }
    }

    fun isPlaying() = timerState == TimerState.PLAYING
    fun isPaused() = timerState == TimerState.PAUSED
    fun isStopped() = timerState == TimerState.STOPPED
    fun isEdited() = timerState == TimerState.EDITED
    fun isTrackingTiming() = isPlaying() || isPaused()

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

    @Query( "SELECT timer_state FROM active_timers")
    abstract fun getTimerState(): TimerState
}