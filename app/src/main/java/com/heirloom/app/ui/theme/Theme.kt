package com.heirloom.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val HeirloomColorScheme = lightColorScheme(
    primary = Brass,
    onPrimary = Cream,
    primaryContainer = BrassDeep,
    onPrimaryContainer = Cream,

    secondary = Oxblood,
    onSecondary = Cream,

    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = CreamDeep,
    onSurfaceVariant = InkSoft,

    error = Oxblood,
    onError = Cream,

    outline = InkSoft,
)

@Composable
fun HeirloomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HeirloomColorScheme,
        typography = HeirloomTypography,
        content = content,
    )
}
