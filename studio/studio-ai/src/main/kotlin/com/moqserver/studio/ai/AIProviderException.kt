package com.moqserver.studio.ai

sealed class AIProviderException(
    message: String,
    val retryable: Boolean = false,
) : Exception(message) {

    class AuthInvalid(provider: String) :
        AIProviderException("$provider API key is invalid.", retryable = false)

    class Unavailable(provider: String, detail: String, retryable: Boolean) :
        AIProviderException("$provider unavailable: $detail", retryable = retryable)

    class ParseFailure(provider: String) :
        AIProviderException("$provider returned an unparseable response.", retryable = false)

    class InvalidConfig(provider: String, issues: List<String>) :
        AIProviderException(
            "$provider configuration is invalid: ${issues.joinToString()}",
            retryable = false,
        )
}
