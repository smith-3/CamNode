package com.fragmind.camnode.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A live camera screen laid out like a camera app: the picture fills the screen, the
 * status sits on top and the capture controls at the bottom, each over a fade so white
 * text stays readable on any scene. Shared by the viewer and the Broadcast tab.
 */
@Composable
fun CameraConsole(
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    video: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(Color.Black)) {
        video()
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))) {
            Box(Modifier.safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp)) { top() }
        }
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))) {
            Box(Modifier.safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { bottom() }
        }
    }
}

/** A secondary control over video: torch, picture mode, rotation, sound. */
@Composable
fun ConsoleIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White),
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp)) }
}

/**
 * Sensor zoom steps (0.6 · 1x · 2x · ...) like a phone camera. The active step shows the
 * exact zoom, so a camera left at 2.5x reads "2.5x" on the 2x step.
 */
@Composable
fun ZoomStops(zoom: Float, min: Float, max: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    val stops = zoomStops(min, max)
    if (stops.size < 2) return
    val active = activeZoomStop(zoom, stops)
    Row(
        modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { stop ->
            val selected = stop == active
            val description = stringResource(R.string.camera_zoom_step, zoomLabel(stop))
            Box(
                Modifier
                    .size(if (selected) 42.dp else 36.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
                    .clickable(role = Role.Button) { onSelect(stop) }
                    .semantics {
                        contentDescription = description
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected) zoomLabel(zoom) else zoomLabel(stop).removeSuffix("x"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/**
 * The capture row: lens switch on the left, shutter in the middle, last photo on the right.
 * A missing action keeps its slot so the shutter stays centered.
 */
@Composable
fun ShutterBar(
    shutter: ShutterAction?,
    lastPhoto: LastPhoto?,
    switchLens: ConsoleAction?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(SIDE_SLOT), contentAlignment = Alignment.Center) {
            switchLens?.let { ConsoleIconButton(CamNodeIcons.SwitchCamera, it.description, it.onClick, Modifier.size(52.dp)) }
        }
        Box(Modifier.size(SHUTTER), contentAlignment = Alignment.Center) {
            shutter?.let { action ->
                Box(
                    Modifier
                        .size(SHUTTER)
                        .alpha(if (action.enabled) 1f else 0.4f)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
                        .semantics { contentDescription = action.description },
                )
            }
        }
        Box(Modifier.size(SIDE_SLOT), contentAlignment = Alignment.Center) {
            lastPhoto?.let { photo ->
                Image(
                    photo.thumbnail,
                    contentDescription = photo.description,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .border(1.5.dp, Color.White, MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClick = photo.onOpen),
                )
            }
        }
    }
}

class ConsoleAction(val description: String, val onClick: () -> Unit)

/** [enabled] is false until there is a picture to capture. */
class ShutterAction(val description: String, val enabled: Boolean, val onClick: () -> Unit)

class LastPhoto(val thumbnail: ImageBitmap, val description: String, val onOpen: () -> Unit)

private val SHUTTER = 76.dp
private val SIDE_SLOT = 56.dp
