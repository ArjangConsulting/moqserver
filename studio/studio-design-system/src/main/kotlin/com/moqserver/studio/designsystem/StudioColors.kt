package com.moqserver.studio.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens used across Studio composables.
 *
 * These are non-theme-aware constants — they don't vary by light/dark mode.
 * For theme-aware colors, use `MaterialTheme.colorScheme` roles instead.
 *
 * HTTP status badge colors have explicit light/dark variants; callers should
 * pick the right pair based on the current theme.
 */
object StudioColors {

	// ── Status indicators ──────────────────────────────────

	/** Green indicator for "ready" / "available" / "saved" status. Material Green 500. */
	val success = Color(0xFF4CAF50)

	/** Light orange background for WARNING severity badges. Material Orange 50. */
	val warningContainer = Color(0xFFFFF3E0)

	// ── Editor panel (GitHub dark style) ───────────────────

	/** Dark panel background. */
	val editorPanelBackground = Color(0xFF161B22)

	/** Border color for dark editor panels. */
	val editorPanelBorder = Color(0xFF30363D)

	/** Light text/content color on dark editor panels. */
	val editorPanelContent = Color(0xFFE6EDF3)

	// ── Code editor (AWT-interoperable values) ─────────────

	/**
	 * Code editor background. Same hue as [editorPanelBackground] but tuned for
	 * the RSyntaxTextArea Swing panel. Use [toAwtColor] to convert.
	 */
	val codeEditorBackground = Color(0xFF1E1E1E)

	/** Code editor foreground text. Use [toAwtColor] to convert. */
	val codeEditorForeground = Color(0xFFE6EDF3)

	// ── HTTP status badge pairs (light / dark) ─────────────

	/** HTTP 2xx badge: container bg / foreground text. */
	val httpSuccessContainerLight = Color(0xFFC8E6C9)
	val httpSuccessOnContainerLight = Color(0xFF1B5E20)
	val httpSuccessContainerDark = Color(0xFF1B5E20)
	val httpSuccessOnContainerDark = Color(0xFFA5D6A7)

	/** HTTP 3xx badge: container bg / foreground text. */
	val httpRedirectContainerLight = Color(0xFFFFF8E1)
	val httpRedirectOnContainerLight = Color(0xFF6D4C00)
	val httpRedirectContainerDark = Color(0xFF3D2B00)
	val httpRedirectOnContainerDark = Color(0xFFFFE082)

	/** HTTP 4xx badge: container bg / foreground text. */
	val httpClientErrorContainerLight = Color(0xFFFFCCBC)
	val httpClientErrorOnContainerLight = Color(0xFFBF360C)
	val httpClientErrorContainerDark = Color(0xFF3E1200)
	val httpClientErrorOnContainerDark = Color(0xFFFFAB91)

	/** HTTP 5xx badge: container bg / foreground text. */
	val httpServerErrorContainerLight = Color(0xFFFFCDD2)
	val httpServerErrorOnContainerLight = Color(0xFFB71C1C)
	val httpServerErrorContainerDark = Color(0xFF400000)
	val httpServerErrorOnContainerDark = Color(0xFFEF9A9A)

	/** HTTP unknown badge: container bg / foreground text. */
	val httpUnknownContainerLight = Color(0xFFEEEEEE)
	val httpUnknownOnContainerLight = Color(0xFF424242)
	val httpUnknownContainerDark = Color(0xFF212121)
	val httpUnknownOnContainerDark = Color(0xFFB0BEC5)

	/** Badge background alpha multiplier for method badges (GET/POST/…). */
	const val METHOD_BADGE_ALPHA = 0.12f
}

/**
 * Convert a Compose [Color] to a [java.awt.Color] for Swing interop.
 *
 * Useful for passing design tokens into `RSyntaxTextArea` and other AWT/Swing
 * components without manually duplicating hex values.
 */
fun Color.toAwtColor(): java.awt.Color = java.awt.Color(
	(red * 255).toInt(),
	(green * 255).toInt(),
	(blue * 255).toInt(),
	(alpha * 255).toInt(),
)
