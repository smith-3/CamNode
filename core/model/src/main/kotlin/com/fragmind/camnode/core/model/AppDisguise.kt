package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable

/**
 * How CamNode presents itself while the PIN lock is on: its launcher icon and name, and
 * a working app that opens until the PIN is entered the way that app naturally takes it.
 */
@Serializable
enum class AppDisguise {
    /** Real name and icon; a plain PIN pad guards the app. */
    NONE,
    /** Type the PIN and press "=". */
    CALCULATOR,
    /** Set an alarm at HH:MM equal to the PIN. */
    CLOCK,
    /** Add an event on the date DD/MM equal to the PIN. */
    CALENDAR,
    /** Save a note whose text is exactly the PIN. */
    NOTES;

    /** How many launcher icons this disguise offers; NONE keeps CamNode's own. */
    val iconStyles: Int get() = if (this == NONE) 1 else 3

    /** Whether [pin] can be entered through this disguise's own controls. */
    fun accepts(pin: String): Boolean {
        if (!AppPin.isWellFormed(pin)) return false
        val first = pin.substring(0, 2).toInt()
        val second = pin.substring(2, 4).toInt()
        return when (this) {
            NONE, CALCULATOR, NOTES -> true
            CLOCK -> first in 0..23 && second in 0..59
            CALENDAR -> second in 1..12 && first in 1..daysInMonth(second)
        }
    }

    private fun daysInMonth(month: Int): Int = when (month) {
        2 -> 29
        4, 6, 9, 11 -> 30
        else -> 31
    }
}

object AppPin {
    const val LENGTH = 4

    fun isWellFormed(pin: String): Boolean = pin.length == LENGTH && pin.all(Char::isDigit)

    /** The PIN a clock alarm at [hour]:[minute] stands for. */
    fun fromTime(hour: Int, minute: Int): String = "%02d%02d".format(hour, minute)

    /** The PIN a calendar date [day]/[month] stands for. */
    fun fromDate(day: Int, month: Int): String = "%02d%02d".format(day, month)
}
