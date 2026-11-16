package com.fragmind.camnode.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CamNodeColorScheme = darkColorScheme(
    primary = Palette.Signal,
    onPrimary = Color.White,
    primaryContainer = Palette.SignalDeep,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.Mint,
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.SignalDeep,
    onSecondaryContainer = Palette.TextPrimary,
    tertiary = Palette.Amber,
    tertiaryContainer = Color(0xFF3D2C0E),
    onTertiaryContainer = Palette.TextPrimary,
    error = Palette.Siren,
    onError = Color.White,
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Palette.Night,
    onBackground = Palette.TextPrimary,
    surface = Palette.Night,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.PanelHigh,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Palette.PanelHigh,
    surfaceContainerHighest = Palette.PanelHighest,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    inverseSurface = Palette.TextPrimary,
    inverseOnSurface = Palette.Ink,
)

private val base = Typography()

private val CamNodeTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
)

private val CamNodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * CamNode is dark by design: it is a monitoring console that often runs at night,
 * and a fixed palette keeps the brand identical on every phone of the household.
 */
@Composable
fun CamNodeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides DarkStatusColors) {
        MaterialTheme(
            colorScheme = CamNodeColorScheme,
            typography = CamNodeTypography,
            shapes = CamNodeShapes,
            content = content,
        )
    }
}

object CamNodeTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}
