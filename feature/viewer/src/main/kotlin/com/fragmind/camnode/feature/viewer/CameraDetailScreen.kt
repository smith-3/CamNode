package com.fragmind.camnode.feature.viewer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import com.fragmind.camnode.core.designsystem.CameraConsole
import com.fragmind.camnode.core.designsystem.ConsoleAction
import com.fragmind.camnode.core.designsystem.ConsoleIconButton
import com.fragmind.camnode.core.designsystem.LastPhoto
import com.fragmind.camnode.core.designsystem.ShutterAction
import com.fragmind.camnode.core.designsystem.ShutterBar
import com.fragmind.camnode.core.designsystem.ZoomStops
import kotlinx.coroutines.launch
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    val setZoom: (NodeId, Float) -> Unit,
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
                setZoom = viewModel::setZoom,
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
    // Shared by every page: the newest CamNode photo in the gallery, re-read on resume so it
    // survives leaving the screen and notices photos deleted from the gallery.
    var lastPhoto by remember { mutableStateOf<SavedPhoto?>(null) }
    val context = LocalContext.current
    var reloads by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        reloads++
        onPauseOrDispose { }
    }
    LaunchedEffect(reloads) {
        val uri = PhotoStore.latest(context)
        val thumbnail = uri?.let { PhotoStore.thumbnail(context, it, THUMBNAIL_WIDTH) }
        lastPhoto = if (uri != null && thumbnail != null) SavedPhoto(uri, thumbnail.asImageBitmap()) else null
    }
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
            lastPhoto = lastPhoto,
            onPhotoSaved = { lastPhoto = it },
        )
    }
}

/** A photo saved in this phone's gallery, with a small copy for the thumbnail. */
internal class SavedPhoto(val uri: Uri, val thumbnail: ImageBitmap)

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
    lastPhoto: SavedPhoto?,
    onPhotoSaved: (SavedPhoto) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Viewers without the control permission keep only what affects their own screen.
    val streaming = (camera.health as? CameraHealth.Online)?.camera
    val controllable = streaming?.takeIf { canControl }
    val live = controllable?.takeIf { !it.paused }
    val grabber = remember { FrameGrabber() }
    val takePhoto = rememberPhotoTaker(camera, grabber, onPhotoSaved)
    val context = LocalContext.current

    CameraConsole(
        modifier = Modifier.fillMaxSize(),
        top = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) { HealthChips(camera.health, onVideo = true) }
                    if (camera.rtspUrl != null) {
                        ConsoleIconButton(CamNodeIcons.Rotate, stringResource(R.string.viewer_rotate), { actions.rotate(camera.id) })
                    }
                    if (live != null) {
                        ImageModeButton(live.imageMode) { mode -> actions.imageMode(camera.id, mode) }
                        if (live.torchAvailable) {
                            ConsoleIconButton(
                                if (live.torch) CamNodeIcons.TorchOn else CamNodeIcons.TorchOff,
                                stringResource(if (live.torch) R.string.viewer_torch_off else R.string.viewer_torch_on),
                                { actions.toggleTorch(camera.id) },
                            )
                        }
                    }
                    ConsoleIconButton(
                        if (muted) CamNodeIcons.VolumeOff else CamNodeIcons.VolumeUp,
                        stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute),
                        onToggleMuted,
                    )
                }
            }
        },
        bottom = {
            // Watching needs no buttons; capture controls belong to those who control cameras.
            if (live != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ZoomStops(live.zoom, live.zoomMin, live.zoomMax, onSelect = { zoom -> actions.setZoom(camera.id, zoom) })
                    ShutterBar(
                        shutter = ShutterAction(stringResource(R.string.viewer_take_photo), enabled = grabber.ready, onClick = takePhoto),
                        lastPhoto = lastPhoto?.let { photo ->
                            LastPhoto(photo.thumbnail, stringResource(R.string.viewer_open_last_photo)) { openPhoto(context, photo.uri) }
                        },
                        switchLens = ConsoleAction(
                            stringResource(if (live.facing == CameraFacing.BACK) R.string.viewer_switch_front else R.string.viewer_switch_back),
                        ) { actions.switchLens(camera.id) },
                    )
                }
            }
        },
    ) {
        val url = camera.rtspUrl
        when {
            url != null -> RtspPlayer(
                url,
                muted = muted,
                playback = playback,
                contentScale = ContentScale.Fit,
                rotationDegrees = camera.displayRotation,
                transformable = true,
                grabber = grabber.takeIf { canControl },
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
                        // Two fingers zoom this screen only; one finger pans only once zoomed.
                        // Otherwise the drag is left unconsumed so the pager can switch cameras.
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
        if (scale > 1f) {
            Box(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 112.dp)) {
                MetricChip(CamNodeIcons.ZoomIn, stringResource(R.string.viewer_screen_zoom, scale), onVideo = true)
            }
        }
        PhotoFlash(takePhoto.flashes)
    }
}

/**
 * Copies the current frame, flashes the screen and saves it to this phone's gallery. On
 * Android 9 the storage permission is asked the first time.
 */
@Composable
private fun rememberPhotoTaker(camera: CameraTile, grabber: FrameGrabber, onSaved: (SavedPhoto) -> Unit): PhotoTaker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val taker = remember { PhotoTaker() }
    val failed = stringResource(R.string.viewer_photo_failed)
    val capture: () -> Unit = capture@{
        val frame = grabber.grab(camera.displayRotation) ?: run {
            Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
            return@capture
        }
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        taker.flashes++
        val thumbnail = Bitmap.createScaledBitmap(frame, THUMBNAIL_WIDTH, maxOf(1, THUMBNAIL_WIDTH * frame.height / frame.width), true).asImageBitmap()
        scope.launch {
            val uri = PhotoStore.save(context, frame, camera.name, System.currentTimeMillis())
            frame.recycle()
            if (uri != null) onSaved(SavedPhoto(uri, thumbnail)) else Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture() else Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
    }
    taker.onClick = {
        val needsStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsStorage) storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else capture()
    }
    return taker
}

/** The shutter handler plus a counter the flash animates on. */
@Stable
private class PhotoTaker : () -> Unit {
    var onClick: () -> Unit = {}
    var flashes by mutableIntStateOf(0)
    override fun invoke() = onClick()
}

/** A brief white flash, like a camera, each time [flashes] grows. */
@Composable
private fun BoxScope.PhotoFlash(flashes: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(flashes) {
        if (flashes == 0) return@LaunchedEffect
        alpha.snapTo(0.8f)
        alpha.animateTo(0f, tween(durationMillis = 250))
    }
    if (alpha.value > 0f) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = alpha.value)))
}

private fun openPhoto(context: Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        // No gallery app; the photo is still saved in Pictures/CamNode.
    }
}

/** Mode switch for the camera's picture; the menu shows which mode is active. */
@Composable
private fun ImageModeButton(current: ImageMode, onSelect: (ImageMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ConsoleIconButton(current.icon, stringResource(R.string.viewer_image_mode, current.label), { open = true })
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

/** Screen zoom is instant and free for the camera; sensor zoom gives real detail. */
private const val MAX_SCREEN_ZOOM = 5f
private const val THUMBNAIL_WIDTH = 160
