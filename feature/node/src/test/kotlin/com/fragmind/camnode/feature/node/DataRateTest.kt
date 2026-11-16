package com.fragmind.camnode.feature.node

import org.junit.Assert.assertEquals
import org.junit.Test

class DataRateTest {
    @Test
    fun belowAMegabitShowsKilobits() {
        assertEquals("850 kbps", formatDataRate(849_600))
        assertEquals("0 kbps", formatDataRate(0))
    }

    @Test
    fun fromAMegabitShowsOneDecimal() {
        assertEquals("1.0 Mbps", formatDataRate(1_000_000))
        assertEquals("2.5 Mbps", formatDataRate(2_540_000))
    }
}
