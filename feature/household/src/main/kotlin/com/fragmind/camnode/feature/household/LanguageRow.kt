package com.fragmind.camnode.feature.household

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.fragmind.camnode.core.data.locale.AppLanguage
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.SettingRow

/** Picks the app language; each option is written in its own language. */
@Composable
internal fun LanguageRow() {
    val context = LocalContext.current
    // Not saveable: picking a language recreates the activity, which must not reopen the list.
    var open by remember { mutableStateOf(false) }
    val current = remember(open) { AppLanguage.current(context) }
    val systemLabel = stringResource(R.string.household_language_system)
    SettingRow(
        CamNodeIcons.Language,
        stringResource(R.string.household_language),
        subtitle = if (current.isEmpty()) systemLabel else AppLanguage.nativeName(current),
        onClick = { open = true },
        trailing = { Icon(CamNodeIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.household_language)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                (listOf("") + AppLanguage.SUPPORTED).forEach { tag ->
                    val selected = tag == current
                    ListItem(
                        headlineContent = { Text(if (tag.isEmpty()) systemLabel else AppLanguage.nativeName(tag)) },
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = {
                            open = false
                            if (!selected) context.applyLanguage(tag)
                        }),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.household_cancel)) } },
    )
}

/** Android 13+ recreates the activity itself; before that the activity is recreated here. */
private fun Context.applyLanguage(tag: String) {
    AppLanguage.set(this, tag)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) findActivity()?.recreate()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
