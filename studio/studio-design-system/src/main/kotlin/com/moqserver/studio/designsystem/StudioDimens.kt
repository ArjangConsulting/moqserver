package com.moqserver.studio.designsystem

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

	/** Close icon size inside tab chips. */
	val tabCloseIconSize = 14.dp

	/** Standard icon button touch-target / container size. */
	val iconButtonSize = 36.dp

	/** Divider thickness (vertical or horizontal separators). */
	val dividerWidth = 1.dp

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

	/** Popup / dropdown panel width (e.g. AI companion, settings popups). */
	val popupWidth = 320.dp

	/** Import-review nested indent. */
	val importIndent = 48.dp

	// ── Tab chip internal padding ──────────────────────────

	/** Horizontal inset for tab chip text (start / end when no close button). */
	val tabChipPaddingHorizontal = 14.dp

	/** Horizontal end inset when a close button is present. */
	val tabChipPaddingEndWithClose = 6.dp

	/** Vertical inset for tab chips. */
	val tabChipPaddingVertical = 8.dp

	// ── Badge internal padding ─────────────────────────────

	/** Horizontal padding inside method badges. */
	val methodBadgePaddingHorizontal = 6.dp

	/** Vertical padding inside method badges. */
	val methodBadgePaddingVertical = 2.dp

	/** Horizontal padding inside status badges. */
	val statusBadgePaddingHorizontal = 8.dp

	/** Vertical padding inside status badges. */
	val statusBadgePaddingVertical = 3.dp

	/** Horizontal padding inside pill / count badges. */
	val pillBadgePaddingHorizontal = 7.dp

	/** Vertical padding inside pill / count badges. */
	val pillBadgePaddingVertical = 2.dp

	// ── Typography (sp values that aren't full TextStyles) ─

	/** Small badge/label font size. */
	val badgeFontSize = 10.sp
}
