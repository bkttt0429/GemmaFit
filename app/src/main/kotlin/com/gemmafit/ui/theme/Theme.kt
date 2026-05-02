package com.gemmafit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Background,
    secondary = Blue,
    onSecondary = TextPrimary,
    error = Red,
    onError = TextPrimary,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = OverlayPanel,
    onSurfaceVariant = TextSecondary,
    outline = TextHint,
)

@Composable
fun GemmaFitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = GemmaFitTypography,
        content = content
    )
}
