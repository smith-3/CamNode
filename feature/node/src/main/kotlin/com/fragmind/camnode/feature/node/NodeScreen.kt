package com.fragmind.camnode.feature.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.content.Intent
import android.os.Build
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fragmind.camnode.core.designsystem.ButtonEmphasis
import com.fragmind.camnode.core.designsystem.CamNodeButton
import com.fragmind.camnode.core.designsystem.CamNodeCard
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.CamNodeTheme
import com.fragmind.camnode.core.designsystem.MetricChip
import com.fragmind.camnode.core.designsystem.NoticeCard
import com.fragmind.camnode.core.designsystem.SectionHeader
import com.fragmind.camnode.core.designsystem.SettingRow
import com.fragmind.camnode.core.designsystem.StatusPill
import com.fragmind.camnode.core.designsystem.StatusTone
import com.fragmind.camnode.core.designsystem.TailscaleRow
import com.fragmind.camnode.core.model.CameraFacing
import com.fragmind.camnode.core.model.CameraSettings
import com.fragmind.camnode.core.model.NetworkKind
import com.fragmind.camnode.core.model.ImageMode
import com.fragmind.camnode.core.model.StreamProfile
import com.fragmind.camnode.core.model.VideoOrientation
import com.fragmind.camnode.core.network.LocalNetworkPermission

@Composable
fun NodeRoute(viewModel: NodeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val activity = LocalActivity.current
    var micGranted by remember { mutableStateOf(context.hasMicPermission()) }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        micGranted = context.hasMicPermission()
        onPauseOrDispose { }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        micGranted = context.hasMicPermission()
        if (granted[Manifest.permission.CAMERA] == true) CameraNodeService.start(context)
    }
    // Without a rationale both before and after a denial, Android never showed the dialog:
    // the permission is blocked and only the app's settings page can grant it.
    var micRationaleBefore by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) {
            viewModel.updateSettings { it.copy(audioEnabled = true) }
        } else if (!micRationaleBefore && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }

    NodeScreen(
        state = state,
        onStart = { permissions.launch(requiredPermissions(state.settings).toTypedArray()) },
        onStop = { CameraNodeService.stop(context) },
        onSettingsChange = viewModel::updateSettings,
        micGranted = micGranted,
        onRequestMic = {
            micRationaleBefore = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onSelectFacing = viewModel::selectFacing,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onSelectImageMode = viewModel::selectImageMode,
        onSelectOrientation = viewModel::selectOrientation,
        onCopyLink = { link -> clipboard.setText(AnnotatedString(link.url)) },
        onRequestBatteryExemption = { context.startActivity(viewModel.batteryExemptionIntent()) },
        onOpenVendorSettings = { intent -> context.startActivity(intent) },
        tailscaleInstalled = viewModel.tailscale.isInstalled(),
        onOpenTailscale = { context.startActivity(viewModel.tailscale.openAppIntent() ?: viewModel.tailscale.installIntent()) },
        onAlwaysOnVpn = { context.startActivity(viewModel.tailscale.alwaysOnVpnIntent()) },
        previewByDefault = viewModel.previewByDefault,
        preview = { NodePreview(viewModel::attachPreview, viewModel::detachPreview) },
    )
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(settings: CameraSettings): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (settings.audioEnabled) add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    addAll(LocalNetworkPermission.runtimePermissions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeScreen(
    state: NodeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettingsChange: ((CameraSettings) -> CameraSettings) -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onSelectFacing: (CameraFacing) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSelectImageMode: (ImageMode) -> Unit,
    onSelectOrientation: (VideoOrientation) -> Unit,
    onCopyLink: (PlayerLink) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenVendorSettings: (Intent) -> Unit,
    tailscaleInstalled: Boolean,
    onOpenTailscale: () -> Unit,
    onAlwaysOnVpn: () -> Unit,
    previewByDefault: Boolean,
    preview: @Composable () -> Unit,
) {
    val live = state.stream as? StreamState.Live
    var showPreview by rememberSaveable { mutableStateOf(previewByDefault) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.node_title), style = MaterialTheme.typography.headlineSmall)
                        Text(state.deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CameraHero(live, showPreview, onTogglePreview = { showPreview = !showPreview }, preview)
            (state.stream as? StreamState.Failed)?.let {
                NoticeCard(CamNodeIcons.Error, stringResource(R.string.node_start_failed), it.describe(), StatusTone.Live)
            }
            when {
                live != null -> {
                    CamNodeButton(stringResource(R.string.node_pause), onPause, icon = CamNodeIcons.VisibilityOff, emphasis = ButtonEmphasis.Secondary)
                    CamNodeButton(stringResource(R.string.node_stop), onStop, icon = CamNodeIcons.VideocamOff, emphasis = ButtonEmphasis.Danger)
                }
                state.stream is StreamState.Paused -> {
                    NoticeCard(
                        CamNodeIcons.VideocamOff,
                        stringResource(R.string.node_paused_title),
                        stringResource(R.string.node_paused_body),
                        StatusTone.Neutral,
                    )
                    CamNodeButton(stringResource(R.string.node_resume), onResume, icon = CamNodeIcons.VideocamFilled)
                    CamNodeButton(stringResource(R.string.node_stop), onStop, icon = CamNodeIcons.VideocamOff, emphasis = ButtonEmphasis.Secondary)
                }
                else -> CamNodeButton(stringResource(R.string.node_start), onStart, icon = CamNodeIcons.VideocamFilled)
            }

            SectionHeader(stringResource(R.string.node_connection))
            ConnectionCard(state, live != null, onCopyLink, tailscaleInstalled, onOpenTailscale, onAlwaysOnVpn)

            SectionHeader(stringResource(R.string.node_video))
            VideoCard(state.settings, live != null, micGranted, onRequestMic, onSettingsChange, onSelectFacing, onSelectImageMode, onSelectOrientation)

            SectionHeader(stringResource(R.string.node_security))
            SecurityCard(state.settings, live != null, onSettingsChange)

            SectionHeader(stringResource(R.string.node_continuous))
            ContinuousCard(state.continuous, onRequestBatteryExemption, onOpenVendorSettings)
        }
    }
}

@Composable
private fun CameraHero(live: StreamState.Live?, showPreview: Boolean, onTogglePreview: () -> Unit, preview: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (live != null && live.rotationDegrees % 180 == 90) 3f / 4f else 16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        if (live != null) {
            if (showPreview) {
                preview()
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(CamNodeIcons.VisibilityOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    Text(stringResource(R.string.node_preview_hidden), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.node_preview_hidden_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            IconButton(onClick = onTogglePreview, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(
                    if (showPreview) CamNodeIcons.VisibilityOff else CamNodeIcons.Visibility,
                    contentDescription = stringResource(if (showPreview) R.string.node_hide_preview else R.string.node_show_preview),
                    tint = Color.White,
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(stringResource(R.string.node_live), StatusTone.Live, onVideo = true)
                MetricChip(CamNodeIcons.Visibility, "${live.viewers}", onVideo = true)
                // Only while someone watches: idle, the camera encodes a couple of frames a second.
                val sending = live.sendingBps
                if (live.viewers > 0 && sending != null) {
                    val rate = formatDataRate(sending)
                    val description = stringResource(R.string.node_sending_per_viewer, rate)
                    MetricChip(CamNodeIcons.Upload, rate, onVideo = true, modifier = Modifier.clearAndSetSemantics { contentDescription = description })
                }
            }
            Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip(CamNodeIcons.Hd, live.settings.profile.shortLabel, onVideo = true)
                if (live.settings.imageMode != ImageMode.COLOR) {
                    MetricChip(if (live.settings.imageMode == ImageMode.NIGHT) CamNodeIcons.Night else CamNodeIcons.BlackAndWhite, live.settings.imageMode.label, onVideo = true)
                }
                MetricChip(CamNodeIcons.SwitchCamera, live.settings.facing.label, onVideo = true)
                if (live.settings.audioEnabled) MetricChip(CamNodeIcons.Mic, stringResource(R.string.node_audio), onVideo = true)
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(CamNodeIcons.VideocamOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.node_camera_off), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.node_camera_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: NodeUiState,
    live: Boolean,
    onCopyLink: (PlayerLink) -> Unit,
    tailscaleInstalled: Boolean,
    onOpenTailscale: () -> Unit,
    onAlwaysOnVpn: () -> Unit,
) {
    val status = CamNodeTheme.status
    CamNodeCard {
        SettingRow(
            icon = CamNodeIcons.Wifi,
            title = stringResource(R.string.node_local_wifi),
            subtitle = state.addresses.lan ?: stringResource(R.string.node_no_wifi),
            iconTint = if (state.addresses.lan != null) status.online else status.offline,
            trailing = state.links(NetworkKind.LAN, live)?.let { link -> { CopyButton { onCopyLink(link) } } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        TailscaleRow(
            tailnetAddress = state.addresses.tailnet,
            installed = tailscaleInstalled,
            onOpenTailscale = onOpenTailscale,
            onAlwaysOn = onAlwaysOnVpn,
            disconnectedHint = stringResource(R.string.node_tailscale_hint),
        )
        state.links(NetworkKind.TAILNET, live)?.let { link ->
            SettingRow(
                icon = CamNodeIcons.Copy,
                title = stringResource(R.string.node_rtsp_tailscale),
                subtitle = stringResource(R.string.node_rtsp_tailscale_body),
                onClick = { onCopyLink(link) },
            )
        }
    }
}

private fun NodeUiState.links(network: NetworkKind, live: Boolean): PlayerLink? =
    if (live) playerLinks.firstOrNull { it.network == network } else null

@Composable
private fun CopyButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(CamNodeIcons.Copy, contentDescription = stringResource(R.string.node_copy_rtsp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoCard(
    settings: CameraSettings,
    live: Boolean,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onSettingsChange: ((CameraSettings) -> CameraSettings) -> Unit,
    onSelectFacing: (CameraFacing) -> Unit,
    onSelectImageMode: (ImageMode) -> Unit,
    onSelectOrientation: (VideoOrientation) -> Unit,
) {
    CamNodeCard {
        Text(stringResource(R.string.node_quality), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StreamProfile.entries.forEachIndexed { index, profile ->
                SegmentedButton(
                    selected = settings.profile == profile,
                    onClick = { onSettingsChange { it.copy(profile = profile) } },
                    shape = SegmentedButtonDefaults.itemShape(index, StreamProfile.entries.size),
                    enabled = !live,
                ) { Text(profile.shortLabel) }
            }
        }
        Text(stringResource(R.string.node_lens), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CameraFacing.entries.forEachIndexed { index, facing ->
                SegmentedButton(
                    selected = settings.facing == facing,
                    onClick = { onSelectFacing(facing) },
                    shape = SegmentedButtonDefaults.itemShape(index, CameraFacing.entries.size),
                ) { Text(facing.label) }
            }
        }
        Text(stringResource(R.string.node_image), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ImageMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.imageMode == mode,
                    onClick = { onSelectImageMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ImageMode.entries.size),
                ) { Text(mode.label) }
            }
        }
        Text(stringResource(R.string.node_orientation), style = MaterialTheme.typography.titleSmall)
        val base = settings.orientation.base
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OrientationChoice.entries.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = base == choice,
                    onClick = { onSelectOrientation(choice.toOrientation(inverted = settings.orientation.inverted)) },
                    shape = SegmentedButtonDefaults.itemShape(index, OrientationChoice.entries.size),
                ) { Text(stringResource(choice.labelRes)) }
            }
        }
        SettingRow(
            icon = CamNodeIcons.Rotate,
            title = stringResource(R.string.node_upside_down),
            subtitle = stringResource(if (base == OrientationChoice.AUTO) R.string.node_upside_down_auto else R.string.node_upside_down_body),
            trailing = {
                Switch(
                    checked = settings.orientation.inverted,
                    enabled = base != OrientationChoice.AUTO,
                    onCheckedChange = { inverted -> onSelectOrientation(base.toOrientation(inverted)) },
                )
            },
        )
        SettingRow(
            icon = CamNodeIcons.Mic,
            title = stringResource(R.string.node_audio),
            subtitle = stringResource(
                when {
                    settings.audioEnabled && !micGranted -> R.string.node_audio_no_permission
                    live -> R.string.node_applies_on_restart
                    else -> R.string.node_audio_body
                },
            ),
            trailing = {
                // The service streams without sound when the permission is missing, so show that.
                Switch(
                    checked = settings.audioEnabled && micGranted,
                    onCheckedChange = { enabled ->
                        if (enabled && !micGranted) onRequestMic() else onSettingsChange { it.copy(audioEnabled = enabled) }
                    },
                )
            },
        )
    }
}

@Composable
private fun SecurityCard(settings: CameraSettings, live: Boolean, onSettingsChange: ((CameraSettings) -> CameraSettings) -> Unit) {
    CamNodeCard {
        SettingRow(
            icon = CamNodeIcons.Alarm,
            iconTint = CamNodeTheme.status.live,
            title = stringResource(R.string.node_motion_alert),
            subtitle = stringResource(if (live) R.string.node_applies_on_restart else R.string.node_motion_alert_body),
            trailing = {
                Switch(
                    checked = settings.movementAlerts,
                    onCheckedChange = { enabled -> onSettingsChange { it.copy(movementAlerts = enabled) } },
                )
            },
        )
    }
}

@Composable
private fun ContinuousCard(
    continuous: ContinuousState,
    onRequestBatteryExemption: () -> Unit,
    onOpenVendorSettings: (Intent) -> Unit,
) {
    val status = CamNodeTheme.status
    CamNodeCard {
        SettingRow(
            icon = if (continuous.batteryUnrestricted) CamNodeIcons.Check else CamNodeIcons.BatteryAlert,
            iconTint = if (continuous.batteryUnrestricted) status.online else status.warning,
            title = stringResource(R.string.node_battery_saver),
            subtitle = stringResource(if (continuous.batteryUnrestricted) R.string.node_battery_unrestricted else R.string.node_battery_restricted),
            trailing = if (continuous.batteryUnrestricted) null else ({ TextButton(onClick = onRequestBatteryExemption) { Text(stringResource(R.string.node_allow)) } }),
        )
        continuous.vendor?.let { vendor ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            SettingRow(
                icon = CamNodeIcons.Bolt,
                iconTint = status.warning,
                title = stringResource(R.string.node_vendor_title, vendor.brand),
                subtitle = stringResource(R.string.node_vendor_body),
                onClick = { onOpenVendorSettings(vendor.intent) },
                trailing = { Icon(CamNodeIcons.ChevronRight, contentDescription = null) },
            )
        }
        Text(
            stringResource(R.string.node_keep_charging),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val StreamProfile.shortLabel: String
    get() = when (this) {
        StreamProfile.LOW -> "360p"
        StreamProfile.MEDIUM -> "720p"
        StreamProfile.HIGH -> "1080p"
    }

private enum class OrientationChoice(@StringRes val labelRes: Int) {
    AUTO(R.string.node_orientation_auto),
    LANDSCAPE(R.string.node_orientation_landscape),
    PORTRAIT(R.string.node_orientation_portrait);

    fun toOrientation(inverted: Boolean): VideoOrientation = when (this) {
        AUTO -> VideoOrientation.AUTO
        LANDSCAPE -> if (inverted) VideoOrientation.LANDSCAPE_INVERTED else VideoOrientation.LANDSCAPE
        PORTRAIT -> if (inverted) VideoOrientation.PORTRAIT_INVERTED else VideoOrientation.PORTRAIT
    }
}

private val VideoOrientation.base: OrientationChoice
    get() = when (this) {
        VideoOrientation.AUTO -> OrientationChoice.AUTO
        VideoOrientation.LANDSCAPE, VideoOrientation.LANDSCAPE_INVERTED -> OrientationChoice.LANDSCAPE
        VideoOrientation.PORTRAIT, VideoOrientation.PORTRAIT_INVERTED -> OrientationChoice.PORTRAIT
    }

private val VideoOrientation.inverted: Boolean
    get() = this == VideoOrientation.LANDSCAPE_INVERTED || this == VideoOrientation.PORTRAIT_INVERTED

private val CameraFacing.label: String
    @Composable get() = stringResource(if (this == CameraFacing.BACK) R.string.node_lens_back else R.string.node_lens_front)

@Composable
private fun NodePreview(attach: (SurfaceView) -> Unit, detach: () -> Unit) {
    AndroidView(factory = { SurfaceView(it).also(attach) }, modifier = Modifier.fillMaxSize())
    DisposableEffect(Unit) { onDispose(detach) }
}

private val ImageMode.label: String
    @Composable get() = stringResource(
        when (this) {
            ImageMode.COLOR -> R.string.node_image_color
            ImageMode.MONOCHROME -> R.string.node_image_monochrome
            ImageMode.NIGHT -> R.string.node_image_night
        },
    )

@Composable
private fun StreamState.Failed.describe(): String = when (problem) {
    StreamProblem.SERVER -> stringResource(R.string.node_error_server, detail)
    StreamProblem.UNSUPPORTED_SIZE -> stringResource(R.string.node_error_size, detail)
    StreamProblem.ENCODER -> stringResource(R.string.node_error_encoder)
}
