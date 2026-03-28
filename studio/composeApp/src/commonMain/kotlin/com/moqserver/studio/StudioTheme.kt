package com.moqserver.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic color tokens used across composables in composeApp.
 *
 * These are non-theme-aware constants — they don't vary by light/dark mode.
 * For theme-aware colors, use [MaterialTheme.colorScheme] roles instead.
 */
object StudioColors {
    /** Green indicator for "ready" / "available" / "saved" status. Material Green 500. */
    val success = Color(0xFF4CAF50)

    /** Light orange background for WARNING severity badges. Material Orange 50. */
    val warningContainer = Color(0xFFFFF3E0)

    /** Dark panel background (GitHub dark style). */
    val editorPanelBackground = Color(0xFF161B22)

    /** Border color for dark editor panels. */
    val editorPanelBorder = Color(0xFF30363D)

    /** Light text/content color on dark editor panels. */
    val editorPanelContent = Color(0xFFE6EDF3)

    /** HTTP 2xx status badge color — green. */
    val httpSuccess = Color(0xFF2E7D32)

    /** HTTP 3xx status badge color — amber. */
    val httpRedirect = Color(0xFF7B5E00)

    /** HTTP 4xx status badge color — deep orange-red. */
    val httpClientError = Color(0xFFBF360C)

    /** HTTP 5xx status badge color — red. */
    val httpServerError = Color(0xFFC62828)

    /** HTTP status badge color for unknown codes — gray. */
    val httpUnknown = Color(0xFF616161)
}

/**
 * Shared spacing and sizing tokens used across Studio composables.
 *
 * Standard Material-style 4dp grid: [xxs]=2, [xs]=4, [s]=6, [m]=8, [l]=12, [xl]=16, [xxl]=20, [xxxl]=24.
 */
object StudioDimens {
    // ── Spacing tokens (4dp grid) ──────────────────────────
    val xxs = 2.dp
    val xs = 4.dp
    val s = 6.dp
    val m = 8.dp
    val l = 12.dp
    val xl = 16.dp
    val xxl = 20.dp
    val xxxl = 24.dp

    // ── Common component sizes ─────────────────────────────
    /** Small indicator dot (status dots, availability dots). */
    val statusDotSize = 8.dp

    /** Small inline spinner (e.g. loading indicators in labels). */
    val smallSpinnerSize = 12.dp

    /** Standard icon size in info tooltips and compact UI. */
    val smallIconSize = 16.dp

    /** Standard spinner stroke width. */
    val spinnerStrokeWidth = 2.dp

    /** Thin spinner stroke for compact spinners. */
    val thinSpinnerStroke = 1.5.dp

    /** Medium spinner size in progress indicators. */
    val mediumSpinnerSize = 20.dp

    // ── Table column widths (shared across Headers, Cookies, Criteria tables) ──
    /** Name column in rule-matcher tables. */
    val tableNameColumnWidth = 160.dp

    /** Condition column in rule-matcher tables. */
    val tableConditionColumnWidth = 200.dp

    /** Match-value column in rule-matcher tables. */
    val tableMatchValueColumnWidth = 180.dp

    /** Required/action column in rule-matcher tables. */
    val tableActionColumnWidth = 80.dp

    /** Delete button or spacer width in table rows. */
    val tableDeleteButtonSize = 32.dp

    // ── Layout sizes ───────────────────────────────────────
    /** Sidebar / endpoint browser width. */
    val endpointBrowserWidth = 300.dp

    /** Pill-shaped corner radius for badges (effectively circular). */
    val pillCornerRadius = 999.dp

    // ── Typography ─────────────────────────────────────────
    /** Small badge/label font size. */
    val badgeFontSize = 10.sp
}

enum class StudioThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

internal fun resolveDarkTheme(
    themeMode: StudioThemeMode,
    systemInDarkTheme: Boolean,
): Boolean = when (themeMode) {
    StudioThemeMode.SYSTEM -> systemInDarkTheme
    StudioThemeMode.LIGHT -> false
    StudioThemeMode.DARK -> true
}

internal fun resolveNextThemeMode(
    themeMode: StudioThemeMode,
    systemInDarkTheme: Boolean,
): StudioThemeMode = if (resolveDarkTheme(themeMode, systemInDarkTheme)) {
    StudioThemeMode.LIGHT
} else {
    StudioThemeMode.DARK
}

@Composable
fun StudioTheme(
    themeMode: StudioThemeMode = StudioThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(
        themeMode = themeMode,
        systemInDarkTheme = isSystemInDarkTheme(),
    )

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
