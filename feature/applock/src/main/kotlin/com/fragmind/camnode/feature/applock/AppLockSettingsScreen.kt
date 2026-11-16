package com.fragmind.camnode.feature.applock

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fragmind.camnode.core.designsystem.ButtonEmphasis
import com.fragmind.camnode.core.designsystem.CamNodeButton
import com.fragmind.camnode.core.designsystem.CamNodeCard
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.NoticeCard
import com.fragmind.camnode.core.designsystem.SectionHeader
import com.fragmind.camnode.core.designsystem.SettingRow
import com.fragmind.camnode.core.designsystem.StatusTone
import com.fragmind.camnode.core.model.AppDisguise
import com.fragmind.camnode.core.model.PinCheck

/** What the PIN dialog is asking for. */
private enum class PinStep { NewPin, ConfirmPin, ForDisguise, ForDisable }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettingsRoute(onBack: () -> Unit, viewModel: AppLockSettingsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    var selected by rememberSaveable { mutableStateOf<AppDisguise?>(null) }
    var selectedStyle by rememberSaveable { mutableStateOf<Int?>(null) }
    var step by rememberSaveable { mutableStateOf<PinStep?>(null) }
    var firstPin by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val current = config ?: return
    LaunchedEffect(current) {
        if (selected == null) selected = current.disguise
        if (selectedStyle == null) selectedStyle = current.iconStyle
    }
    val choice = selected ?: current.disguise
    val style = (selectedStyle ?: current.iconStyle).coerceIn(0, choice.iconStyles - 1)
    val title = stringResource(choice.titleRes())
    val rule = stringResource(choice.pinRuleRes())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.applock_settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(CamNodeIcons.Back, contentDescription = stringResource(R.string.applock_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CamNodeCard {
                SettingRow(
                    CamNodeIcons.Lock,
                    stringResource(if (current.enabled) R.string.applock_on else R.string.applock_off),
                    subtitle = stringResource(if (current.enabled) R.string.applock_on_detail else R.string.applock_off_detail),
                    iconTint = if (current.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(stringResource(R.string.applock_look_header))
            CamNodeCard(Modifier.selectableGroup()) {
                AppDisguise.entries.forEachIndexed { index, disguise ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    DisguiseOption(disguise, selected = disguise == choice, onSelect = {
                        selected = disguise
                        selectedStyle = if (disguise == current.disguise) current.iconStyle else 0
                        message = null
                    })
                }
            }
            if (choice.iconStyles > 1) {
                SectionHeader(stringResource(R.string.applock_icon_header))
                IconStylePicker(choice, style, onSelect = { selectedStyle = it; message = null })
            }
            NoticeCard(
                CamNodeIcons.Videocam,
                stringResource(R.string.applock_camera_visible_title),
                stringResource(R.string.applock_camera_visible_body),
                StatusTone.Neutral,
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            if (!current.enabled) {
                CamNodeButton(stringResource(R.string.applock_enable), { message = null; step = PinStep.NewPin }, icon = CamNodeIcons.Lock)
            } else {
                CamNodeButton(
                    stringResource(R.string.applock_apply),
                    { message = null; step = PinStep.ForDisguise },
                    icon = CamNodeIcons.Check,
                    enabled = choice != current.disguise || style != current.iconStyle,
                )
                CamNodeButton(stringResource(R.string.applock_disable), { message = null; step = PinStep.ForDisable }, emphasis = ButtonEmphasis.Secondary)
            }
        }
    }

    step?.let { active ->
        PinDialog(
            title = stringResource(
                when (active) {
                    PinStep.NewPin -> R.string.applock_pin_choose
                    PinStep.ConfirmPin -> R.string.applock_pin_repeat
                    PinStep.ForDisguise, PinStep.ForDisable -> R.string.applock_lock_title
                },
            ),
            hint = when (active) {
                PinStep.NewPin, PinStep.ForDisguise -> rule
                PinStep.ConfirmPin -> stringResource(R.string.applock_pin_repeat_hint)
                PinStep.ForDisable -> stringResource(R.string.applock_pin_disable_hint)
            },
            onDismiss = { step = null },
            onPin = { pin ->
                when (active) {
                    PinStep.NewPin -> if (choice.accepts(pin)) {
                        firstPin = pin
                        step = PinStep.ConfirmPin
                    } else {
                        message = resources.getString(R.string.applock_error_not_enterable, title, rule)
                        step = null
                    }
                    PinStep.ConfirmPin -> {
                        step = null
                        if (pin != firstPin) message = resources.getString(R.string.applock_error_mismatch)
                        else viewModel.enable(pin, choice, style) { ok -> if (!ok) message = resources.getString(R.string.applock_error_unusable, title) }
                        firstPin = ""
                    }
                    PinStep.ForDisguise -> {
                        step = null
                        if (!choice.accepts(pin)) message = resources.getString(R.string.applock_error_current_pin, title, rule)
                        else viewModel.changeDisguise(pin, choice, style) { result -> message = result.failure(context) }
                    }
                    PinStep.ForDisable -> {
                        step = null
                        viewModel.disable(pin) { result ->
                            message = result.failure(context)
                            if (result == PinCheck.Granted) {
                                selected = AppDisguise.NONE
                                selectedStyle = 0
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun DisguiseOption(disguise: AppDisguise, selected: Boolean, onSelect: () -> Unit) {
    SettingRow(
        disguise.icon(),
        stringResource(disguise.titleRes()),
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        subtitle = stringResource(disguise.howToOpenRes()),
        trailing = { RadioButton(selected = selected, onClick = null) },
    )
}

/** The launcher icons themselves, read from the activity-aliases, so the preview is exact. */
@Composable
private fun IconStylePicker(disguise: AppDisguise, selectedStyle: Int, onSelect: (Int) -> Unit) {
    val context = LocalContext.current
    val icons = remember(disguise) { List(disguise.iconStyles) { context.launcherIcon(LauncherEntry(disguise, it)) } }
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        icons.forEachIndexed { index, icon ->
            val shape = RoundedCornerShape(22.dp)
            val description = stringResource(R.string.applock_icon_style, index + 1)
            val ring = if (index == selectedStyle) MaterialTheme.colorScheme.primary else Color.Transparent
            Column(
                Modifier
                    .clip(shape)
                    .selectable(selected = index == selectedStyle, onClick = { onSelect(index) }, role = Role.RadioButton)
                    .border(3.dp, ring, shape)
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) Image(icon, contentDescription = description, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
                else Text(description)
            }
        }
    }
}

private fun Context.launcherIcon(entry: LauncherEntry): ImageBitmap? = runCatching {
    @Suppress("DEPRECATION") // The flags overload is API 33+; minSdk is 28.
    val info = packageManager.getActivityInfo(entry.component(this), PackageManager.MATCH_DISABLED_COMPONENTS)
    info.loadIcon(packageManager).toBitmap(192, 192).asImageBitmap()
}.getOrNull()

@Composable
private fun PinDialog(title: String, hint: String, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by rememberSaveable(title) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                PinPad(pin, onPinChange = { pin = it }, onComplete = { onPin(it); pin = "" })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.applock_cancel)) } },
    )
}

private fun PinCheck.failure(context: Context): String? = if (this == PinCheck.Granted) null else describe(context)

@StringRes
private fun AppDisguise.titleRes(): Int = when (this) {
    AppDisguise.NONE -> R.string.applock_disguise_none
    AppDisguise.CALCULATOR -> R.string.disguise_calculator
    AppDisguise.CLOCK -> R.string.disguise_clock
    AppDisguise.CALENDAR -> R.string.disguise_calendar
    AppDisguise.NOTES -> R.string.disguise_notes
}

@StringRes
private fun AppDisguise.howToOpenRes(): Int = when (this) {
    AppDisguise.NONE -> R.string.applock_howto_none
    AppDisguise.CALCULATOR -> R.string.applock_howto_calculator
    AppDisguise.CLOCK -> R.string.applock_howto_clock
    AppDisguise.CALENDAR -> R.string.applock_howto_calendar
    AppDisguise.NOTES -> R.string.applock_howto_notes
}

@StringRes
private fun AppDisguise.pinRuleRes(): Int = when (this) {
    AppDisguise.CLOCK -> R.string.applock_rule_clock
    AppDisguise.CALENDAR -> R.string.applock_rule_calendar
    else -> R.string.applock_rule_any
}

@Composable
private fun AppDisguise.icon(): ImageVector = when (this) {
    AppDisguise.NONE -> CamNodeIcons.Dialpad
    AppDisguise.CALCULATOR -> CamNodeIcons.Calculator
    AppDisguise.CLOCK -> CamNodeIcons.Clock
    AppDisguise.CALENDAR -> CamNodeIcons.Calendar
    AppDisguise.NOTES -> CamNodeIcons.Note
}
