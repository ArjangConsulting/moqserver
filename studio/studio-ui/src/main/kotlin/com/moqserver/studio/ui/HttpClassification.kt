package com.moqserver.studio.ui

/** HTTP status code families used to pick badge colors. */
internal enum class HttpStatusClass {
	SUCCESS,
	REDIRECT,
	CLIENT_ERROR,
	SERVER_ERROR,
	OTHER,
}

/** Classifies a raw HTTP status code into its family. Pure, color-free, and testable. */
internal fun httpStatusClass(status: Int): HttpStatusClass = when (status) {
	in 200..299 -> HttpStatusClass.SUCCESS
	in 300..399 -> HttpStatusClass.REDIRECT
	in 400..499 -> HttpStatusClass.CLIENT_ERROR
	in 500..599 -> HttpStatusClass.SERVER_ERROR
	else -> HttpStatusClass.OTHER
}

/** Semantic color role for an HTTP method badge. */
internal enum class HttpMethodRole {
	PRIMARY,
	SECONDARY,
	TERTIARY,
	ERROR,
	OUTLINE,
}

/** Maps an HTTP method (case-insensitive) to its badge color role. Pure and testable. */
internal fun httpMethodRole(method: String): HttpMethodRole = when (method.uppercase()) {
	"GET" -> HttpMethodRole.PRIMARY
	"POST" -> HttpMethodRole.TERTIARY
	"PUT" -> HttpMethodRole.SECONDARY
	"PATCH" -> HttpMethodRole.SECONDARY
	"DELETE" -> HttpMethodRole.ERROR
	else -> HttpMethodRole.OUTLINE
}
