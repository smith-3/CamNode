package com.fragmind.camnode.feature.alerts

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.fragmind.camnode.core.designsystem.IconTile
import com.fragmind.camnode.core.model.CameraEventType
import com.fragmind.camnode.core.model.NodeId
import java.time.ZoneId
import java.util.Date
import android.Manifest
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.app.NotificationManagerCompat
import android.provider.Settings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.AlertsRepository
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.device.ContinuousOperation
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.CamNodeTheme
import com.fragmind.camnode.core.designsystem.EmptyState
import com.fragmind.camnode.core.designsystem.NoticeCard
import com.fragmind.camnode.core.designsystem.SectionHeader
import com.fragmind.camnode.core.designsystem.StatusTone
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.WallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertsUiState(
    val receiverEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = true,
    val notificationsAllowed: Boolean = true,
    val alerts: List<CameraEvent> = emptyList(),
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlertsRepository,
    private val continuousOperation: ContinuousOperation,
    private val households: HouseholdRepository,
    private val clock: WallClock,
) : ViewModel() {
    private val batteryUnrestricted = MutableStateFlow(continuousOperation.isBatteryUnrestricted())
    private val notificationsAllowed = MutableStateFlow(notificationsEnabled())

    val uiState: StateFlow<AlertsUiState> =
        combine(repository.receiverEnabled, batteryUnrestricted, notificationsAllowed, repository.alerts, ::AlertsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    val cameraPreferences: StateFlow<List<CameraAlertPreference>> = combine(
        households.session,
        repository.cameraMutes,
        flow {
            while (true) {
                emit(clock.nowMillis())
                delay(1_000)
            }
        },
    ) { session, mutes, now ->
        session?.takeIf { it.can(Permission.VIEW_STREAMS) }?.directory?.cameras
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { camera -> CameraAlertPreference(camera.id, camera.name, mutes[camera.id]?.takeIf { it > now }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    fun muteCamera(cameraId: NodeId, temporarily: Boolean) {
        viewModelScope.launch {
            repository.setCameraMuted(cameraId, if (temporarily) clock.nowMillis() + 3_600_000 else Long.MAX_VALUE)
        }
    }

    fun unmuteCamera(cameraId: NodeId) {
        viewModelScope.launch { repository.setCameraMuted(cameraId, null) }
    }

    /** Battery and notification rules change outside the app, so they are re-read on resume. */
    fun refresh() {
        batteryUnrestricted.value = continuousOperation.isBatteryUnrestricted()
        notificationsAllowed.value = notificationsEnabled()
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    suspend fun dismiss(id: String) = repository.dismiss(id)

    suspend fun restore(event: CameraEvent) = repository.restore(event)

    fun batteryExemptionIntent(): Intent = continuousOperation.batteryExemptionIntent()

    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/**
 * Lists alert history with a separate page for notification preferences on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsRoute(onOpenCamera: (NodeId) -> Unit, viewModel: AlertsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val historyScrollState = rememberScrollState()
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val snackbarHost = remember { SnackbarHostState() }
    val restoredVersions = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val dismissedMessage = stringResource(R.string.alert_dismissed)
    val undoLabel = stringResource(R.string.alert_undo)
    val dismissLabel = stringResource(R.string.alert_dismiss)
    val dismiss: (CameraEvent) -> Unit = { event ->
        scope.launch {
            viewModel.dismiss(event.id)
            if (snackbarHost.showSnackbar(dismissedMessage, undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                restoredVersions[event.id] = (restoredVersions[event.id] ?: 0) + 1
                viewModel.restore(event)
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Android stops showing the dialog after repeated denials; only Settings can turn them on then.
        if (!granted) context.startActivity(viewModel.notificationSettingsIntent())
        viewModel.refresh()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (settingsOpen) R.string.alerts_settings_title else R.string.alerts_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                navigationIcon = {
                    if (settingsOpen) {
                        IconButton(onClick = { settingsOpen = false }) {
                            Icon(CamNodeIcons.Back, contentDescription = stringResource(R.string.alerts_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (!settingsOpen) {
                        if (state.alerts.isNotEmpty()) {
                            TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.alerts_clear)) }
                        }
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(CamNodeIcons.Settings, contentDescription = stringResource(R.string.alerts_settings_title))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (settingsOpen) {
            val cameraPreferences by viewModel.cameraPreferences.collectAsStateWithLifecycle()
            AlertsSettingsScreen(
                cameras = cameraPreferences,
                onMute = viewModel::muteCamera,
                onUnmute = viewModel::unmuteCamera,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(historyScrollState).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    !state.receiverEnabled -> NoticeCard(
                        CamNodeIcons.Notifications,
                        stringResource(R.string.alerts_muted_title),
                        stringResource(R.string.alerts_muted_body),
                        StatusTone.Neutral,
                    )
                    !state.notificationsAllowed -> NoticeCard(
                        CamNodeIcons.Notifications,
                        stringResource(R.string.alerts_notifications_title),
                        stringResource(R.string.alerts_notifications_body),
                        StatusTone.Warning,
                        action = {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(viewModel.notificationSettingsIntent())
                            }) { Text(stringResource(R.string.alerts_allow)) }
                        },
                    )
                    !state.batteryUnrestricted -> NoticeCard(
                        CamNodeIcons.BatteryAlert,
                        stringResource(R.string.alerts_battery_title),
                        stringResource(R.string.alerts_battery_body),
                        StatusTone.Warning,
                        action = { TextButton(onClick = { context.startActivity(viewModel.batteryExemptionIntent()) }) { Text(stringResource(R.string.alerts_allow)) } },
                    )
                }

                if (state.alerts.isEmpty()) {
                    EmptyState(
                        icon = CamNodeIcons.Shield,
                        title = stringResource(R.string.alerts_empty_title),
                        body = stringResource(R.string.alerts_empty_body),
                    )
                } else {
                    groupByDay(state.alerts, System.currentTimeMillis(), ZoneId.systemDefault()).forEach { (day, events) ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader(day.label(context, events.first().atMillis))
                            events.forEach { event ->
                                key(event.id, restoredVersions[event.id] ?: 0) {
                                    AlertCard(event, onOpen = { onOpenCamera(event.cameraId) }, onDismiss = { dismiss(event) }, dismissLabel = dismissLabel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.alerts_clear_title)) },
            text = { Text(stringResource(R.string.alerts_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clear()
                }) { Text(stringResource(R.string.alerts_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.alerts_cancel)) } },
        )
    }
}

/**
 * One alert as its own card: camera name, what happened and the time. Tapping opens the
 * camera; swiping left reveals "Dismiss" and removes it (with Undo in the snackbar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertCard(event: CameraEvent, onOpen: () -> Unit, onDismiss: () -> Unit, dismissLabel: String) {
    val context = LocalContext.current
    val shape = MaterialTheme.shapes.large
    val dismissState = rememberSwipeToDismissBoxState()
    val openLabel = stringResource(R.string.alert_open)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { direction -> if (direction == SwipeToDismissBoxValue.EndToStart) onDismiss() },
        modifier = Modifier.clip(shape),
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.alert_swipe_dismiss), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Icon(CamNodeIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClickLabel = openLabel, onClick = onOpen)
                .semantics { customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true }) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(CamNodeIcons.Alarm, tint = CamNodeTheme.status.live, size = 44)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.cameraName, style = MaterialTheme.typography.titleSmall)
                Text(event.summary(context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(DateFormat.getTimeFormat(context).format(Date(event.atMillis)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun CameraEvent.summary(context: Context): String = when (type) {
    CameraEventType.MOVED -> context.getString(R.string.alert_moved_summary)
}

private fun AlertDay.label(context: Context, atMillis: Long): String = when (this) {
    AlertDay.Today -> context.getString(R.string.alerts_today)
    AlertDay.Yesterday -> context.getString(R.string.alerts_yesterday)
    is AlertDay.On -> DateUtils.formatDateTime(context, atMillis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
}
