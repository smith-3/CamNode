package com.fragmind.camnode.feature.viewer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class PlaybackPhase { Connecting, Playing, Reconnecting }

@OptIn(UnstableApi::class)
internal class RtspConnection(
    context: Context,
    private val url: String,
    private val scope: CoroutineScope,
) : PlaybackConnection {
    private val _phase = MutableStateFlow(PlaybackPhase.Connecting)
    val phase = _phase.asStateFlow()
    private var running = false
    private var retry: Job? = null
    private var watchdog: Job? = null
    private var audible = false

    /**
     * Set when this camera's audio held the video still: the player then keeps video only.
     * ExoPlayer paces video by the audio track, so audio that stops arriving freezes the picture.
     */
    private val _audioBlocked = MutableStateFlow(false)
    val audioBlocked = _audioBlocked.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    ).setLoadControl(
        DefaultLoadControl.Builder().setBufferDurationsMs(500, 2_000, 250, 250).build(),
    ).build().apply {
        volume = 0f
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> _phase.value = PlaybackPhase.Playing
                    Player.STATE_ENDED -> reconnect()
                }
            }

            override fun onPlayerError(error: PlaybackException) = reconnect()
        })
    }

    override fun start() {
        if (running) return
        running = true
        prepare()
        watchdog = scope.launch { watchForAudioStalls() }
    }

    override fun stop() {
        running = false
        retry?.cancel()
        retry = null
        watchdog?.cancel()
        watchdog = null
        player.stop()
        _phase.value = PlaybackPhase.Connecting
    }

    override fun setAudible(audible: Boolean) {
        this.audible = audible
        applyAudio()
    }

    override fun release() {
        running = false
        retry?.cancel()
        watchdog?.cancel()
        player.release()
    }

    /**
     * Muting only turns the volume down: Media3 can't add an RTSP track mid-session (it set the
     * audio up again but never delivered its samples), so the audio track stays selected from
     * the start and is dropped only when it stalls the picture.
     */
    private fun applyAudio() {
        val blocked = _audioBlocked.value
        player.volume = if (audible && !blocked) 1f else 0f
        if (blocked) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
    }

    /** Drops the audio track when frames stop being drawn while it is on. */
    private suspend fun watchForAudioStalls() {
        val detector = StallDetector(STALL_CHECKS)
        while (running) {
            delay(STALL_CHECK_MILLIS)
            val audioOn = C.TRACK_TYPE_AUDIO !in player.trackSelectionParameters.disabledTrackTypes
            val rendered = player.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount.toLong() }
            if (detector.stalled(rendered, audioOn && player.playbackState != Player.STATE_IDLE) && !_audioBlocked.value) {
                _audioBlocked.value = true
                applyAudio()
            }
        }
    }

    private fun prepare() {
        _phase.value = PlaybackPhase.Connecting
        player.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
        player.playWhenReady = true
        player.prepare()
    }

    private fun reconnect() {
        if (!running || retry?.isActive == true) return
        _phase.value = PlaybackPhase.Reconnecting
        retry = scope.launch {
            delay(3_000)
            retry = null
            prepare()
        }
    }
}

/**
 * Notices video that stopped being drawn while audio is on: [checks] samples in a row with
 * the same rendered-frame count. Samples taken with audio off, or before the first frame,
 * start the count over.
 */
internal class StallDetector(private val checks: Int) {
    private var last: Long? = null
    private var still = 0

    fun stalled(renderedFrames: Long?, audioOn: Boolean): Boolean {
        val previous = last
        last = renderedFrames
        still = if (audioOn && renderedFrames != null && renderedFrames > 0 && renderedFrames == previous) still + 1 else 0
        return still >= checks
    }
}

private const val STALL_CHECK_MILLIS = 2_000L

/** Two checks: about four seconds without a new frame. */
private const val STALL_CHECKS = 2

