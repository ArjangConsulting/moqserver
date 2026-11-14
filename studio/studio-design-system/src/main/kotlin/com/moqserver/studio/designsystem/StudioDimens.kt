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

	/** No spacing, e.g. a card whose content draws edge to edge. */
	val none = 0.dp

	// ── Common component sizes ─────────────────────────────

	/** Hairline border width. */
	val borderWidth = 1.dp

	/** Icon size for list rows, e.g. language logos. */
	val mediumIconSize = 24.dp

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

	/** Operator dropdown in a match-condition row. */
	val matchConditionDropdownWidth = 140.dp

	/** Value field in a match-condition row. */
	val matchConditionValueWidth = 160.dp

	// ── Field widths ───────────────────────────────────────

	/** HTTP method selector in the endpoint header. */
	val methodSelectorWidth = 140.dp

	/** HTTP status field in a variant summary. */
	val statusFieldWidth = 130.dp

	// ── Layout sizes ───────────────────────────────────────

	/** Sidebar / endpoint browser width. */
	val endpointBrowserWidth = 300.dp

	/** Preferences navigation sidebar width. */
	val navigationSidebarWidth = 200.dp

	/** Popover width, e.g. AI connection details. */
	val popoverWidth = 320.dp

	/** Content width of compact dialogs such as Import from URL. */
	val dialogContentWidth = 480.dp

	/** Height of the validation diagnostics list. */
	val validationListHeight = 200.dp

	/** Maximum height of a scrollable list inside a dialog. */
	val dialogListMaxHeight = 400.dp

	/** Height of the response body code editor. */
	val bodyEditorHeight = 280.dp

	/** Indent of an endpoint's generated responses in Import Review. */
	val importResponseIndent = 28.dp

	/** Indent of an endpoint's details (tags, notes) in Import Review. */
	val importDetailIndent = 48.dp

	// ── Window sizes ───────────────────────────────────────

	val mainWindowWidth = 1400.dp
	val mainWindowHeight = 900.dp
	val importReviewWindowWidth = 1120.dp
	val importReviewWindowHeight = 820.dp
	val runtimeInspectorWindowWidth = 900.dp
	val runtimeInspectorWindowHeight = 780.dp
	val preferencesWindowWidth = 900.dp
	val preferencesWindowHeight = 680.dp
	val exportWindowWidth = 640.dp
	val exportWindowHeight = 720.dp
}
