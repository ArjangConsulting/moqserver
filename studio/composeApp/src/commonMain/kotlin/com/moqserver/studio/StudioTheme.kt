@file:Suppress("MatchingDeclarationName")

package com.moqserver.studio

import com.moqserver.studio.designsystem.StudioThemeMode as DesignSystemThemeMode

/**
 * Backwards-compatibility shim.
 *
 * All canonical definitions now live in the `:studio-design-system` module under
 * `com.moqserver.studio.designsystem`. These typealiases let same-package consumers
 * in `composeApp` keep compiling without import changes. New code should import
 * directly from `com.moqserver.studio.designsystem.*`.
 */

/** @see com.moqserver.studio.designsystem.StudioColors */
typealias StudioColors = com.moqserver.studio.designsystem.StudioColors

/** @see com.moqserver.studio.designsystem.StudioDimens */
typealias StudioDimens = com.moqserver.studio.designsystem.StudioDimens

/** @see com.moqserver.studio.designsystem.StudioTypography */
typealias StudioTypography = com.moqserver.studio.designsystem.StudioTypography

/** @see com.moqserver.studio.designsystem.StudioShapes */
typealias StudioShapes = com.moqserver.studio.designsystem.StudioShapes

/** @see com.moqserver.studio.designsystem.StudioThemeMode */
typealias StudioThemeMode = DesignSystemThemeMode

/** @see com.moqserver.studio.designsystem.StudioTheme */
@Suppress("FunctionName")
@androidx.compose.runtime.Composable
fun StudioTheme(
	themeMode: StudioThemeMode = StudioThemeMode.SYSTEM,
	content: @androidx.compose.runtime.Composable () -> Unit,
) {
	com.moqserver.studio.designsystem.StudioTheme(themeMode = themeMode, content = content)
}

/** @see com.moqserver.studio.designsystem.resolveNextThemeMode */
fun resolveNextThemeMode(
	themeMode: StudioThemeMode,
	systemInDarkTheme: Boolean,
): StudioThemeMode = com.moqserver.studio.designsystem.resolveNextThemeMode(themeMode, systemInDarkTheme)

/**
 * Resolves whether the app should render in dark mode.
 *
 * Duplicated from design-system (where it is `internal`) so that `composeApp` tests
 * can still exercise it.
 */
internal fun resolveDarkTheme(
	themeMode: StudioThemeMode,
	systemInDarkTheme: Boolean,
): Boolean = when (themeMode) {
	StudioThemeMode.SYSTEM -> systemInDarkTheme
	StudioThemeMode.LIGHT -> false
	StudioThemeMode.DARK -> true
}
