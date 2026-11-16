package com.fragmind.camnode.feature.viewer

import android.content.Context
import androidx.annotation.OptIn
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
    }

    override fun stop() {
        running = false
        retry?.cancel()
        retry = null
        player.stop()
        _phase.value = PlaybackPhase.Connecting
    }

    override fun setAudible(audible: Boolean) {
        player.volume = if (audible) 1f else 0f
    }

    override fun release() {
        running = false
        retry?.cancel()
        player.release()
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
