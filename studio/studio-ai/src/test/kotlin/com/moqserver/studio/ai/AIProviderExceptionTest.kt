package com.moqserver.studio.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AIProviderExceptionTest {

	@Test
	fun `AuthInvalid is not retryable`() {
		val ex = AIProviderException.AuthInvalid("Anthropic")
		assertFalse(ex.retryable)
		assertContains(ex.message!!, "Anthropic")
		assertContains(ex.message!!, "API key is invalid")
	}

	@Test
	fun `Unavailable carries retryable flag`() {
		val retryable = AIProviderException.Unavailable("OpenAI", "rate limit", retryable = true)
		assertTrue(retryable.retryable)
		assertContains(retryable.message!!, "rate limit")

		val notRetryable = AIProviderException.Unavailable("Gemini", "HTTP 400", retryable = false)
		assertFalse(notRetryable.retryable)
	}

	@Test
	fun `ParseFailure is not retryable`() {
		val ex = AIProviderException.ParseFailure("Ollama")
		assertFalse(ex.retryable)
		assertContains(ex.message!!, "unparseable")
	}

	@Test
	fun `InvalidConfig includes all issues`() {
		val ex = AIProviderException.InvalidConfig("Anthropic", listOf("Missing API key", "Invalid URL"))
		assertFalse(ex.retryable)
		assertContains(ex.message!!, "Missing API key")
		assertContains(ex.message!!, "Invalid URL")
	}

	@Test
	fun `all subclasses are sealed under AIProviderException`() {
		val exceptions: List<AIProviderException> = listOf(
			AIProviderException.AuthInvalid("test"),
			AIProviderException.Unavailable("test", "detail", retryable = false),
			AIProviderException.ParseFailure("test"),
			AIProviderException.InvalidConfig("test", emptyList()),
		)
		exceptions.forEach { assertIs<AIProviderException>(it) }
	}
}
