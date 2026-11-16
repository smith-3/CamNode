package com.fragmind.camnode.feature.viewer

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
) {
    var lease by remember(playback, url) { mutableStateOf<PlaybackSessions.Lease<RtspConnection>?>(null) }
    DisposableEffect(playback, url) {
        val attached = playback.acquire(url, muted)
        lease = attached
        onDispose { attached.close() }
    }
    LaunchedEffect(lease, muted) { lease?.setMuted(muted) }

    val connection = lease?.connection
    val phase = if (connection == null) PlaybackPhase.Connecting else connection.phase.collectAsState().value
    Box(modifier, contentAlignment = Alignment.Center) {
        // TextureView only when needed: it costs an extra GPU copy per frame on old phones.
        ContentFrame(
            player = connection?.player,
            modifier = Modifier.rotated(rotationDegrees),
            surfaceType = if (rotationDegrees != 0 || transformable) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
            contentScale = contentScale,
        )
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
