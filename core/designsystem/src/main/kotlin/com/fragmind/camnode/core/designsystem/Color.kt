package com.fragmind.camnode.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Monochrome surfaces derived from the logo; hue is reserved for status.
 * Night mirrors res/values/brand_colors.xml (checked by BrandColorsTest).
 */
internal object Palette {
    // Brand, from the logo.
    val Paper = Color(0xFFEDE9E4)
    val Charcoal = Color(0xFF262624)
    val Night = Color(0xFF161614)

    // Warm neutral ramp around the brand charcoal.
    val Ink = Color(0xFF0F0F0E)
    val Panel = Color(0xFF1E1E1C)
    val PanelHigh = Charcoal
    val PanelHighest = Color(0xFF302F2C)
    val Line = Color(0xFF3A3936)
    val TextSecondary = Color(0xFFA19E99)

    // Status hues, the only color in the UI.
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
