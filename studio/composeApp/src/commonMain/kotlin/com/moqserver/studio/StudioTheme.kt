package com.moqserver.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class StudioThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Composable
fun StudioTheme(
    themeMode: StudioThemeMode = StudioThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        StudioThemeMode.SYSTEM -> isSystemInDarkTheme()
        StudioThemeMode.LIGHT -> false
        StudioThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) studioDarkColors() else studioLightColors(),
        content = content,
    )
}

private fun studioLightColors(): ColorScheme = lightColorScheme(
    primary = Color(0xFF1F5EFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF002A78),
    secondary = Color(0xFF52617A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E2F2),
    onSecondaryContainer = Color(0xFF0F1D34),
    tertiary = Color(0xFF006A6A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9DEDED),
    onTertiaryContainer = Color(0xFF002020),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color.White,
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171C23),
    surface = Color(0xFFFDFDFF),
    onSurface = Color(0xFF171C23),
    surfaceVariant = Color(0xFFE2E6EF),
    onSurfaceVariant = Color(0xFF434955),
    outline = Color(0xFF737A86),
)

private fun studioDarkColors(): ColorScheme = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF24438F),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFBCC6DD),
    onSecondary = Color(0xFF243145),
    secondaryContainer = Color(0xFF3A475E),
    onSecondaryContainer = Color(0xFFD9E2F2),
    tertiary = Color(0xFF81D4D4),
    onTertiary = Color(0xFF002020),
    tertiaryContainer = Color(0xFF004F4F),
    onTertiaryContainer = Color(0xFF9DEDED),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF11151C),
    onBackground = Color(0xFFE4E7EF),
    surface = Color(0xFF171C23),
    onSurface = Color(0xFFE4E7EF),
    surfaceVariant = Color(0xFF434955),
    onSurfaceVariant = Color(0xFFC4CAD8),
    outline = Color(0xFF8D94A0),
)
