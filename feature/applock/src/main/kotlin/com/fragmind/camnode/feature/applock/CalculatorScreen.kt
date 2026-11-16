package com.fragmind.camnode.feature.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.feature.applock.CalculatorEngine.DIVIDE
import com.fragmind.camnode.feature.applock.CalculatorEngine.MINUS
import com.fragmind.camnode.feature.applock.CalculatorEngine.MULTIPLY
import com.fragmind.camnode.feature.applock.CalculatorEngine.PLUS

private val KEY_ROWS = listOf(
    listOf('C', '<', '%', DIVIDE),
    listOf('7', '8', '9', MULTIPLY),
    listOf('4', '5', '6', MINUS),
    listOf('1', '2', '3', PLUS),
    listOf('0', '.', '='),
)

/** A working calculator. Typing the PIN and pressing "=" opens CamNode. */
@Composable
internal fun CalculatorDisguise(onPinCandidate: (String) -> Unit) {
    // MainActivity handles rotation itself, so plain remember survives it.
    var state by remember { mutableStateOf(CalculatorState()) }
    fun press(key: Char) {
        if (key == '=') CalculatorEngine.pinCandidate(state)?.let(onPinCandidate)
        state = CalculatorEngine.press(state, key)
    }

    // Calculators keep the 7-8-9 keypad and right-aligned numbers in right-to-left languages too.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { CalculatorBody(state, ::press) }
}

@Composable
private fun CalculatorBody(state: CalculatorState, press: (Char) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.weight(1f))
            state.operator?.let { operator ->
                Text(
                    "${state.accumulator?.toPlainString().orEmpty()} $operator",
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
            Text(
                state.display,
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            KEY_ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(0.32f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key -> CalculatorKey(key, Modifier.weight(if (key == '0') 2f else 1f).fillMaxSize()) { press(key) } }
                }
            }
        }
    }
}

@Composable
private fun CalculatorKey(key: Char, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.extraLarge
    when (key) {
        '=' -> Button(onClick, modifier, shape = shape) { Text("=", style = MaterialTheme.typography.headlineMedium) }
        DIVIDE, MULTIPLY, MINUS, PLUS -> FilledTonalButton(
            onClick, modifier, shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) { Text(key.toString(), style = MaterialTheme.typography.headlineMedium) }
        '<' -> TextButton(onClick, modifier, shape = shape) { Icon(CamNodeIcons.Backspace, contentDescription = stringResource(R.string.applock_delete)) }
        'C', '%' -> TextButton(onClick, modifier, shape = shape) { Text(if (key == 'C') "AC" else "%", style = MaterialTheme.typography.headlineSmall) }
        else -> FilledTonalButton(
            onClick, modifier, shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) { Text(key.toString(), style = MaterialTheme.typography.headlineMedium) }
    }
}
