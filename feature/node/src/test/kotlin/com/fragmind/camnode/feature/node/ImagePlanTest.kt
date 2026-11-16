package com.fragmind.camnode.feature.node

import com.fragmind.camnode.core.model.ImageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePlanTest {
    @Test
    fun colorAddsNoFiltersSoItCostsNothingExtra() {
        val plan = ImagePlan.forMode(ImageMode.COLOR)
        assertFalse(plan.greyscale)
        assertEquals(1f, plan.gamma)
        assertEquals(1f, plan.contrast)
        assertFalse(plan.lowLight)
    }

    @Test
    fun monochromeOnlyDropsColor() {
        assertEquals(ImagePlan(greyscale = true), ImagePlan.forMode(ImageMode.MONOCHROME))
    }

    @Test
    fun nightIsBrighterMonochromeWithLongerExposures() {
        val plan = ImagePlan.forMode(ImageMode.NIGHT)
        assertTrue(plan.greyscale && plan.lowLight)
        assertTrue(plan.gamma < 1f && plan.contrast > 1f)
    }

    @Test
    fun lowLightPicksTheLowestFloorWithinTheStreamRate() {
        val sensor = listOf(15..15, 7..20, 20..20, 10..30, 5..15)
        assertEquals(5..15, lowLightFpsRange(sensor, streamFps = 20))
        assertEquals(7..20, lowLightFpsRange(listOf(15..15, 7..20, 20..20), streamFps = 20))
        assertNull(lowLightFpsRange(listOf(24..30), streamFps = 20))
    }

    @Test
    fun idlePicksTheSlowestSensorRange() {
        val sensor = listOf(15..15, 7..20, 20..20, 7..24, 24..24, 7..30, 30..30)
        assertEquals(15..15, idleFpsRange(sensor))
        assertNull(idleFpsRange(emptyList()))
    }

    @Test
    fun theFilterPartIgnoresSensorSettings() {
        val night = ImagePlan.forMode(ImageMode.NIGHT)
        assertEquals(night.glFilters, night.copy(lowLight = false).glFilters)
        assertEquals(GlFilters(greyscale = true, gamma = 1f, contrast = 1f), ImagePlan.forMode(ImageMode.MONOCHROME).glFilters)
        assertNotEquals(ImagePlan.forMode(ImageMode.MONOCHROME).glFilters, night.glFilters)
    }
}
