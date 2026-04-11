package com.moqserver.studio.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shared corner-radius tokens for Studio composables.
 *
 * Using named shapes keeps corner radii consistent across badges, tabs, cards,
 * and dialogs. Prefer these over inline `RoundedCornerShape(N.dp)`.
 */
object StudioShapes {

	/** Tight rounding for inline badges (method, status). 4dp. */
	val badge = RoundedCornerShape(4.dp)

	/** Tab chip rounding. 10dp. */
	val tab = RoundedCornerShape(10.dp)

	/** Card / panel rounding. 12dp. */
	val card = RoundedCornerShape(12.dp)

	/** Fully-rounded pill (count badges, toggles). Effectively circular. */
	val pill = RoundedCornerShape(999.dp)
}
