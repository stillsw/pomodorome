package com.stillwindsoftware.pomodorome

import java.util.*

/**
 * Extension function sets the timezone to GMT and clears all times so only dealing with
 * the hours and minutes passed
 */
fun GregorianCalendar.onlyTimeMillis(hours: Int, minutes: Int): Long {
    timeZone = TimeZone.getTimeZone("GMT")
    clear()
    set(Calendar.HOUR_OF_DAY, hours)
    set(Calendar.MINUTE, minutes)
    return timeInMillis
}

/**
 * Extension function gets the time today at start of day in the local time zone
 * used by AutoStartStopHelper()
 */
fun GregorianCalendar.millisAtStartOfDay(): Long {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    return timeInMillis
}

/**
 * Extension function gets the millis from start of a day chopping out
 * the rest of the date
 */
fun Long.elapsedMillisModulusDay(): Long {

    val hours = this / 1000L / 60L / 60L
    val minutes = (this / 1000L / 60L) - (hours * 60L)
    val dayHours = hours % 24

    return (dayHours * 60L + minutes) * 60L * 1000L
}