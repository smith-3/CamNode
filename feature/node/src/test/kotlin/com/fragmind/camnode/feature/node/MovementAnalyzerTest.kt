package com.fragmind.camnode.feature.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MovementAnalyzerTest {
    private val g = 9.81f
    private val analyzer = MovementAnalyzer(armDelayMillis = 1_000, cooldownMillis = 10_000)

    /** Feeds samples every 50 ms of a phone tilted by [degrees] around X, returns how many alerts fired. */
    private fun feed(fromMillis: Long, toMillis: Long, degrees: Double = 0.0): Int {
        val radians = Math.toRadians(degrees)
        return (fromMillis until toMillis step 50).count { t ->
            analyzer.onSample(0f, (g * sin(radians)).toFloat(), (g * cos(radians)).toFloat(), t)
        }
    }

    @Test
    fun aPhoneLyingStillNeverAlerts() {
        assertEquals(0, feed(0, 60_000))
    }

    @Test
    fun pickingItUpAlertsOnceThenRespectsTheCooldown() {
        feed(0, 2_000)
        assertEquals(1, feed(2_000, 5_000, degrees = 40.0) + feed(5_000, 8_000, degrees = 0.0))
        assertEquals(1, feed(13_000, 16_000, degrees = 40.0))
    }

    @Test
    fun placingThePhoneDuringTheArmDelayIsNotAnAlert() {
        assertEquals(0, feed(0, 500, degrees = 30.0) + feed(500, 20_000, degrees = 60.0))
    }

    @Test
    fun aSuddenJoltAlertsEvenWithoutTilt() {
        feed(0, 2_000)
        assertFalse(analyzer.onSample(0f, 0f, g, 2_050))
        assertEquals(true, analyzer.onSample(4f, 0f, g + 4f, 2_100))
    }
}
