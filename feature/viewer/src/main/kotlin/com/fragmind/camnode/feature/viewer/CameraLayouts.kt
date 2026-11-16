package com.fragmind.camnode.feature.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.model.CameraLayout
import com.fragmind.camnode.core.model.NodeId

@Composable
internal fun CameraLayoutPicker(layout: CameraLayout, onChange: (CameraLayout) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(layout.icon, contentDescription = stringResource(R.string.viewer_change_layout))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            CameraLayout.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Icon(option.icon, contentDescription = null) },
                    trailingIcon = { if (layout == option) Icon(CamNodeIcons.Check, contentDescription = stringResource(R.string.viewer_selected)) },
                    onClick = { expanded = false; onChange(option) },
                )
            }
        }
    }
}

@Composable
internal fun CameraMonitor(
    cameras: List<CameraTile>,
    selected: CameraTile,
    playback: PlaybackSessions<RtspConnection>,
    live: Boolean,
    onSelect: (NodeId) -> Unit,
    onOpen: (NodeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val others = cameras.filter { it.id != selected.id }
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp)) {
        if (maxWidth > maxHeight) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CameraVideoTile(selected, playback, live, CameraPreviewStyle.MONITOR, Modifier.weight(1f).fillMaxHeight()) { onOpen(selected.id) }
                if (others.isNotEmpty()) {
                    LazyColumn(Modifier.width(168.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(others, key = { it.id.value }) { camera ->
                            CameraVideoTile(camera, playback, live, CameraPreviewStyle.THUMBNAIL, Modifier.aspectRatio(16f / 9f)) { onSelect(camera.id) }
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CameraVideoTile(selected, playback, live, CameraPreviewStyle.MONITOR, Modifier.weight(1f)) { onOpen(selected.id) }
                if (others.isNotEmpty()) {
                    LazyRow(Modifier.height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(others, key = { it.id.value }) { camera ->
                            CameraVideoTile(camera, playback, live, CameraPreviewStyle.THUMBNAIL, Modifier.width(160.dp).fillMaxHeight()) { onSelect(camera.id) }
                        }
                    }
                }
            }
        }
    }
}

private val CameraLayout.label: String
    @Composable get() = stringResource(
        when (this) {
            CameraLayout.CARDS -> R.string.viewer_layout_cards
            CameraLayout.MOSAIC -> R.string.viewer_layout_mosaic
            CameraLayout.MONITOR -> R.string.viewer_layout_monitor
        },
    )

private val CameraLayout.icon: ImageVector
    @Composable get() = when (this) {
        CameraLayout.CARDS -> CamNodeIcons.Cards
        CameraLayout.MOSAIC -> CamNodeIcons.Grid
        CameraLayout.MONITOR -> CamNodeIcons.Monitor
    }
