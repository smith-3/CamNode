package com.fragmind.camnode.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal object Palette {
    val Ink = Color(0xFF080B10)
    val Night = Color(0xFF0E131A)
    val Panel = Color(0xFF151C25)
    val PanelHigh = Color(0xFF1C2531)
    val PanelHighest = Color(0xFF243040)
    val Line = Color(0xFF2B3747)
    val TextPrimary = Color(0xFFE7EDF4)
    val TextSecondary = Color(0xFF93A1B2)
    val Signal = Color(0xFF4C8DFF)
    val SignalDeep = Color(0xFF1B3D7A)
    val Mint = Color(0xFF2FD38A)
    val Amber = Color(0xFFFFB547)
    val Siren = Color(0xFFFF4D4F)
}

/** Status colors Material's scheme has no slot for. */
@Immutable
data class StatusColors(
    val live: Color,
    val online: Color,
    val warning: Color,
    val offline: Color,
    val neutral: Color,
)

internal val DarkStatusColors = StatusColors(
    live = Palette.Siren,
    online = Palette.Mint,
    warning = Palette.Amber,
    offline = Palette.TextSecondary,
    neutral = Palette.TextSecondary,
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }
