package com.fragmind.camnode.feature.household

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.Permission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.data.DirectorySync
import com.fragmind.camnode.core.data.HouseholdSession
import com.fragmind.camnode.core.data.PeerProbe
import com.fragmind.camnode.core.data.PeerReachability
import com.fragmind.camnode.core.data.device.DeviceBattery
import com.fragmind.camnode.core.designsystem.NoticeCard
import com.fragmind.camnode.core.designsystem.StatusTone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import com.fragmind.camnode.core.designsystem.ButtonEmphasis
import com.fragmind.camnode.core.designsystem.CamNodeButton
import com.fragmind.camnode.core.designsystem.CamNodeCard
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.CamNodeTheme
import com.fragmind.camnode.core.designsystem.IconTile
import com.fragmind.camnode.core.designsystem.MetricChip
import com.fragmind.camnode.core.designsystem.SectionHeader
import com.fragmind.camnode.core.designsystem.SettingRow
import com.fragmind.camnode.core.model.Member
import com.fragmind.camnode.core.model.NetworkKind
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.NodeRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BatteryReading(val percent: Int, val charging: Boolean) {
    val low: Boolean get() = percent <= LOW_BATTERY_PERCENT && !charging
}

data class HouseholdUiState(
    val householdName: String,
    val selfId: NodeId,
    /** Lowest battery first, so the phone to charge is on top; unknown batteries last. */
    val members: List<Member>,
    val batteries: Map<NodeId, BatteryReading>,
    /** False for households created before roles: anyone with the secret can do anything. */
    val secured: Boolean = true,
    val myRole: AccessRole = AccessRole.CAMERA,
    val roles: Map<NodeId, AccessRole> = emptyMap(),
) {
    val canManage: Boolean get() = myRole.can(Permission.MANAGE_HOUSEHOLD)
    val cameras: Int get() = members.count { it.role == NodeRole.CAMERA }
    val needCharging: List<Member> get() = members.filter { batteries[it.id]?.low == true }
}

@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val households: HouseholdRepository,
    private val probe: PeerProbe,
    private val battery: DeviceBattery,
    private val directorySync: DirectorySync,
) : ViewModel() {
    private val batteries = MutableStateFlow<Map<NodeId, BatteryReading>>(emptyMap())

    /** Battery polling runs only while the screen collects this state. */
    val uiState: StateFlow<HouseholdUiState?> = channelFlow {
        launch {
            while (true) {
                households.currentSession()?.let { batteries.value = readBatteries(it) }
                delay(BATTERY_REFRESH_MILLIS)
            }
        }
        combine(households.session, batteries) { session, readings ->
            session?.let {
                val sorted = it.directory.active.sortedWith(
                    compareBy<Member>({ m -> readings[m.id]?.percent ?: Int.MAX_VALUE }, { m -> m.name.lowercase() }),
                )
                HouseholdUiState(
                    householdName = it.household.name,
                    selfId = it.self.id,
                    members = sorted,
                    batteries = readings,
                    secured = it.household.secured,
                    myRole = it.role,
                    roles = sorted.associate { member -> member.id to it.roleOf(member) },
                )
            }
        }.collect(::send)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun readBatteries(session: HouseholdSession): Map<NodeId, BatteryReading> = coroutineScope {
        val level = battery.read()
        val self = level.percent?.let { session.self.id to BatteryReading(it, level.charging) }
        val peers = session.directory.active
            .filter { it.id != session.self.id && it.endpoints.isNotEmpty() }
            .map { member ->
                async {
                    val status = (probe.probe(member.endpoints, session.auth, member.id) as? PeerReachability.Online)?.status
                    status?.batteryPercent?.let { member.id to BatteryReading(it, status.charging) }
                }
            }
            .awaitAll()
        (peers + self).filterNotNull().toMap()
    }

    fun remove(id: NodeId) {
        viewModelScope.launch {
            households.removeMember(id)
            directorySync.sync()
        }
    }

    /** Signed with this phone's key; the change reaches every device on the next sync, started right away. */
    fun assignRole(id: NodeId, role: AccessRole) {
        viewModelScope.launch {
            if (households.assignRole(id, role)) directorySync.sync()
        }
    }

    /** Replaces a pre-roles household: this phone becomes the owner and the secret is new. */
    fun secureHousehold() {
        viewModelScope.launch {
            val session = households.currentSession() ?: return@launch
            households.create(session.household.name, session.self.name)
        }
    }

    fun rename(id: NodeId, name: String) {
        viewModelScope.launch {
            households.renameMember(id, name)
            directorySync.sync()
        }
    }

    fun leave(onLeft: () -> Unit) {
        viewModelScope.launch {
            households.leave()
            onLeft()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdRoute(onInvite: () -> Unit, onAppLock: () -> Unit, onLeft: () -> Unit, viewModel: HouseholdViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var pendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRename by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceName by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.household_title), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val household = state ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CamNodeCard {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(CamNodeIcons.Logo, size = 56)
                    Column(Modifier.weight(1f)) {
                        Text(household.householdName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            pluralStringResource(R.plurals.household_devices, household.members.size, household.members.size) + " · " +
                                pluralStringResource(R.plurals.household_cameras, household.cameras, household.cameras),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (household.canManage) CamNodeButton(stringResource(R.string.household_invite), onInvite, icon = CamNodeIcons.QrCode)
                RoleLine(household.myRole)
            }

            if (!household.secured) {
                NoticeCard(
                    CamNodeIcons.Lock,
                    stringResource(R.string.household_no_roles_title),
                    stringResource(R.string.household_no_roles_body),
                    StatusTone.Warning,
                    action = { TextButton(onClick = viewModel::secureHousehold) { Text(stringResource(R.string.household_secure)) } },
                )
            }

            if (household.needCharging.isNotEmpty()) {
                NoticeCard(
                    CamNodeIcons.BatteryAlert,
                    stringResource(R.string.household_charge_soon),
                    household.needCharging.joinToString { "${it.name} (${household.batteries[it.id]?.percent}%)" },
                    StatusTone.Warning,
                )
            }

            SectionHeader(stringResource(R.string.household_devices_header))
            CamNodeCard {
                household.members.forEachIndexed { index, member ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    val isSelf = member.id == household.selfId
                    DeviceRow(
                        member,
                        isSelf = isSelf,
                        battery = household.batteries[member.id],
                        role = household.roles[member.id].takeIf { household.secured },
                        manageable = household.canManage && !isSelf,
                        onRename = { pendingRename = member.id.value; deviceName = member.name }.takeIf { isSelf || household.canManage },
                        onRemove = { pendingRemoval = member.id.value },
                        onAssignRole = { role -> viewModel.assignRole(member.id, role) },
                    )
                }
            }
            Text(
                stringResource(R.string.household_battery_note) + " " +
                    stringResource(if (household.secured) R.string.household_secured_note else R.string.household_legacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionHeader(stringResource(R.string.household_this_phone))
            CamNodeCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                SettingRow(
                    CamNodeIcons.Lock,
                    stringResource(R.string.household_app_lock),
                    subtitle = stringResource(R.string.household_app_lock_detail),
                    onClick = onAppLock,
                    trailing = { Icon(CamNodeIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                LanguageRow()
            }
            CamNodeButton(stringResource(R.string.household_leave), { confirmLeave = true }, icon = CamNodeIcons.Logout, emphasis = ButtonEmphasis.Secondary)
        }
    }

    pendingRename?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.household_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.household_rename_body))
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text(stringResource(R.string.household_device_name)) },
                        singleLine = true,
                        isError = deviceName.isBlank(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deviceName.isNotBlank(),
                    onClick = { viewModel.rename(NodeId(id), deviceName); pendingRename = null },
                ) { Text(stringResource(R.string.household_save)) }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.household_cancel)) } },
        )
    }
    pendingRemoval?.let { id ->
        val name = state?.members?.firstOrNull { it.id.value == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.household_remove_title, name)) },
            text = { Text(stringResource(R.string.household_remove_body)) },
            confirmButton = { TextButton(onClick = { pendingRemoval = null; viewModel.remove(NodeId(id)) }) { Text(stringResource(R.string.household_remove)) } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.household_cancel)) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.household_leave_title)) },
            text = { Text(stringResource(R.string.household_leave_body)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; viewModel.leave(onLeft) }) { Text(stringResource(R.string.household_leave_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.household_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(
    member: Member,
    isSelf: Boolean,
    battery: BatteryReading?,
    role: AccessRole?,
    manageable: Boolean,
    onRename: (() -> Unit)?,
    onRemove: () -> Unit,
    onAssignRole: (AccessRole) -> Unit,
) {
    val camera = member.role == NodeRole.CAMERA
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(
            when (member.role) {
                NodeRole.CAMERA -> CamNodeIcons.Videocam
                NodeRole.RECORDER -> CamNodeIcons.Computer
                NodeRole.VIEWER -> CamNodeIcons.Smartphone
            },
            tint = if (camera) MaterialTheme.colorScheme.primary else CamNodeTheme.status.neutral,
            size = 40,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (isSelf) stringResource(R.string.household_self_name, member.name) else member.name, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                battery?.let {
                    val batteryDescription = stringResource(if (it.charging) R.string.household_battery_charging else R.string.household_battery_not_charging, it.percent)
                    MetricChip(
                        icon = when {
                            it.charging -> CamNodeIcons.BatteryCharging
                            it.low -> CamNodeIcons.BatteryAlert
                            else -> CamNodeIcons.Battery
                        },
                        text = "${it.percent}%",
                        modifier = Modifier.clearAndSetSemantics { contentDescription = batteryDescription },
                        tint = if (it.low) CamNodeTheme.status.warning else if (it.charging) CamNodeTheme.status.online else Color.Unspecified,
                    )
                }
                MetricChip(
                    when (member.role) {
                        NodeRole.CAMERA -> CamNodeIcons.Videocam
                        NodeRole.RECORDER -> CamNodeIcons.Folder
                        NodeRole.VIEWER -> CamNodeIcons.Visibility
                    },
                    when (member.role) {
                        NodeRole.CAMERA -> stringResource(R.string.household_node_camera)
                        NodeRole.RECORDER -> stringResource(R.string.household_node_recorder)
                        NodeRole.VIEWER -> stringResource(R.string.household_node_viewer)
                    },
                )
                role?.let { MetricChip(it.icon, it.label, tint = if (it == AccessRole.REVOKED) CamNodeTheme.status.warning else Color.Unspecified) }
                if (member.receivesAlerts) MetricChip(CamNodeIcons.Notifications, stringResource(R.string.household_master))
                val networks = member.endpoints.map { it.network }.toSet()
                if (NetworkKind.LAN in networks) MetricChip(CamNodeIcons.Wifi, "Wi-Fi")
                if (NetworkKind.TAILNET in networks) MetricChip(CamNodeIcons.Tailscale, "Tailscale")
            }
        }
        Column {
            onRename?.let { IconButton(onClick = it) { Icon(CamNodeIcons.Edit, contentDescription = stringResource(R.string.household_rename_device, member.name)) } }
            if (manageable) {
                if (role != null) RoleMenu(member.name, role, onAssignRole)
                IconButton(onClick = onRemove) { Icon(CamNodeIcons.Delete, contentDescription = stringResource(R.string.household_remove_device, member.name), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun RoleLine(role: AccessRole) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.household_your_role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricChip(role.icon, role.label)
    }
}

/** Administrators pick a device's role; the choice is signed and synced to the household. */
@Composable
private fun RoleMenu(deviceName: String, current: AccessRole, onAssign: (AccessRole) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(CamNodeIcons.Lock, contentDescription = stringResource(R.string.household_change_role, deviceName)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AccessRole.entries.forEach { role ->
                DropdownMenuItem(
                    text = { Text(role.label) },
                    leadingIcon = { Icon(role.icon, contentDescription = null) },
                    trailingIcon = { if (role == current) Icon(CamNodeIcons.Check, contentDescription = stringResource(R.string.household_current)) },
                    onClick = {
                        open = false
                        if (role != current) onAssign(role)
                    },
                )
            }
        }
    }
}

private val AccessRole.label: String
    @Composable get() = stringResource(
        when (this) {
            AccessRole.ADMIN -> R.string.household_role_admin
            AccessRole.VIEWER -> R.string.household_role_viewer
            AccessRole.RECORDER -> R.string.household_role_recorder
            AccessRole.CAMERA -> R.string.household_role_camera
            AccessRole.REVOKED -> R.string.household_role_revoked
        },
    )

private val AccessRole.icon: ImageVector
    @Composable get() = when (this) {
        AccessRole.ADMIN -> CamNodeIcons.Shield
        AccessRole.VIEWER -> CamNodeIcons.Visibility
        AccessRole.RECORDER -> CamNodeIcons.Folder
        AccessRole.CAMERA -> CamNodeIcons.Videocam
        AccessRole.REVOKED -> CamNodeIcons.Lock
    }

private const val LOW_BATTERY_PERCENT = 20
private const val BATTERY_REFRESH_MILLIS = 30_000L
