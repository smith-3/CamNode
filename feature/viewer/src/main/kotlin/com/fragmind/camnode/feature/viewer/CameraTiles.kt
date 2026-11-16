package com.fragmind.camnode.feature.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons

internal enum class CameraPreviewStyle { MOSAIC, MONITOR, THUMBNAIL }

@Composable
internal fun CameraCard(camera: CameraTile, playback: PlaybackSessions<RtspConnection>, live: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            CameraVideo(camera, playback, live, compact = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.35f to Color.Transparent)))
            Box(Modifier.align(Alignment.TopStart).padding(10.dp)) { HealthPill(camera.health) }
            Icon(CamNodeIcons.Fullscreen, stringResource(R.string.viewer_open, camera.name), tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(camera.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HealthChips(camera.health, onVideo = false)
        }
    }
}

@Composable
internal fun CameraVideoTile(
    camera: CameraTile,
    playback: PlaybackSessions<RtspConnection>,
    live: Boolean,
    style: CameraPreviewStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        CameraVideo(camera, playback, live, compact = style != CameraPreviewStyle.MONITOR)
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.6f),
                0.3f to Color.Transparent,
                0.6f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.85f),
            )),
        )
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthPill(camera.health, compact = style != CameraPreviewStyle.MONITOR)
            if (style == CameraPreviewStyle.MOSAIC) {
                (camera.health as? CameraHealth.Online)?.let { BatteryChip(it.status, onVideo = true) }
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(if (style == CameraPreviewStyle.MONITOR) 16.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    camera.name,
                    color = Color.White,
                    style = when (style) {
                        CameraPreviewStyle.MONITOR -> MaterialTheme.typography.titleMedium
                        CameraPreviewStyle.MOSAIC -> MaterialTheme.typography.labelLarge
                        CameraPreviewStyle.THUMBNAIL -> MaterialTheme.typography.labelMedium
                    },
                    maxLines = if (style == CameraPreviewStyle.MOSAIC) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (style == CameraPreviewStyle.THUMBNAIL) CamNodeIcons.ChevronRight else CamNodeIcons.Fullscreen,
                    contentDescription = stringResource(if (style == CameraPreviewStyle.THUMBNAIL) R.string.viewer_highlight else R.string.viewer_open, camera.name),
                    tint = Color.White,
                )
            }
            if (style == CameraPreviewStyle.MONITOR) HealthChips(camera.health, onVideo = true)
        }
    }
}

@Composable
private fun CameraVideo(camera: CameraTile, playback: PlaybackSessions<RtspConnection>, live: Boolean, compact: Boolean) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        val url = camera.rtspUrl
        when {
            url != null && live -> RtspPlayer(url, muted = true, playback = playback, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, rotationDegrees = camera.displayRotation)
            url != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(CamNodeIcons.Videocam, null, tint = Color.White)
                if (!compact) Text(stringResource(R.string.viewer_tap_live), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            else -> NoSignal(camera.health, Modifier.padding(12.dp), compact = compact)
        }
    }
}
