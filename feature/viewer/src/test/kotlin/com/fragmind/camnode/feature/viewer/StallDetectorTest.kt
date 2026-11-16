package com.fragmind.camnode.feature.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {

    @Test
    fun framesThatStopWhileAudioIsOnAreAStall() {
        val detector = StallDetector(checks = 2)
        assertFalse(detector.stalled(10, audioOn = true))
        assertFalse(detector.stalled(10, audioOn = true))
        assertTrue(detector.stalled(10, audioOn = true))
    }

    @Test
    fun movingFramesNeverStall() {
        val detector = StallDetector(checks = 2)
        listOf(10L, 30L, 50L, 70L).forEach { assertFalse(detector.stalled(it, audioOn = true)) }
    }

    @Test
    fun withAudioOffAStillPictureIsNotBlamedOnAudio() {
        val detector = StallDetector(checks = 2)
        repeat(4) { assertFalse(detector.stalled(10, audioOn = false)) }
    }

    @Test
    fun beforeTheFirstFrameNothingCounts() {
        val detector = StallDetector(checks = 2)
        repeat(4) { assertFalse(detector.stalled(0, audioOn = true)) }
        repeat(4) { assertFalse(detector.stalled(null, audioOn = true)) }
    }

    @Test
    fun turningAudioOffResetsTheCount() {
        val detector = StallDetector(checks = 2)
        detector.stalled(10, audioOn = true)
        detector.stalled(10, audioOn = true)
        assertFalse(detector.stalled(10, audioOn = false))
        assertFalse(detector.stalled(10, audioOn = true))
    }
}
