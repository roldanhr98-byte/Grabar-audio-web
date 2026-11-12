package com.audiovault.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AvColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF161B22)
    val SurfaceHigh = Color(0xFF1F2630)
    val Mint = Color(0xFF2EE6A6)
    val Blue = Color(0xFF7C9CFF)
    val Red = Color(0xFFFF4D6D)
    val Amber = Color(0xFFFFC857)
    val Muted = Color(0xFF8B96A5)
    val Text = Color(0xFFE6EDF3)
}

private val scheme = darkColorScheme(
    primary = AvColors.Mint,
    onPrimary = Color(0xFF00281A),
    primaryContainer = Color(0xFF0F3D2E),
    onPrimaryContainer = AvColors.Mint,
    secondary = AvColors.Blue,
    onSecondary = Color(0xFF0A1440),
    secondaryContainer = Color(0xFF232C4D),
    onSecondaryContainer = Color(0xFFD5DEFF),
    tertiary = AvColors.Amber,
    background = AvColors.Background,
    onBackground = AvColors.Text,
    surface = AvColors.Surface,
    onSurface = AvColors.Text,
    surfaceVariant = AvColors.SurfaceHigh,
    onSurfaceVariant = Color(0xFFB4BFCC),
    surfaceContainer = AvColors.Surface,
    surfaceContainerHigh = AvColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF28303B),
    surfaceContainerLow = Color(0xFF12171D),
    outline = Color(0xFF3A4452),
    outlineVariant = Color(0xFF2A323D),
    error = AvColors.Red,
)

@Composable
fun AudioVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
