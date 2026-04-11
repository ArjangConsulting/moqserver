package com.moqserver.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.designsystem.StudioShapes

@Composable
fun TabStrip(content: @Composable RowScope.() -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState()),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
		content = content,
	)
}

@Composable
fun TabChip(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
	onClose: (() -> Unit)? = null,
) {
	val background = if (selected) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surfaceVariant
	}
	val contentColor = if (selected) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}

	Row(
		modifier = Modifier
			.clip(StudioShapes.tab)
			.background(background)
			.clickable(onClick = onClick)
			.padding(
				start = StudioDimens.tabChipPaddingHorizontal,
				end = if (onClose != null) StudioDimens.tabChipPaddingEndWithClose else StudioDimens.tabChipPaddingHorizontal,
				top = StudioDimens.tabChipPaddingVertical,
				bottom = StudioDimens.tabChipPaddingVertical,
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelLarge,
			color = contentColor,
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
		)
		if (onClose != null) {
			Icon(
				imageVector = Icons.Default.Close,
				contentDescription = "Delete variant",
				tint = contentColor,
				modifier = Modifier
					.size(StudioDimens.tabCloseIconSize)
					.clickable(onClick = onClose),
			)
		}
	}
}
