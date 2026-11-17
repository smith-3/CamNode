package com.fragmind.camnode.feature.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.EmptyState
import com.fragmind.camnode.core.model.NodeId

@Composable
internal fun AlertsSettingsScreen(
    cameras: List<CameraAlertPreference>,
    onMute: (NodeId, Boolean) -> Unit,
    onUnmute: (NodeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (cameras.isEmpty()) {
            EmptyState(
                icon = CamNodeIcons.Notifications,
                title = stringResource(R.string.alerts_settings_empty_title),
                body = stringResource(R.string.alerts_settings_empty_body),
            )
        } else {
            CameraAlertPreferences(cameras, onMute, onUnmute)
        }
    }
}
