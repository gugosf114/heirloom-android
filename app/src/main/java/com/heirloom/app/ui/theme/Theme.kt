package com.heirloom.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HeirloomColorScheme = darkColorScheme(
    primary = LavAmberText,
    onPrimary = LavBackground,
    primaryContainer = LavPanelSurface,
    onPrimaryContainer = LavAmberText,

    secondary = LavCrimsonRed,
    onSecondary = LavBackground,

    background = LavBackground,
    onBackground = LavAmberText,
    surface = LavPanelSurface,
    onSurface = LavAmberText,
    surfaceVariant = LavPanelSurface,
    onSurfaceVariant = LavMutedText,

    error = LavCrimsonRed,
    onError = LavBackground,

    outline = LavBorder,
)

@Composable
fun HeirloomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HeirloomColorScheme,
        typography = LavTypography,
        content = content,
    )
}