package com.fragmind.camnode.feature.viewer

import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.res.stringResource
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW

/** Attaches a surface to the viewer's connection without owning its transport. */
@OptIn(UnstableApi::class)
@Composable
internal fun RtspPlayer(
    url: String,
    muted: Boolean,
    playback: PlaybackSessions<RtspConnection>,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    rotationDegrees: Int = 0,
    /** Set when the caller scales or moves the video; a SurfaceView ignores those transforms. */
    transformable: Boolean = false,
    /** Set to capture photos: the video then draws into a TextureView this grabber can copy. */
    grabber: FrameGrabber? = null,
) {
    var lease by remember(playback, url) { mutableStateOf<PlaybackSessions.Lease<RtspConnection>?>(null) }
    DisposableEffect(playback, url) {
        val attached = playback.acquire(url, muted)
        lease = attached
        onDispose { attached.close() }
    }
    LaunchedEffect(lease, muted) { lease?.setMuted(muted) }
    // Only a screen that asked for sound hears about it: muted tiles never turn audio on.
    if (!muted) {
        val context = LocalContext.current
        val notice = stringResource(R.string.viewer_audio_unavailable)
        LaunchedEffect(lease) {
            lease?.connection?.audioBlocked?.first { it } ?: return@LaunchedEffect
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
        }
    }

    val connection = lease?.connection
    val phase = if (connection == null) PlaybackPhase.Connecting else connection.phase.collectAsState().value
    if (grabber != null) SideEffect { grabber.ready = phase == PlaybackPhase.Playing }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (grabber != null) {
            CapturableVideo(connection?.player, grabber, Modifier.rotated(rotationDegrees))
        } else {
            // TextureView only when needed: it costs an extra GPU copy per frame on old phones.
            ContentFrame(
                player = connection?.player,
                modifier = Modifier.rotated(rotationDegrees),
                surfaceType = if (rotationDegrees != 0 || transformable) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
                contentScale = contentScale,
            )
        }
        if (phase != PlaybackPhase.Playing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Text(
                    stringResource(if (phase == PlaybackPhase.Connecting) R.string.viewer_connecting else R.string.viewer_reconnecting),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * The video fitted to the box in a TextureView that [grabber] can copy. ContentFrame keeps
 * its view private, so this replaces it where photos are taken.
 */
@Composable
private fun CapturableVideo(player: ExoPlayer?, grabber: FrameGrabber, modifier: Modifier) {
    var size by remember(player) { mutableStateOf(player?.videoSize ?: VideoSize.UNKNOWN) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                size = videoSize
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    SideEffect {
        grabber.videoWidth = size.width
        grabber.videoHeight = size.height
    }
    val ratio = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else null
    var view by remember { mutableStateOf<TextureView?>(null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context -> TextureView(context) },
            update = { created ->
                view = created
                grabber.view = created
            },
            onRelease = { released -> if (grabber.view === released) grabber.view = null },
            modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
        )
    }
    // Attach after composition settles, as Media3's own surfaces do; attaching while the view
    // is created let the screen being left take the player back and froze this picture.
    view?.let { texture ->
        DisposableEffect(texture, player) {
            player?.setVideoTextureView(texture)
            onDispose { player?.clearVideoTextureView(texture) }
        }
    }
}

/**
 * Fills the available box with the picture turned by [degrees]. For a quarter turn the
 * content is laid out with width and height swapped and then rotated, so it fits the box
 * exactly instead of being squeezed into the unrotated frame.
 */
private fun Modifier.rotated(degrees: Int): Modifier {
    if (degrees == 0) return this
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val quarterTurn = degrees % 180 != 0
        val placeable = measurable.measure(if (quarterTurn) Constraints.fixed(height, width) else Constraints.fixed(width, height))
        layout(width, height) { placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2) }
    }.graphicsLayer { rotationZ = degrees.toFloat() }
}
