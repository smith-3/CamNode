package com.pedro.rtspserver.server

import com.pedro.rtspserver.util.ClientTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientTimelineTest {

    @Test
    fun theFirstVideoFrameIsTimeZero() {
        val timeline = ClientTimeline(hasVideo = true)
        assertEquals(0L, timeline.offset(5_000_000, isVideo = true))
        assertEquals(50_000L, timeline.offset(5_050_000, isVideo = true))
    }

    /** The bug: a burst of audio older than the first video frame went out with negative times. */
    @Test
    fun lateAudioFromBeforeTheOriginIsDroppedNotSentNegative() {
        val timeline = ClientTimeline(hasVideo = true)
        timeline.offset(5_000_000, isVideo = true)
        assertNull(timeline.offset(4_700_000, isVideo = false))
        assertEquals(32_000L, timeline.offset(5_032_000, isVideo = false))
    }

    @Test
    fun audioWaitsForVideoToSetTheOrigin() {
        val timeline = ClientTimeline(hasVideo = true)
        assertNull(timeline.offset(4_900_000, isVideo = false))
        assertEquals(0L, timeline.offset(5_000_000, isVideo = true))
    }

    @Test
    fun anAudioOnlyStreamStartsOnItsFirstAudioFrame() {
        val timeline = ClientTimeline(hasVideo = false)
        assertEquals(0L, timeline.offset(4_900_000, isVideo = false))
        assertEquals(32_000L, timeline.offset(4_932_000, isVideo = false))
    }

    @Test
    fun resetStartsANewOrigin() {
        val timeline = ClientTimeline(hasVideo = true)
        timeline.offset(5_000_000, isVideo = true)
        timeline.reset()
        assertEquals(0L, timeline.offset(9_000_000, isVideo = true))
    }
}
