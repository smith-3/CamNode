package com.fragmind.camnode.feature.viewer

import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fragmind.camnode.core.designsystem.ButtonEmphasis
import com.fragmind.camnode.core.designsystem.CamNodeButton
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.EmptyState
import com.fragmind.camnode.core.model.CameraLayout
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.network.LocalNetworkPermission

@Composable
fun ViewerRoute(onOpenCamera: (NodeId) -> Unit, onInvite: () -> Unit, viewModel: ViewerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        LocalNetworkPermission.runtimePermissions.takeIf { it.isNotEmpty() }?.let { localNetwork.launch(it.toTypedArray()) }
    }
    ViewerScreen(
        state, viewModel.playback, onOpenCamera, onInvite,
        onToggleLiveGrid = { viewModel.setLiveGrid(!state.liveGrid) },
        onChangeLayout = viewModel::setCameraLayout,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerScreen(
    state: ViewerUiState,
    playback: PlaybackSessions<RtspConnection>,
    onOpenCamera: (NodeId) -> Unit,
    onInvite: () -> Unit,
    onToggleLiveGrid: () -> Unit,
    onChangeLayout: (CameraLayout) -> Unit,
) {
    var focusedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.viewer_title), style = MaterialTheme.typography.headlineSmall)
                        if (state.cameras.isNotEmpty()) {
                            Text(
                                stringResource(R.string.viewer_subtitle, state.householdName, state.online, state.cameras.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (state.cameras.isNotEmpty()) {
                        CameraLayoutPicker(state.cameraLayout, onChangeLayout)
                        IconButton(onClick = onToggleLiveGrid) {
                            Icon(
                                if (state.liveGrid) CamNodeIcons.Videocam else CamNodeIcons.VideocamOff,
                                contentDescription = stringResource(if (state.liveGrid) R.string.viewer_pause_previews else R.string.viewer_resume_previews),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.canView -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = CamNodeIcons.Lock,
                    title = stringResource(R.string.viewer_camera_only_title),
                    body = stringResource(R.string.viewer_camera_only_body),
                )
            }
            state.cameras.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = CamNodeIcons.Videocam,
                    title = stringResource(R.string.viewer_empty_title),
                    body = stringResource(R.string.viewer_empty_body),
                    // Inviting hands out the household secret, so only administrators get the shortcut.
                    action = if (state.canManage) ({ CamNodeButton(stringResource(R.string.viewer_invite), onInvite, icon = CamNodeIcons.QrCode, emphasis = ButtonEmphasis.Secondary) }) else null,
                )
            }
            state.cameraLayout == CameraLayout.MONITOR -> {
                val selected = state.cameras.firstOrNull { it.id.value == focusedCameraId }
                    ?: state.cameras.firstOrNull { (it.health as? CameraHealth.Online)?.camera != null }
                    ?: state.cameras.first()
                CameraMonitor(
                    state.cameras, selected, playback, state.liveGrid,
                    onSelect = { focusedCameraId = it.value },
                    onOpen = onOpenCamera,
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                val mosaic = state.cameraLayout == CameraLayout.MOSAIC
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (mosaic) 160.dp else 280.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (mosaic) 8.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (mosaic) 8.dp else 14.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    items(state.cameras, key = { it.id.value }) { camera ->
                        if (mosaic) {
                            CameraVideoTile(camera, playback, state.liveGrid, CameraPreviewStyle.MOSAIC, Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                                onOpenCamera(camera.id)
                            }
                        } else {
                            CameraCard(camera, playback, state.liveGrid) { onOpenCamera(camera.id) }
                        }
                    }
                }
            }
        }
    }
}
