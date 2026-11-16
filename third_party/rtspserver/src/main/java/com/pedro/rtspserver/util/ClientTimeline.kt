package com.pedro.rtspserver.util

/**
 * Turns encoder timestamps into one client's RTP timeline, which must start at 0 (some
 * players need it) and never go backwards.
 *
 * Upstream took whichever frame came first as the origin. Audio arrives in bursts up to
 * ~400 ms late, so after a video origin the next audio frames had negative times; written
 * into the 32-bit RTP field they wrapped to hours ahead, and players that pace video by the
 * audio clock (Media3) froze with no sound. The origin is now the first video frame when the
 * stream has video, and frames older than the origin are dropped instead of sent.
 */
internal class ClientTimeline(private val hasVideo: Boolean) {
    private var originUs: Long? = null

    /** Microseconds since the origin, or null when the frame must not be sent. */
    @Synchronized
    fun offset(presentationTimeUs: Long, isVideo: Boolean): Long? {
        val origin = originUs ?: if (isVideo || !hasVideo) presentationTimeUs.also { originUs = it } else return null
        return (presentationTimeUs - origin).takeIf { it >= 0 }
    }

    @Synchronized
    fun reset() {
        originUs = null
    }
}
