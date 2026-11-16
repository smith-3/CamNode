package com.fragmind.camnode.feature.household

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fragmind.camnode.core.designsystem.ButtonEmphasis
import com.fragmind.camnode.core.designsystem.CamNodeButton
import com.fragmind.camnode.core.designsystem.CamNodeCard
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.IconTile
import com.fragmind.camnode.core.designsystem.NoticeCard
import com.fragmind.camnode.core.designsystem.StatusTone
import androidx.compose.material3.TextButton
import com.fragmind.camnode.core.designsystem.SettingRow
import com.fragmind.camnode.core.model.InviteCodec
import com.fragmind.camnode.core.model.InviteParseResult
import com.fragmind.camnode.core.network.LocalNetworkPermission

private val defaultDeviceName: String
    get() = "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onJoin: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        IconTile(CamNodeIcons.Logo, size = 72)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CamNode", style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.household_welcome_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Feature(CamNodeIcons.Lock, stringResource(R.string.household_feature_private_title), stringResource(R.string.household_feature_private_body))
            Feature(CamNodeIcons.Wifi, stringResource(R.string.household_feature_discovery_title), stringResource(R.string.household_feature_discovery_body))
            Feature(CamNodeIcons.Bolt, stringResource(R.string.household_feature_always_on_title), stringResource(R.string.household_feature_always_on_body))
        }
        Spacer(Modifier.weight(1f))
        CamNodeButton(stringResource(R.string.household_create_mine), onCreate, icon = CamNodeIcons.Add)
        CamNodeButton(stringResource(R.string.household_join_with_invite), onJoin, icon = CamNodeIcons.QrScanner, emphasis = ButtonEmphasis.Secondary)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(icon, size = 40)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CreateHouseholdRoute(onBack: () -> Unit, onDone: () -> Unit, viewModel: HouseholdSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val defaultHouseholdName = stringResource(R.string.household_default_name)
    var householdName by rememberSaveable { mutableStateOf(defaultHouseholdName) }
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceName) }
    LaunchedEffect(state) { if (state == SetupState.Done) onDone() }

    FlowScaffold(stringResource(R.string.household_create), onBack) {
        Text(
            stringResource(R.string.household_create_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CamNodeCard {
            OutlinedTextField(householdName, { householdName = it }, label = { Text(stringResource(R.string.household_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deviceName, { deviceName = it }, label = { Text(stringResource(R.string.household_device_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        SetupFeedback(state)
        CamNodeButton(stringResource(R.string.household_create), { viewModel.create(householdName, deviceName) }, enabled = state != SetupState.Working, icon = CamNodeIcons.Logo)
    }
}

@Composable
fun JoinHouseholdRoute(
    prefilledLink: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: HouseholdSetupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf(prefilledLink.orEmpty()) }
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceName) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var cameraUnavailable by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> scanning = granted }
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        LocalNetworkPermission.runtimePermissions.takeIf { it.isNotEmpty() }?.let { localNetwork.launch(it.toTypedArray()) }
    }
    LaunchedEffect(state) { if (state == SetupState.Done) onDone() }

    FlowScaffold(stringResource(R.string.household_join_title), onBack) {
        Text(
            stringResource(R.string.household_join_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val invite = (InviteCodec.decode(link) as? InviteParseResult.Valid)?.invite
        if (invite != null && !scanning) {
            CamNodeCard {
                SettingRow(
                    icon = CamNodeIcons.Logo,
                    title = stringResource(R.string.household_join_invite_to, invite.household.name),
                    subtitle = if (invite.seeds.isEmpty()) stringResource(R.string.household_join_no_seeds) else pluralStringResource(R.plurals.household_join_seeds, invite.seeds.size, invite.seeds.size),
                    trailing = { TextButton(onClick = { link = "" }) { Text(stringResource(R.string.household_join_change)) } },
                )
                OutlinedTextField(deviceName, { deviceName = it }, label = { Text(stringResource(R.string.household_device_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            SetupFeedback(state)
            CamNodeButton(
                stringResource(R.string.household_join_named, invite.household.name),
                onClick = { viewModel.join(link, deviceName) },
                enabled = state != SetupState.Working,
                icon = CamNodeIcons.Link,
            )
            return@FlowScaffold
        }
        ScannerFrame(scanning) {
            if (scanning) {
                QrScanner(
                    onScanned = { scanned ->
                        link = scanned
                        scanning = false
                        viewModel.join(scanned, deviceName)
                    },
                    onCameraUnavailable = {
                        scanning = false
                        cameraUnavailable = true
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconTile(CamNodeIcons.QrScanner, size = 64)
                    CamNodeButton(
                        stringResource(R.string.household_join_scan),
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            cameraUnavailable = false
                            if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        }
        if (cameraUnavailable) {
            NoticeCard(
                CamNodeIcons.Error,
                stringResource(R.string.household_join_camera_error_title),
                stringResource(R.string.household_join_camera_error_body),
                StatusTone.Warning,
            )
        }
        CamNodeCard {
            OutlinedTextField(link, { link = it; viewModel.dismissError() }, label = { Text(stringResource(R.string.household_join_paste)) }, modifier = Modifier.fillMaxWidth())
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            OutlinedTextField(deviceName, { deviceName = it }, label = { Text(stringResource(R.string.household_device_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        SetupFeedback(state)
        CamNodeButton(
            stringResource(R.string.household_join),
            onClick = { viewModel.join(link, deviceName) },
            enabled = state != SetupState.Working && link.isNotBlank(),
            icon = CamNodeIcons.Link,
        )
    }
}

@Composable
private fun ScannerFrame(active: Boolean, content: @Composable () -> Unit) {
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(2.dp, borderColor, MaterialTheme.shapes.extraLarge),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SetupFeedback(state: SetupState) {
    when (state) {
        is SetupState.Failed -> NoticeCard(CamNodeIcons.Error, stringResource(R.string.household_failed), stringResource(state.message), StatusTone.Live)
        SetupState.Working -> NoticeCard(CamNodeIcons.Devices, stringResource(R.string.household_connecting_title), stringResource(R.string.household_connecting_body), StatusTone.Neutral)
        SetupState.Editing, SetupState.Done -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlowScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(CamNodeIcons.Back, contentDescription = stringResource(R.string.household_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
