package com.fragmind.camnode.feature.applock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.model.AppPin

/** Four dots and a numeric keypad; calls [onComplete] once four digits are typed. */
@Composable
internal fun PinPad(pin: String, onPinChange: (String) -> Unit, onComplete: (String) -> Unit, modifier: Modifier = Modifier) {
    val progress = stringResource(R.string.applock_pin_progress, pin.length, AppPin.LENGTH)
    Column(modifier.widthIn(max = 320.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            Modifier.semantics { contentDescription = progress },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(AppPin.LENGTH) { index ->
                val filled = index < pin.length
                Box(
                    Modifier.size(16.dp).background(
                        if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        CircleShape,
                    ),
                )
            }
        }
        // Number pads keep their left-to-right order in right-to-left languages.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Keys(pin, onPinChange, onComplete)
        }
    }
}

@Composable
private fun Keys(pin: String, onPinChange: (String) -> Unit, onComplete: (String) -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<"))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    val keyModifier = Modifier.weight(1f).aspectRatio(1.4f)
                    when (key) {
                        "" -> Box(keyModifier)
                        "<" -> IconButton(onClick = { onPinChange(pin.dropLast(1)) }, modifier = keyModifier, enabled = pin.isNotEmpty()) {
                            Icon(CamNodeIcons.Backspace, contentDescription = stringResource(R.string.applock_delete))
                        }
                        else -> FilledTonalButton(
                            onClick = {
                                if (pin.length < AppPin.LENGTH) {
                                    val next = pin + key
                                    onPinChange(next)
                                    if (next.length == AppPin.LENGTH) onComplete(next)
                                }
                            },
                            modifier = keyModifier,
                        ) { Text(key, style = MaterialTheme.typography.headlineSmall) }
                    }
                }
            }
        }
    }
}
