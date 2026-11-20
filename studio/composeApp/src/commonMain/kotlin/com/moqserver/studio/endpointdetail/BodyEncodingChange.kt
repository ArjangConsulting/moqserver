package com.moqserver.studio.endpointdetail

import com.moqserver.studio.projectformat.BodyEncoding
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
import kotlin.io.encoding.Base64

/** Outcome of switching an inline string body between UTF-8 text and base64. */
internal sealed interface BodyEncodingChange {
	data class Changed(val variant: ProjectVariant) : BodyEncodingChange

	/** The base64 decodes to bytes that are not UTF-8 text, so it has to stay base64. */
	data object NotText : BodyEncodingChange

	data object InvalidBase64 : BodyEncodingChange
}

/** Whether the variant has an inline string body, the only kind `body_encoding` applies to. */
internal val ProjectVariant.hasInlineStringBody: Boolean
	get() = bodyFile == null && body is YamlValue.Str

/**
 * Re-encodes the inline string body so the response bytes stay the same: text becomes its UTF-8
 * bytes in base64, and base64 becomes text when its bytes are valid UTF-8. Whitespace inside
 * base64 is ignored, as the server ignores the line breaks of a YAML block scalar.
 */
internal fun ProjectVariant.convertingBodyEncoding(target: BodyEncoding): BodyEncodingChange {
	val text = (body as? YamlValue.Str)?.value ?: return BodyEncodingChange.Changed(this)
	val current = bodyEncoding ?: BodyEncoding.UTF8
	if (current == target) return BodyEncodingChange.Changed(this)
	return when (target) {
		BodyEncoding.BASE64 -> BodyEncodingChange.Changed(
			copy(body = YamlValue.Str(Base64.encode(text.encodeToByteArray())), bodyEncoding = BodyEncoding.BASE64),
		)
		BodyEncoding.UTF8 -> {
			val bytes = runCatching { Base64.decode(text.filterNot(Char::isWhitespace)) }.getOrNull()
				?: return BodyEncodingChange.InvalidBase64
			val decoded = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
				?: return BodyEncodingChange.NotText
			BodyEncodingChange.Changed(copy(body = YamlValue.Str(decoded), bodyEncoding = null))
		}
	}
}
