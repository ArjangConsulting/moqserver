package com.moqserver.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.moqserver.studio.designsystem.StudioColors
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.designsystem.StudioShapes
import com.moqserver.studio.designsystem.StudioTypography

/**
 * Paired background/foreground colors for a status badge.
 * Using distinct container + on-container colors (rather than the same hue at low alpha)
 * guarantees readable contrast in both light and dark themes.
 */
private data class StatusBadgeColors(val background: Color, val foreground: Color)

@Composable
private fun statusBadgeColors(status: Int): StatusBadgeColors {
	val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
	return when (httpStatusClass(status)) {
		HttpStatusClass.SUCCESS -> when {
			isDark -> StatusBadgeColors(StudioColors.httpSuccessContainerDark, StudioColors.httpSuccessOnContainerDark)
			else -> StatusBadgeColors(StudioColors.httpSuccessContainerLight, StudioColors.httpSuccessOnContainerLight)
		}
		HttpStatusClass.REDIRECT -> when {
			isDark -> StatusBadgeColors(StudioColors.httpRedirectContainerDark, StudioColors.httpRedirectOnContainerDark)
			else -> StatusBadgeColors(StudioColors.httpRedirectContainerLight, StudioColors.httpRedirectOnContainerLight)
		}
		HttpStatusClass.CLIENT_ERROR -> when {
			isDark -> StatusBadgeColors(StudioColors.httpClientErrorContainerDark, StudioColors.httpClientErrorOnContainerDark)
			else -> StatusBadgeColors(StudioColors.httpClientErrorContainerLight, StudioColors.httpClientErrorOnContainerLight)
		}
		HttpStatusClass.SERVER_ERROR -> when {
			isDark -> StatusBadgeColors(StudioColors.httpServerErrorContainerDark, StudioColors.httpServerErrorOnContainerDark)
			else -> StatusBadgeColors(StudioColors.httpServerErrorContainerLight, StudioColors.httpServerErrorOnContainerLight)
		}
		HttpStatusClass.OTHER -> when {
			isDark -> StatusBadgeColors(StudioColors.httpUnknownContainerDark, StudioColors.httpUnknownOnContainerDark)
			else -> StatusBadgeColors(StudioColors.httpUnknownContainerLight, StudioColors.httpUnknownOnContainerLight)
		}
	}
}

@Composable
fun MethodBadge(method: String) {
	val color = when (httpMethodRole(method)) {
		HttpMethodRole.PRIMARY -> MaterialTheme.colorScheme.primary
		HttpMethodRole.TERTIARY -> MaterialTheme.colorScheme.tertiary
		HttpMethodRole.SECONDARY -> MaterialTheme.colorScheme.secondary
		HttpMethodRole.ERROR -> MaterialTheme.colorScheme.error
		HttpMethodRole.OUTLINE -> MaterialTheme.colorScheme.outline
	}

	Text(
		text = method.uppercase(),
		style = StudioTypography.badge,
		color = color,
		modifier = Modifier
			.clip(StudioShapes.badge)
			.background(color.copy(alpha = StudioColors.METHOD_BADGE_ALPHA))
			.padding(horizontal = StudioDimens.methodBadgePaddingHorizontal, vertical = StudioDimens.methodBadgePaddingVertical),
	)
}

@Composable
fun StatusBadge(status: Int) {
	val colors = statusBadgeColors(status)
	Text(
		text = "$status",
		style = MaterialTheme.typography.labelMedium,
		fontWeight = FontWeight.Bold,
		color = colors.foreground,
		modifier = Modifier
			.clip(StudioShapes.badge)
			.background(colors.background)
			.padding(horizontal = StudioDimens.statusBadgePaddingHorizontal, vertical = StudioDimens.statusBadgePaddingVertical),
	)
}

@Composable
fun VariantCountBadge(count: Int) {
	val color = MaterialTheme.colorScheme.secondary

	Text(
		text = count.toString(),
		style = StudioTypography.badgeSemiBold,
		color = color,
		modifier = Modifier
			.clip(StudioShapes.pill)
			.background(color.copy(alpha = StudioColors.METHOD_BADGE_ALPHA))
			.padding(horizontal = StudioDimens.pillBadgePaddingHorizontal, vertical = StudioDimens.pillBadgePaddingVertical),
	)
}
