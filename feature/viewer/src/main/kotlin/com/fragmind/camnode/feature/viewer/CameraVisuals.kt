package com.fragmind.camnode.feature.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.CamNodeTheme
import com.fragmind.camnode.core.designsystem.MetricChip
import com.fragmind.camnode.core.designsystem.StatusPill
import com.fragmind.camnode.core.designsystem.StatusTone
import com.fragmind.camnode.core.model.NodeStatus

@Composable
internal fun HealthPill(health: CameraHealth, onVideo: Boolean = true, compact: Boolean = false) {
    val (label, tone) = when (health) {
        CameraHealth.Checking -> R.string.viewer_pill_checking to StatusTone.Neutral
        CameraHealth.Offline -> R.string.viewer_pill_offline to StatusTone.Offline
        CameraHealth.Unauthorized -> R.string.viewer_pill_unauthorized to StatusTone.Warning
        is CameraHealth.Online ->
            if (health.camera?.paused == false) R.string.viewer_pill_live to StatusTone.Live else R.string.viewer_pill_paused to StatusTone.Neutral
    }
    StatusPill(stringResource(label), tone, onVideo = onVideo, compact = compact)
}

/** Battery and, when others are watching too, the viewer count of a reachable camera. */
@Composable
internal fun HealthChips(health: CameraHealth, onVideo: Boolean) {
    val online = health as? CameraHealth.Online ?: return
    val status = online.status
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BatteryChip(status, onVideo)
        val viewers = online.camera?.viewers ?: 0
        if (viewers > 1) MetricChip(CamNodeIcons.Visibility, "$viewers", onVideo = onVideo)
    }
}

@Composable
internal fun BatteryChip(status: NodeStatus, onVideo: Boolean) {
    status.batteryPercent?.let { percent ->
        val low = percent <= LOW_BATTERY && !status.charging
        val batteryDescription = stringResource(if (status.charging) R.string.viewer_battery_charging else R.string.viewer_battery_not_charging, percent)
        MetricChip(
            icon = when {
                status.charging -> CamNodeIcons.BatteryCharging
                low -> CamNodeIcons.BatteryAlert
                else -> CamNodeIcons.Battery
            },
            text = "$percent%",
            modifier = Modifier.clearAndSetSemantics { contentDescription = batteryDescription },
            tint = if (low) CamNodeTheme.status.warning else if (status.charging) CamNodeTheme.status.online else Color.Unspecified,
            onVideo = onVideo,
        )
    }
}

/** What replaces the video while a camera can't be played. */
@Composable
internal fun NoSignal(health: CameraHealth, modifier: Modifier = Modifier, compact: Boolean = false) {
    val (title, body) = when (health) {
        CameraHealth.Unauthorized -> R.string.viewer_unauthorized_title to R.string.viewer_unauthorized_body
        CameraHealth.Checking -> R.string.viewer_checking_title to R.string.viewer_checking_body
        is CameraHealth.Online ->
            if (health.camera?.paused == true) R.string.viewer_paused_title to R.string.viewer_paused_body
            else R.string.viewer_stopped_title to R.string.viewer_stopped_body
        CameraHealth.Offline -> R.string.viewer_offline_title to R.string.viewer_offline_body
    }.let { (title, body) -> stringResource(title) to stringResource(body) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            if (health == CameraHealth.Checking) CamNodeIcons.Videocam else CamNodeIcons.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        if (!compact) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val LOW_BATTERY = 20
