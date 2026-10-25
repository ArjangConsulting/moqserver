package com.moqserver.studio.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens used across Studio composables.
 *
 * These are non-theme-aware constants — they don't vary by light/dark mode.
 * For theme-aware colors, use `MaterialTheme.colorScheme` roles instead.
 *
 * Success and warning accents have explicit light/dark variants for shared theme tokens.
 */
object StudioColors {

	// ── Status indicators ──────────────────────────────────

	/** Green indicator for "ready" / "available" / "saved" status. Material Green 500. */
	val success = Color(0xFF4CAF50)

	// ── Editor panel (GitHub dark style) ───────────────────

	/** Dark panel background. */
	val editorPanelBackground = Color(0xFF161B22)

	/** Border color for dark editor panels. */
	val editorPanelBorder = Color(0xFF30363D)

	/** Light text/content color on dark editor panels. */
	val editorPanelContent = Color(0xFFE6EDF3)

	val successLight = Color(0xFF1B5E20)
	val successDark = Color(0xFFA5D6A7)
	val warningLight = Color(0xFFBF360C)
	val warningDark = Color(0xFFFFAB91)
}
