package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.projectformat.RequestRules
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InfoTooltip
import io.github.maniramezan.kmpcomponents.ThemeSwitch
import io.github.maniramezan.kmpcomponents.ThemeTextButton

private object RequestBodyRulesStrings {
	const val TITLE = "Request Body"
	const val REQUIRES_BODY = "Require a request body"
	const val REQUIRES_BODY_TOOLTIP = "Requests without a body get 400 missing_request_body."
	const val CONTENT_TYPES = "Accepted Content Types"
	const val CONTENT_TYPES_TOOLTIP =
		"When set, a request body with any other Content-Type gets 415 unsupported_content_type. " +
			"Wildcards such as image/* are allowed."
	const val NO_CONTENT_TYPES = "Any Content-Type is accepted."
	const val CONTENT_TYPE = "Content type"
	const val CONTENT_TYPE_PLACEHOLDER = "application/json"
	const val ADD = "Add"
	const val DELETE_CONTENT_TYPE = "Delete content type"
}

/** Edits `requires_body` and `accepted_content_types` in the endpoint's request rules. */
@Composable
internal fun RequestBodyRulesSection(
	requestRules: RequestRules,
	onUpdate: (RequestRules) -> Unit,
) {
	val contentTypes = requestRules.acceptedContentTypes.orEmpty()
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
		Text(RequestBodyRulesStrings.TITLE, style = MaterialTheme.typography.titleSmall)
		ThemeSwitch(
			checked = requestRules.requiresBody == true,
			onCheckedChange = { onUpdate(requestRules.copy(requiresBody = if (it) true else null)) },
			label = RequestBodyRulesStrings.REQUIRES_BODY,
			tooltip = RequestBodyRulesStrings.REQUIRES_BODY_TOOLTIP,
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs), verticalAlignment = Alignment.CenterVertically) {
				Text(RequestBodyRulesStrings.CONTENT_TYPES, style = MaterialTheme.typography.labelLarge)
				InfoTooltip(RequestBodyRulesStrings.CONTENT_TYPES_TOOLTIP)
			}
			ThemeTextButton(
				text = RequestBodyRulesStrings.ADD,
				onClick = { onUpdate(requestRules.withAcceptedContentTypes(contentTypes + "")) },
			)
		}
		if (contentTypes.isEmpty()) {
			Text(
				RequestBodyRulesStrings.NO_CONTENT_TYPES,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		contentTypes.forEachIndexed { index, contentType ->
			Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs), verticalAlignment = Alignment.CenterVertically) {
				FormTextField(
					value = contentType,
					onValueChange = { onUpdate(requestRules.withAcceptedContentTypes(contentTypes.updated(index, it.trim()))) },
					label = RequestBodyRulesStrings.CONTENT_TYPE,
					placeholder = RequestBodyRulesStrings.CONTENT_TYPE_PLACEHOLDER,
					modifier = Modifier.weight(1f),
				)
				ThemeTextButton(
					text = RequestBodyRulesStrings.DELETE_CONTENT_TYPE,
					onClick = {
						onUpdate(requestRules.withAcceptedContentTypes(contentTypes.toMutableList().also { it.removeAt(index) }))
					},
					destructive = true,
				)
			}
		}
	}
}

/**
 * Replaces the accepted content types. A blank entry is kept while it is being typed; the
 * validator reports it (`E_BLANK_ACCEPTED_CONTENT_TYPE`) and Save stays disabled until it is
 * filled in or removed.
 */
internal fun RequestRules.withAcceptedContentTypes(contentTypes: List<String>): RequestRules =
	copy(acceptedContentTypes = contentTypes.ifEmpty { null })
