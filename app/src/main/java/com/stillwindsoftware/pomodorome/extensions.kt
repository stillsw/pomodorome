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
 * If it's a local time, the offset from gmt has to be added as well
 */
fun Long.elapsedMillisModulusDay(isLocalTime: Boolean): Long {

    val useTime = this + if (isLocalTime) TimeZone.getDefault().getOffset(this) else 0

    val hours = useTime / 1000L / 60L / 60L
    val minutes = (useTime / 1000L / 60L) - (hours * 60L)
    val dayHours = hours % 24

    return (dayHours * 60L + minutes) * 60L * 1000L
}

fun Long.daysHoursMinsToString(): String {
    val oneMin = 1000L * 60
    val oneHour = oneMin * 60
    val days = this / (24 * oneHour)
    val lastDay = this - (days * 24 * oneHour)
    return "whole24Hours=$days hours=${lastDay / oneHour } mins=${(lastDay % oneHour) / oneMin}"
}