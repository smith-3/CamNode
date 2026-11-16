package com.fragmind.camnode.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomTest {

    @Test
    fun wholeStepsStopAtFourX() {
        assertEquals(listOf(1f, 2f, 3f, 4f), zoomStops(min = 1f, max = 10f))
    }

    @Test
    fun includesUltraWideBelowOneX() {
        assertEquals(listOf(0.6f, 1f, 2f), zoomStops(min = 0.6f, max = 2.5f))
    }

    @Test
    fun aLensBelowTwoXOffersItsMaximum() {
        assertEquals(listOf(1f, 1.5f), zoomStops(min = 1f, max = 1.5f))
    }

    @Test
    fun aFixedLensHasASingleStep() {
        assertEquals(listOf(1f), zoomStops(min = 1f, max = 1f))
    }

    @Test
    fun aZoomBetweenStepsLightsUpTheStepBelow() {
        val stops = zoomStops(min = 1f, max = 8f)
        assertEquals(2f, activeZoomStop(2.5f, stops))
        assertEquals(2f, activeZoomStop(1.98f, stops))
        assertEquals(1f, activeZoomStop(1f, stops))
    }

    @Test
    fun labelsWholeAndFractionalZoom() {
        assertEquals("2x", zoomLabel(2f))
        assertEquals("1.5x", zoomLabel(1.5f))
        assertEquals("2x", zoomLabel(1.999f))
        assertEquals("0.6x", zoomLabel(0.6f))
    }
}
