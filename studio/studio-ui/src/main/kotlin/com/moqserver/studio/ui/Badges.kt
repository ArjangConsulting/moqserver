package com.moqserver.studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.github.maniramezan.kmpcomponents.CountBadge
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.TonalBadge

private object BadgeStrings {
	const val VARIANT = "variant"
	const val VARIANTS = "variants"
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
	TonalBadge(text = method.uppercase(), color = color)
}

@Composable
fun StatusBadge(status: Int) {
	val tone = when (httpStatusClass(status)) {
		HttpStatusClass.SUCCESS -> StatusTone.SUCCESS
		HttpStatusClass.REDIRECT -> StatusTone.INFO
		HttpStatusClass.CLIENT_ERROR -> StatusTone.WARNING
		HttpStatusClass.SERVER_ERROR -> StatusTone.ERROR
		HttpStatusClass.OTHER -> StatusTone.INFO
	}
	TonalBadge(text = status.toString(), tone = tone)
}

@Composable
fun VariantCountBadge(count: Int) {
	val unit = if (count == 1) BadgeStrings.VARIANT else BadgeStrings.VARIANTS
	CountBadge(count = count, contentDescription = "$count $unit")
}
