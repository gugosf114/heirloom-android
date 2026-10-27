package com.heirloom.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val GalleryDark = darkColorScheme(
    primary = Color(0xFFE4C99C), onPrimary = Color(0xFF172427),
    primaryContainer = Color(0xFF2F3937), onPrimaryContainer = Color(0xFFF5ECDE),
    secondary = Color(0xFFB5C7B7), onSecondary = Color(0xFF16231D),
    secondaryContainer = Color(0xFF20332C), onSecondaryContainer = Color(0xFFD3E1D4),
    background = Color(0xFF0C171B), onBackground = Color(0xFFF4EFE6),
    surface = Color(0xFF152328), onSurface = Color(0xFFF4EFE6),
    surfaceVariant = Color(0xFF1D2E33), onSurfaceVariant = Color(0xFFACB9B9),
    surfaceContainer = Color(0xFF152328), surfaceContainerHigh = Color(0xFF1D2E33),
    surfaceContainerHighest = Color(0xFF283A3E), surfaceContainerLow = Color(0xFF101E22),
    error = Color(0xFFEDB4A1), onError = Color(0xFF331C15),
    errorContainer = Color(0xFF382923), onErrorContainer = Color(0xFFF5D4C8),
    outline = Color(0xFF435356), outlineVariant = Color(0xFF2A3B40),
)
private val GalleryLight = lightColorScheme(
    primary = Color(0xFF715328), onPrimary = Color(0xFFFFF9EF),
    primaryContainer = Color(0xFFE9DCC5), onPrimaryContainer = Color(0xFF24302C),
    secondary = Color(0xFF4B6557), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7DF), onSecondaryContainer = Color(0xFF263B30),
    background = Color(0xFFF4F0E8), onBackground = Color(0xFF17272B),
    surface = Color(0xFFFCF9F3), onSurface = Color(0xFF17272B),
    surfaceVariant = Color(0xFFE8E6DE), onSurfaceVariant = Color(0xFF586665),
    error = Color(0xFF914B38), errorContainer = Color(0xFFF4DDD3), onErrorContainer = Color(0xFF653D2C),
    outline = Color(0xFF9AA5A0), outlineVariant = Color(0xFFD6DBD3),
)
@Composable
fun HeirloomTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) GalleryDark else GalleryLight,
        typography = HeirloomTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
