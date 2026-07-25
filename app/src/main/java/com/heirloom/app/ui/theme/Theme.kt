package com.heirloom.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightArchiveScheme = lightColorScheme(
    primary = ArchiveBrass,
    onPrimary = ArchiveSurface,
    primaryContainer = ArchiveBrassSoft,
    onPrimaryContainer = ArchiveInk,
    secondary = ArchiveSage,
    onSecondary = ArchiveSurface,
    secondaryContainer = ColorTokens.SageSoft,
    onSecondaryContainer = ArchiveInk,
    background = ArchivePaper,
    onBackground = ArchiveInk,
    surface = ArchiveSurface,
    onSurface = ArchiveInk,
    surfaceVariant = ArchiveSurfaceQuiet,
    onSurfaceVariant = ArchiveMutedInk,
    error = ArchiveBurgundy,
    onError = ArchiveSurface,
    errorContainer = ColorTokens.BurgundySoft,
    onErrorContainer = ArchiveBurgundy,
    outline = ArchiveRule,
    outlineVariant = ColorTokens.RuleSoft,
)

private val DarkArchiveScheme = darkColorScheme(
    primary = DarkArchiveBrass,
    onPrimary = DarkArchivePaper,
    primaryContainer = DarkArchiveBrassSoft,
    onPrimaryContainer = DarkArchiveInk,
    secondary = DarkArchiveSage,
    onSecondary = DarkArchivePaper,
    secondaryContainer = ColorTokens.DarkSageSoft,
    onSecondaryContainer = DarkArchiveInk,
    background = DarkArchivePaper,
    onBackground = DarkArchiveInk,
    surface = DarkArchiveSurface,
    onSurface = DarkArchiveInk,
    surfaceVariant = DarkArchiveSurfaceQuiet,
    onSurfaceVariant = DarkArchiveMutedInk,
    error = DarkArchiveBurgundy,
    onError = DarkArchivePaper,
    errorContainer = ColorTokens.DarkBurgundySoft,
    onErrorContainer = DarkArchiveBurgundy,
    outline = DarkArchiveRule,
    outlineVariant = ColorTokens.DarkRuleSoft,
)

private object ColorTokens {
    val SageSoft = androidx.compose.ui.graphics.Color(0xFFDDE6DC)
    val BurgundySoft = androidx.compose.ui.graphics.Color(0xFFF2DEDA)
    val RuleSoft = androidx.compose.ui.graphics.Color(0xFFE9E1D5)
    val DarkSageSoft = androidx.compose.ui.graphics.Color(0xFF28352B)
    val DarkBurgundySoft = androidx.compose.ui.graphics.Color(0xFF442723)
    val DarkRuleSoft = androidx.compose.ui.graphics.Color(0xFF373229)
}

private val HeirloomShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

@Composable
fun HeirloomTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkArchiveScheme else LightArchiveScheme,
        typography = HeirloomTypography,
        shapes = HeirloomShapes,
        content = content,
    )
}
