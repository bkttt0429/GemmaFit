package com.gemmafit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// CompositionLocal to force theme override if needed
val LocalDarkTheme = staticCompositionLocalOf { true }

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = DarkBackground,
    secondary = Blue,
    onSecondary = DarkTextPrimary,
    error = Red,
    onError = DarkTextPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkOverlayPanel,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkTextHint,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00C853), // Slightly darker green for light bg
    onPrimary = Color.White,
    secondary = Blue,
    onSecondary = LightTextPrimary,
    error = Red,
    onError = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightOverlayPanel,
    onSurfaceVariant = LightTextSecondary,
    outline = LightTextHint,
)

@Composable
fun GemmaFitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Set global theme state for dynamic color aliases
    IsDarkTheme = darkTheme

    val colorScheme = if (darkTheme) DarkColors else LightColors

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GemmaFitTypography,
            content = content,
        )
    }
}
