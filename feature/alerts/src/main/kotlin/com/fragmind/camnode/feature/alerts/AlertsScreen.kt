package com.fragmind.camnode.feature.alerts

import android.Manifest
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.AlertsRepository
import com.fragmind.camnode.core.data.device.ContinuousOperation
import com.fragmind.camnode.core.data.device.TailscaleSetup
import com.fragmind.camnode.core.designsystem.TailscaleRow
import com.fragmind.camnode.core.network.LocalAddressProvider
import com.fragmind.camnode.core.designsystem.CamNodeCard
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.CamNodeTheme
import com.fragmind.camnode.core.designsystem.EmptyState
import com.fragmind.camnode.core.designsystem.NoticeCard
import com.fragmind.camnode.core.designsystem.SectionHeader
import com.fragmind.camnode.core.designsystem.SettingRow
import com.fragmind.camnode.core.designsystem.StatusTone
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.network.LocalNetworkPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertsUiState(
    val receiverEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = true,
    val alerts: List<CameraEvent> = emptyList(),
    val tailnetAddress: String? = null,
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val repository: AlertsRepository,
    private val continuousOperation: ContinuousOperation,
    private val addresses: LocalAddressProvider,
    val tailscale: TailscaleSetup,
) : ViewModel() {
    private val batteryUnrestricted = MutableStateFlow(continuousOperation.isBatteryUnrestricted())
    private val tailnet = MutableStateFlow(addresses.current().tailnet)

    val uiState: StateFlow<AlertsUiState> = combine(repository.receiverEnabled, batteryUnrestricted, repository.alerts, tailnet, ::AlertsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    /** Battery rules and the Tailscale connection change outside the app, so they are re-read on resume. */
    fun refresh() {
        batteryUnrestricted.value = continuousOperation.isBatteryUnrestricted()
        tailnet.value = addresses.current().tailnet
    }

    fun setReceiverEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setReceiverEnabled(enabled) }
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    fun batteryExemptionIntent(): Intent = continuousOperation.batteryExemptionIntent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsRoute(viewModel: AlertsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.setReceiverEnabled(true)
        AlertReceiverService.start(context)
    }
    val onToggle: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            permissions.launch(buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                addAll(LocalNetworkPermission.runtimePermissions)
            }.toTypedArray())
        } else {
            AlertReceiverService.stop(context)
            viewModel.setReceiverEnabled(false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = { if (state.alerts.isNotEmpty()) TextButton(onClick = viewModel::clear) { Text(stringResource(R.string.alerts_clear)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CamNodeCard {
                SettingRow(
                    icon = CamNodeIcons.Notifications,
                    iconTint = if (state.receiverEnabled) CamNodeTheme.status.online else MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.alerts_master_title),
                    subtitle = stringResource(if (state.receiverEnabled) R.string.alerts_master_on else R.string.alerts_master_off),
                    trailing = { Switch(checked = state.receiverEnabled, onCheckedChange = onToggle) },
                )
            }
            if (state.receiverEnabled) {
                CamNodeCard {
                    TailscaleRow(
                        tailnetAddress = state.tailnetAddress,
                        installed = viewModel.tailscale.isInstalled(),
                        onOpenTailscale = { context.startActivity(viewModel.tailscale.openAppIntent() ?: viewModel.tailscale.installIntent()) },
                        onAlwaysOn = { context.startActivity(viewModel.tailscale.alwaysOnVpnIntent()) },
                        disconnectedHint = stringResource(R.string.alerts_tailscale_hint),
                    )
                }
            }
            if (state.receiverEnabled && !state.batteryUnrestricted) {
                NoticeCard(
                    CamNodeIcons.BatteryAlert,
                    stringResource(R.string.alerts_battery_title),
                    stringResource(R.string.alerts_battery_body),
                    StatusTone.Warning,
                    action = { TextButton(onClick = { context.startActivity(viewModel.batteryExemptionIntent()) }) { Text(stringResource(R.string.alerts_allow)) } },
                )
            }

            SectionHeader(stringResource(R.string.alerts_recent))
            if (state.alerts.isEmpty()) {
                EmptyState(
                    icon = CamNodeIcons.Shield,
                    title = stringResource(R.string.alerts_empty_title),
                    body = stringResource(R.string.alerts_empty_body),
                )
            } else {
                CamNodeCard {
                    state.alerts.forEachIndexed { index, event ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        SettingRow(
                            icon = CamNodeIcons.Alarm,
                            iconTint = CamNodeTheme.status.live,
                            title = event.title(context),
                            subtitle = DateUtils.getRelativeDateTimeString(
                                context, event.atMillis, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0,
                            ).toString(),
                        )
                    }
                }
            }
        }
    }
}
