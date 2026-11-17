package com.fragmind.camnode.feature.alerts

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeCard
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.SectionHeader
import com.fragmind.camnode.core.designsystem.SettingRow
import com.fragmind.camnode.core.model.NodeId

data class CameraAlertPreference(val id: NodeId, val name: String, val mutedUntilMillis: Long?)

@Composable
internal fun CameraAlertPreferences(
    cameras: List<CameraAlertPreference>,
    onMute: (NodeId, Boolean) -> Unit,
    onUnmute: (NodeId) -> Unit,
) {
    if (cameras.isEmpty()) return
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    CamNodeCard {
        SectionHeader(stringResource(R.string.alerts_camera_preferences))
        Text(stringResource(R.string.alerts_local_scope))
        cameras.forEach { camera ->
            val subtitle = when (camera.mutedUntilMillis) {
                null -> stringResource(R.string.alerts_camera_enabled)
                Long.MAX_VALUE -> stringResource(R.string.alerts_camera_muted)
                else -> stringResource(
                    R.string.alerts_camera_muted_until,
                    DateUtils.formatDateTime(context, camera.mutedUntilMillis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE),
                )
            }
            SettingRow(
                icon = CamNodeIcons.Notifications,
                title = camera.name,
                subtitle = subtitle,
                onClick = { selectedId = camera.id.value },
                trailing = {
                    TextButton(onClick = { selectedId = camera.id.value }) {
                        Text(stringResource(R.string.alerts_configure))
                    }
                },
            )
        }
    }
    cameras.firstOrNull { it.id.value == selectedId }?.let { camera ->
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text(camera.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.alerts_local_scope))
                    TextButton(onClick = { onUnmute(camera.id); selectedId = null }) {
                        Text(stringResource(R.string.alerts_camera_receive))
                    }
                    TextButton(onClick = { onMute(camera.id, true); selectedId = null }) {
                        Text(stringResource(R.string.alerts_camera_mute_hour))
                    }
                    TextButton(onClick = { onMute(camera.id, false); selectedId = null }) {
                        Text(stringResource(R.string.alerts_camera_mute_forever))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedId = null }) { Text(stringResource(R.string.alerts_cancel)) }
            },
        )
    }
}
