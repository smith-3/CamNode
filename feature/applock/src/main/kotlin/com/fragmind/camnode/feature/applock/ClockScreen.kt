package com.fragmind.camnode.feature.applock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.IconButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.model.AppPin
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource

/**
 * A clock that sets real alarms through the system clock app. An alarm at HH:MM equal to
 * the PIN opens CamNode instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClockDisguise(onPinCandidate: (String, onDenied: () -> Unit) -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    var picking by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                icon = { Icon(CamNodeIcons.AlarmAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.applock_clock_new_alarm)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            val locale = LocalConfiguration.current.locales[0]
            Text(now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)), style = MaterialTheme.typography.displayLarge)
            Text(
                now.format(bestPattern(locale, "EEEEdMMMM")).replaceFirstChar { it.titlecase(locale) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picking) {
        val pickerState = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = DateFormat.is24HourFormat(context))
        // The dial snaps to 5 minutes on tap; typing is the precise way, as in real clock apps.
        var typing by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.applock_clock_new_alarm)) },
            text = { if (typing) TimeInput(pickerState) else TimePicker(pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    val hour = pickerState.hour
                    val minute = pickerState.minute
                    onPinCandidate(AppPin.fromTime(hour, minute)) { context.setSystemAlarm(hour, minute) }
                }) { Text(stringResource(R.string.applock_save)) }
            },
            dismissButton = {
                Row {
                    IconButton(onClick = { typing = !typing }) {
                        Icon(if (typing) CamNodeIcons.Clock else CamNodeIcons.Keyboard, contentDescription = stringResource(if (typing) R.string.applock_clock_use_dial else R.string.applock_clock_type_time))
                    }
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.applock_cancel)) }
                }
            },
        )
    }
}

private fun Context.setSystemAlarm(hour: Int, minute: Int) {
    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.applock_clock_no_app, Toast.LENGTH_SHORT).show()
    }
}

/** The locale's own order and words for a date made of [skeleton] fields. */
internal fun bestPattern(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
