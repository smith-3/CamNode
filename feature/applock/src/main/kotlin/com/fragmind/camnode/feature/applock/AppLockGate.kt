package com.fragmind.camnode.feature.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.IconTile
import com.fragmind.camnode.core.model.AppDisguise
import com.fragmind.camnode.core.model.PinCheck
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * Shows [content] only after the PIN is entered. While locked, the chosen disguise is a
 * working app; nothing of CamNode is composed until the PIN is right.
 */
@Composable
fun AppLockGate(viewModel: AppLockViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val gate = state) {
        GateState.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        GateState.Open -> content()
        is GateState.Locked -> {
            val tryPin: (String, () -> Unit) -> Unit = { pin, onDenied -> viewModel.tryPin(pin, onDenied) }
            when (gate.disguise) {
                AppDisguise.NONE -> PinLockScreen(viewModel)
                AppDisguise.CALCULATOR -> CalculatorDisguise(onPinCandidate = { viewModel.tryPin(it) })
                AppDisguise.CLOCK -> ClockDisguise(tryPin)
                AppDisguise.CALENDAR -> CalendarDisguise(tryPin)
                AppDisguise.NOTES -> NotesDisguise(tryPin)
            }
        }
    }
}

@Composable
private fun PinLockScreen(viewModel: AppLockViewModel) {
    val lastCheck by viewModel.lastCheck.collectAsStateWithLifecycle()
    var pin by rememberSaveable { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconTile(CamNodeIcons.Lock, size = 64)
            Text(stringResource(R.string.applock_lock_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                lastCheck.describe(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                color = if (lastCheck is PinCheck.Granted || lastCheck == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            PinPad(pin, onPinChange = { pin = it }, onComplete = { viewModel.tryPin(it); pin = "" })
        }
    }
}

internal fun PinCheck?.describe(context: Context): String = when (this) {
    null, PinCheck.Granted -> context.getString(R.string.applock_lock_info)
    is PinCheck.Denied -> context.resources.getQuantityString(R.plurals.applock_wrong_pin, attemptsBeforeLockout, attemptsBeforeLockout)
    is PinCheck.LockedOut -> context.getString(
        R.string.applock_locked_out,
        Instant.ofEpochMilli(untilMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)),
    )
}
