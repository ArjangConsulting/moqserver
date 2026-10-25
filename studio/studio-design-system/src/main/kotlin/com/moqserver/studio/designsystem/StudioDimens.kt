package com.moqserver.studio.designsystem

import androidx.compose.ui.unit.dp

/**
 * Shared spacing and sizing tokens used across Studio composables.
 *
 * Standard Material-style 4dp grid: [xxs]=2, [xs]=4, [s]=6, [m]=8, [l]=12,
 * [xl]=16, [xxl]=20, [xxxl]=24.
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

	/** Standard icon size in info tooltips and compact UI. */
	val smallIconSize = 16.dp

	val multilineFieldMinHeight = 96.dp
	val multilineFieldMaxHeight = 240.dp

	// ── Table column widths (shared across Headers, Cookies, Criteria tables) ──

	/** Name column in rule-matcher tables. */
	val tableNameColumnWidth = 160.dp

	/** Condition column in rule-matcher tables. */
	val tableConditionColumnWidth = 200.dp

	/** Match-value column in rule-matcher tables. */
	val tableMatchValueColumnWidth = 180.dp

	/** Required/action column in rule-matcher tables. */
	val tableActionColumnWidth = 140.dp

	/** Delete button or spacer width in table rows. */
	val tableDeleteButtonSize = 140.dp

	// ── Layout sizes ───────────────────────────────────────

	/** Sidebar / endpoint browser width. */
	val endpointBrowserWidth = 300.dp
}
