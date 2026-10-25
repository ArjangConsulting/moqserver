package com.moqserver.studio.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.maniramezan.kommon.designsystem.ColorSchemeTokens
import io.github.maniramezan.kommon.designsystem.ColorToken
import io.github.maniramezan.kommon.designsystem.ThemeTokens

internal val studioThemeTokens = ThemeTokens(
	lightColors = studioLightColors().toColorTokens(
		success = StudioColors.successLight,
		warning = StudioColors.warningLight,
	),
	darkColors = studioDarkColors().toColorTokens(
		success = StudioColors.successDark,
		warning = StudioColors.warningDark,
	),
)

private fun ColorScheme.toColorTokens(success: Color, warning: Color): ColorSchemeTokens = ColorSchemeTokens(
	primary = primary.toColorToken(),
	onPrimary = onPrimary.toColorToken(),
	surface = surface.toColorToken(),
	onSurface = onSurface.toColorToken(),
	surfaceVariant = surfaceVariant.toColorToken(),
	outline = outline.toColorToken(),
	success = success.toColorToken(),
	warning = warning.toColorToken(),
	error = error.toColorToken(),
)

private fun Color.toColorToken(): ColorToken = ColorToken(toArgb().toUInt().toLong())
