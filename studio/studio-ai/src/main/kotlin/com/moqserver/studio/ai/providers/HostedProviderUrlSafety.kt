package com.moqserver.studio.ai.providers

import java.net.URI

internal fun hostedBaseUrlIssue(baseUrl: String): String? {
	val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
	return when {
		uri == null || uri.host.isNullOrBlank() -> "Base URL must be a valid absolute URL."
		!uri.scheme.equals("https", ignoreCase = true) -> "Base URL must use HTTPS to protect the API key."
		uri.userInfo != null -> "Base URL must not contain embedded credentials."
		else -> null
	}
}
