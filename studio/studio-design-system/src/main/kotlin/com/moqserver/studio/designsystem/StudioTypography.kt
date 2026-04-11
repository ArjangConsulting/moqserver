package com.moqserver.studio.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Custom text styles for Studio.
 *
 * These augment the Material 3 [Typography] defaults with Studio-specific roles
 * (badges, code, compact labels). Access via [StudioTypography] for named styles,
 * or use [studioTypography] to apply the full Material scale override via
 * `MaterialTheme(typography = studioTypography())`.
 */
object StudioTypography {

	/** Page or section heading. Maps to Material `titleLarge`. */
	val heading = TextStyle(
		fontSize = 22.sp,
		fontWeight = FontWeight.SemiBold,
		lineHeight = 28.sp,
	)

	/** Secondary heading / panel title. Maps to Material `titleMedium`. */
	val subheading = TextStyle(
		fontSize = 16.sp,
		fontWeight = FontWeight.Medium,
		lineHeight = 24.sp,
	)

	/** Primary body text. Maps to Material `bodyMedium`. */
	val body = TextStyle(
		fontSize = 14.sp,
		fontWeight = FontWeight.Normal,
		lineHeight = 20.sp,
	)

	/** Small body / detail text. Maps to Material `bodySmall`. */
	val bodySmall = TextStyle(
		fontSize = 12.sp,
		fontWeight = FontWeight.Normal,
		lineHeight = 16.sp,
	)

	/** Label text for form fields and tabs. Maps to Material `labelLarge`. */
	val label = TextStyle(
		fontSize = 14.sp,
		fontWeight = FontWeight.Medium,
		lineHeight = 20.sp,
	)

	/** Small label for column headers, metadata. Maps to Material `labelSmall`. */
	val labelSmall = TextStyle(
		fontSize = 11.sp,
		fontWeight = FontWeight.Medium,
		lineHeight = 16.sp,
	)

	/** Badge / pill text (HTTP methods, status codes, counts). */
	val badge = TextStyle(
		fontSize = 10.sp,
		fontWeight = FontWeight.Bold,
		lineHeight = 14.sp,
	)

	/** Semi-bold badge variant (variant counts, secondary pills). */
	val badgeSemiBold = TextStyle(
		fontSize = 10.sp,
		fontWeight = FontWeight.SemiBold,
		lineHeight = 14.sp,
	)

	/** Monospace style for code snippets and JSON paths. */
	val code = TextStyle(
		fontSize = 13.sp,
		fontWeight = FontWeight.Normal,
		fontFamily = FontFamily.Monospace,
		lineHeight = 18.sp,
	)
}

/**
 * Returns a Material 3 [Typography] instance with the Studio-customized scale.
 *
 * Use this in [StudioTheme] to override the default Material type ramp:
 * ```
 * MaterialTheme(typography = studioTypography(), …)
 * ```
 */
fun studioTypography(): Typography = Typography(
	titleLarge = StudioTypography.heading,
	titleMedium = StudioTypography.subheading,
	bodyMedium = StudioTypography.body,
	bodySmall = StudioTypography.bodySmall,
	labelLarge = StudioTypography.label,
	labelSmall = StudioTypography.labelSmall,
)
