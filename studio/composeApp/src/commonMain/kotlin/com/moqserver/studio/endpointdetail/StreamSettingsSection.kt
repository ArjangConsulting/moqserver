package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.ResponseStream
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InfoTooltip
import io.github.maniramezan.kmpcomponents.ThemeSwitch

private object StreamSettingsStrings {
	const val STREAM = "Stream the response in chunks"
	const val STREAM_TOOLTIP =
		"Sends the body in fixed-size chunks with a pause between them, to test progress UI, timeouts and " +
			"partial reads. The response has no Content-Length."
	const val CHUNK_BYTES = "Chunk (bytes)"
	const val CHUNK_BYTES_TOOLTIP = "Bytes per chunk. At least 1."
	const val INTERVAL_MS = "Pause (ms)"
	const val INTERVAL_MS_TOOLTIP = "Wait between chunks, 0 to 60000 ms."
	const val DISCONNECT_AFTER = "Disconnect after (bytes)"
	const val DISCONNECT_AFTER_TOOLTIP =
		"Closes the connection after this many bytes, without finishing the response, to simulate a dropped " +
			"connection. Leave empty to send the whole body."
}

/** Chunk size the switch starts with: small enough to see progress on a typical JSON fixture. */
private const val DEFAULT_CHUNK_BYTES = 1024

/** Pause the switch starts with between chunks. */
private const val DEFAULT_INTERVAL_MS = 100

/** Largest pause the server accepts between chunks (`ResponseStream.validate`). */
private const val MAX_INTERVAL_MS = 60_000

/** Edits the variant's `stream` block. */
@Composable
internal fun StreamSettingsSection(
	variant: ProjectVariant,
	onUpdate: (ProjectVariant) -> Unit,
) {
	val stream = variant.stream
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
		ThemeSwitch(
			checked = stream != null,
			onCheckedChange = { enabled ->
				onUpdate(variant.copy(stream = if (enabled) defaultResponseStream() else null))
			},
			label = StreamSettingsStrings.STREAM,
			tooltip = StreamSettingsStrings.STREAM_TOOLTIP,
		)
		if (stream != null) {
			Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
				FormTextField(
					value = stream.chunkBytes.toString(),
					onValueChange = {
						when (val update = acceptedBoundedIntInput(it, stream.chunkBytes, minimum = 1)) {
							is NumericInputUpdate.Accepted -> onUpdate(variant.copy(stream = stream.copy(chunkBytes = update.value)))
							NumericInputUpdate.Ignored -> Unit
						}
					},
					label = StreamSettingsStrings.CHUNK_BYTES,
					modifier = Modifier.width(StudioDimens.tableNameColumnWidth),
					trailingContent = { InfoTooltip(StreamSettingsStrings.CHUNK_BYTES_TOOLTIP) },
				)
				FormTextField(
					value = (stream.intervalMs ?: 0).toString(),
					onValueChange = {
						when (val update = acceptedBoundedIntInput(it, stream.intervalMs ?: 0, minimum = 0, maximum = MAX_INTERVAL_MS)) {
							is NumericInputUpdate.Accepted ->
								onUpdate(variant.copy(stream = stream.copy(intervalMs = update.value.takeIf { value -> value > 0 })))
							NumericInputUpdate.Ignored -> Unit
						}
					},
					label = StreamSettingsStrings.INTERVAL_MS,
					modifier = Modifier.width(StudioDimens.tableNameColumnWidth),
					trailingContent = { InfoTooltip(StreamSettingsStrings.INTERVAL_MS_TOOLTIP) },
				)
				FormTextField(
					value = stream.disconnectAfterBytes?.toString().orEmpty(),
					onValueChange = {
						when (val update = acceptedOptionalNonNegativeIntInput(it, stream.disconnectAfterBytes)) {
							is NumericInputUpdate.Accepted ->
								onUpdate(variant.copy(stream = stream.copy(disconnectAfterBytes = update.value)))
							NumericInputUpdate.Ignored -> Unit
						}
					},
					label = StreamSettingsStrings.DISCONNECT_AFTER,
					modifier = Modifier.width(StudioDimens.tableConditionColumnWidth),
					trailingContent = { InfoTooltip(StreamSettingsStrings.DISCONNECT_AFTER_TOOLTIP) },
				)
			}
		}
	}
}

internal fun defaultResponseStream(): ResponseStream =
	ResponseStream(chunkBytes = DEFAULT_CHUNK_BYTES, intervalMs = DEFAULT_INTERVAL_MS)
