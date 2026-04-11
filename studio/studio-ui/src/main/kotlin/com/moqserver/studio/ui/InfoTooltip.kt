package com.moqserver.studio.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moqserver.studio.designsystem.StudioDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTooltip(text: String) {
	TooltipBox(
		positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
		tooltip = { PlainTooltip { Text(text) } },
		state = rememberTooltipState(),
	) {
		Icon(
			imageVector = Icons.Outlined.Info,
			contentDescription = "Info",
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
			modifier = Modifier.size(StudioDimens.smallIconSize),
		)
	}
}
