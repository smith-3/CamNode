package com.fragmind.camnode.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Tailscale status with the two actions that make the connection permanent. The
 * caller supplies the intents, so the design system stays free of platform logic.
 */
@Composable
fun TailscaleRow(
    tailnetAddress: String?,
    installed: Boolean,
    onOpenTailscale: () -> Unit,
    onAlwaysOn: () -> Unit,
    disconnectedHint: String,
) {
    val status = CamNodeTheme.status
    SettingRow(
        icon = CamNodeIcons.Tailscale,
        title = "Tailscale",
        subtitle = when {
            tailnetAddress != null -> stringResource(R.string.tailscale_connected, tailnetAddress)
            installed -> stringResource(R.string.tailscale_disconnected, disconnectedHint)
            else -> stringResource(R.string.tailscale_not_installed, disconnectedHint)
        },
        iconTint = if (tailnetAddress != null) status.online else status.warning,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onOpenTailscale) { Text(stringResource(if (installed) R.string.tailscale_open else R.string.tailscale_install)) }
        if (installed) TextButton(onClick = onAlwaysOn) { Text(stringResource(R.string.tailscale_always_on)) }
    }
}
