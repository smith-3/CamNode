package com.fragmind.camnode.feature.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fragmind.camnode.core.designsystem.CamNodeButton
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.MetricChip
import com.fragmind.camnode.core.model.CameraFacing
import com.fragmind.camnode.core.model.ImageMode
import com.fragmind.camnode.core.model.NodeId

/** What the console can ask of a camera, keyed by camera so one set serves every page. */
internal class CameraActions(
    val switchLens: (NodeId) -> Unit,
    val rotate: (NodeId) -> Unit,
    val zoom: (NodeId, Float) -> Unit,
    val imageMode: (NodeId, ImageMode) -> Unit,
    val toggleTorch: (NodeId) -> Unit,
    val setPaused: (NodeId, Boolean) -> Unit,
)

@Composable
fun CameraDetailRoute(cameraId: NodeId, onBack: () -> Unit, viewModel: ViewerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CameraDetailScreen(
        cameras = state.cameras,
        initialCameraId = cameraId,
        playback = viewModel.playback,
        loading = state.loading,
        canControl = state.canControl,
        onBack = onBack,
        actions = remember(viewModel) {
            CameraActions(
                switchLens = viewModel::switchLens,
                rotate = viewModel::rotate,
                zoom = viewModel::zoom,
                imageMode = viewModel::setImageMode,
                toggleTorch = viewModel::toggleTorch,
                setPaused = viewModel::setPaused,
            )
        },
    )
}

/**
 * Full-bleed live view; swipe left or right to move between cameras. Swiping is off
 * while the picture is zoomed on screen, so panning never jumps to another camera.
 */
@Composable
internal fun CameraDetailScreen(
    cameras: List<CameraTile>,
    initialCameraId: NodeId,
    playback: PlaybackSessions<RtspConnection>,
    loading: Boolean,
    canControl: Boolean,
    onBack: () -> Unit,
    actions: CameraActions,
) {
    val initialPage = cameras.indexOfFirst { it.id == initialCameraId }
    if (initialPage < 0) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text(stringResource(R.string.viewer_camera_gone), color = Color.White)
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding()) {
                Icon(CamNodeIcons.Back, contentDescription = stringResource(R.string.viewer_back), tint = Color.White)
            }
        }
        return
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { cameras.size }
    var muted by rememberSaveable { mutableStateOf(false) }
    var zoomedOnScreen by remember { mutableStateOf(false) }
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !zoomedOnScreen,
        key = { page -> cameras[page].id.value },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) { page ->
        CameraPage(
            camera = cameras[page],
            position = "${page + 1} / ${cameras.size}",
            playback = playback,
            muted = muted,
            onToggleMuted = { muted = !muted },
            onBack = onBack,
            canControl = canControl,
            actions = actions,
            onScreenZoomChanged = { zoomed -> if (page == pagerState.currentPage) zoomedOnScreen = zoomed },
        )
    }
}

@Composable
private fun CameraPage(
    camera: CameraTile,
    position: String,
    playback: PlaybackSessions<RtspConnection>,
    muted: Boolean,
    onToggleMuted: () -> Unit,
    onBack: () -> Unit,
    canControl: Boolean,
    actions: CameraActions,
    onScreenZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Viewers without the control permission keep only what affects their own screen.
    val streaming = (camera.health as? CameraHealth.Online)?.camera
    val controllable = streaming?.takeIf { canControl }
    Box(Modifier.fillMaxSize()) {
        val url = camera.rtspUrl
        when {
            url != null -> RtspPlayer(
                url,
                muted = muted,
                playback = playback,
                contentScale = ContentScale.Fit,
                rotationDegrees = camera.displayRotation,
                transformable = true,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                            onScreenZoomChanged(false)
                        })
                    }
                    .pointerInput(Unit) {
                        // Two fingers zoom; one finger pans only once zoomed. Otherwise the drag is
                        // left unconsumed so the pager can switch cameras.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2 || scale > 1f) {
                                    scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_SCREEN_ZOOM)
                                    val pan = event.calculatePan()
                                    val limitX = size.width * (scale - 1) / 2
                                    val limitY = size.height * (scale - 1) / 2
                                    offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
                                    onScreenZoomChanged(scale > 1f)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            streaming?.paused == true -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NoSignal(camera.health)
                if (canControl) CamNodeButton(stringResource(R.string.viewer_resume_camera), { actions.setPaused(camera.id, false) }, icon = CamNodeIcons.VideocamFilled)
            }
            else -> NoSignal(camera.health, Modifier.align(Alignment.Center))
        }

        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))) {
            Row(
                Modifier.safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(CamNodeIcons.Back, contentDescription = stringResource(R.string.viewer_back), tint = Color.White) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(camera.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HealthPill(camera.health)
                        Text(position, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                if (controllable != null) {
                    IconButton(onClick = { actions.setPaused(camera.id, !controllable.paused) }) {
                        Icon(
                            if (controllable.paused) CamNodeIcons.VideocamFilled else CamNodeIcons.VideocamOff,
                            contentDescription = stringResource(if (controllable.paused) R.string.viewer_resume_camera else R.string.viewer_pause_camera),
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        val live = controllable?.takeIf { !it.paused }
        if (live != null && live.zoomMax > live.zoomMin) {
            Column(
                Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConsoleButton(icon = CamNodeIcons.ZoomIn, description = stringResource(R.string.viewer_zoom_in), onClick = { actions.zoom(camera.id, ZOOM_STEP) })
                Text("%.1fx".format(live.zoom), color = Color.White, style = MaterialTheme.typography.labelLarge)
                ConsoleButton(icon = CamNodeIcons.ZoomOut, description = stringResource(R.string.viewer_zoom_out), onClick = { actions.zoom(camera.id, -ZOOM_STEP) })
            }
        }
        if (scale > 1f) {
            Box(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 64.dp)) {
                MetricChip(CamNodeIcons.ZoomIn, stringResource(R.string.viewer_screen_zoom, scale), onVideo = true)
            }
        }
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))) {
            // Chips on their own line: up to five console buttons need the full width on a 360 dp phone.
            Column(Modifier.safeDrawingPadding().padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HealthChips(camera.health, onVideo = true)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    if (camera.rtspUrl != null) {
                        ConsoleButton(icon = CamNodeIcons.Rotate, description = stringResource(R.string.viewer_rotate), onClick = { actions.rotate(camera.id) })
                    }
                    if (live != null) {
                        ImageModeButton(live.imageMode) { mode -> actions.imageMode(camera.id, mode) }
                        if (live.torchAvailable) {
                            ConsoleButton(
                                icon = if (live.torch) CamNodeIcons.TorchOn else CamNodeIcons.TorchOff,
                                description = stringResource(if (live.torch) R.string.viewer_torch_off else R.string.viewer_torch_on),
                                onClick = { actions.toggleTorch(camera.id) },
                            )
                        }
                        ConsoleButton(
                            icon = CamNodeIcons.SwitchCamera,
                            description = stringResource(if (live.facing == CameraFacing.BACK) R.string.viewer_switch_front else R.string.viewer_switch_back),
                            onClick = { actions.switchLens(camera.id) },
                        )
                    }
                    ConsoleButton(
                        icon = if (muted) CamNodeIcons.VolumeOff else CamNodeIcons.VolumeUp,
                        description = stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute),
                        onClick = onToggleMuted,
                    )
                }
            }
        }
    }
}

/** Mode switch for the camera's picture; the menu shows which mode is active. */
@Composable
private fun ImageModeButton(current: ImageMode, onSelect: (ImageMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ConsoleButton(icon = current.icon, description = stringResource(R.string.viewer_image_mode, current.label), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ImageMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = { Icon(mode.icon, contentDescription = null) },
                    trailingIcon = { if (mode == current) Icon(CamNodeIcons.Check, contentDescription = stringResource(R.string.viewer_active)) },
                    onClick = {
                        open = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

private val ImageMode.label: String
    @Composable get() = stringResource(
        when (this) {
            ImageMode.COLOR -> R.string.viewer_image_color
            ImageMode.MONOCHROME -> R.string.viewer_image_monochrome
            ImageMode.NIGHT -> R.string.viewer_image_night
        },
    )

private val ImageMode.icon: ImageVector
    @Composable get() = when (this) {
        ImageMode.COLOR -> CamNodeIcons.Color
        ImageMode.MONOCHROME -> CamNodeIcons.BlackAndWhite
        ImageMode.NIGHT -> CamNodeIcons.Night
    }

@Composable
private fun ConsoleButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White),
    ) { Icon(icon, contentDescription = description) }
}

/** Screen zoom is instant and free for the camera; sensor zoom gives real detail. */
private const val MAX_SCREEN_ZOOM = 5f
private const val ZOOM_STEP = 0.5f
