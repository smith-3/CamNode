package com.fragmind.camnode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDisguiseTest {
    @Test
    fun anyFourDigitsWorkForCalculatorNotesAndPinPad() {
        listOf(AppDisguise.NONE, AppDisguise.CALCULATOR, AppDisguise.NOTES).forEach { disguise ->
            assertTrue(disguise.accepts("9876"))
            assertFalse(disguise.accepts("123"))
            assertFalse(disguise.accepts("12a4"))
        }
    }

    @Test
    fun clockNeedsARealTime() {
        assertTrue(AppDisguise.CLOCK.accepts("0730"))
        assertTrue(AppDisguise.CLOCK.accepts("2359"))
        assertFalse(AppDisguise.CLOCK.accepts("2400"))
        assertFalse(AppDisguise.CLOCK.accepts("1260"))
        assertEquals("0730", AppPin.fromTime(7, 30))
    }

    @Test
    fun calendarNeedsARealDayAndMonth() {
        assertTrue(AppDisguise.CALENDAR.accepts("1503"))
        assertTrue(AppDisguise.CALENDAR.accepts("2902"))
        assertFalse(AppDisguise.CALENDAR.accepts("3102"))
        assertFalse(AppDisguise.CALENDAR.accepts("3104"))
        assertFalse(AppDisguise.CALENDAR.accepts("0013"))
        assertEquals("0508", AppPin.fromDate(5, 8))
    }
}
