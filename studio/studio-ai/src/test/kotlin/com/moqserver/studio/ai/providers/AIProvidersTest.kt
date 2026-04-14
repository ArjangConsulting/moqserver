package com.moqserver.studio.ai.providers

import com.moqserver.studio.ai.AIProviderCapability
import com.moqserver.studio.ai.AIProviderException
import com.moqserver.studio.ai.AIProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnthropicAIProviderTest {

	@Test
	fun `provider metadata is correct`() {
		val provider = AnthropicAIProvider(apiKey = "test-key")
		assertEquals("anthropic", provider.id)
		assertEquals("Anthropic", provider.displayName)
		assertEquals(AIProviderKind.HOSTED, provider.kind)
		assertTrue(provider.capabilities.containsAll(AIProviderCapability.entries.toSet()))
	}

	@Test
	fun `checkAvailability returns false for blank api key`() = runTest {
		val provider = AnthropicAIProvider(apiKey = "   ")
		assertFalse(provider.checkAvailability())
	}

	@Test
	fun `checkAvailability returns true when models endpoint succeeds`() = runTest {
		val client = mockClient { respond("[]", HttpStatusCode.OK, jsonHeaders()) }
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)
		assertTrue(provider.checkAvailability())
	}

	@Test
	fun `checkAvailability returns false when models endpoint fails`() = runTest {
		val client = mockClient { respond("Unauthorized", HttpStatusCode.Unauthorized) }
		val provider = AnthropicAIProvider(apiKey = "sk-bad", httpClient = client)
		assertFalse(provider.checkAvailability())
	}

	@Test
	fun `validateConfig returns issue for blank api key`() = runTest {
		val provider = AnthropicAIProvider(apiKey = "")
		val issues = provider.validateConfig()
		assertEquals(1, issues.size)
		assertContains(issues.first(), "API key is not configured")
	}

	@Test
	fun `complete returns parsed result on success`() = runTest {
		val responseBody = """
			{
				"model": "claude-sonnet-4-6",
				"content": [{"type": "text", "text": "Hello world"}],
				"usage": {"input_tokens": 10, "output_tokens": 5}
			}
		""".trimIndent()
		val client = mockClient { respond(responseBody, HttpStatusCode.OK, jsonHeaders()) }
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)

		val result = provider.complete("Say hello")

		assertEquals("Hello world", result.text)
		assertEquals("claude-sonnet-4-6", result.model)
		assertEquals(10, result.promptTokens)
		assertEquals(5, result.completionTokens)
		assertEquals(15, result.totalTokens)
		assertNotNull(result.latencyMs)
	}

	@Test
	fun `complete throws AuthInvalid on 401`() = runTest {
		val client = mockClient { respond("", HttpStatusCode.Unauthorized) }
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)

		assertFailsWith<AIProviderException.AuthInvalid> {
			provider.complete("test")
		}
	}

	@Test
	fun `complete throws retryable Unavailable on 429`() = runTest {
		val client = mockClient { respond("", HttpStatusCode.TooManyRequests) }
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)

		val ex = assertFailsWith<AIProviderException.Unavailable> {
			provider.complete("test")
		}
		assertTrue(ex.retryable)
	}

	@Test
	fun `complete throws retryable Unavailable on 500`() = runTest {
		val client = mockClient { respond("", HttpStatusCode.InternalServerError) }
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)

		val ex = assertFailsWith<AIProviderException.Unavailable> {
			provider.complete("test")
		}
		assertTrue(ex.retryable)
	}

	@Test
	fun `complete throws non-retryable Unavailable on 400`() = runTest {
		val client = mockClient { respond("", HttpStatusCode.BadRequest) }
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)

		val ex = assertFailsWith<AIProviderException.Unavailable> {
			provider.complete("test")
		}
		assertFalse(ex.retryable)
	}

	@Test
	fun `complete throws ParseFailure when no text content block found`() = runTest {
		val responseBody = """{"model": "claude-sonnet-4-6", "content": [], "usage": {}}"""
		val client = mockClient { respond(responseBody, HttpStatusCode.OK, jsonHeaders()) }
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)

		assertFailsWith<AIProviderException.ParseFailure> {
			provider.complete("test")
		}
	}

	@Test
	fun `complete uses custom model when specified`() = runTest {
		var capturedBody = ""
		val client = mockClient {
			capturedBody = String(it.body.toByteArray())
			respond(
				"""{"model":"custom-model","content":[{"type":"text","text":"ok"}],"usage":{}}""",
				HttpStatusCode.OK,
				jsonHeaders(),
			)
		}
		val provider = AnthropicAIProvider(apiKey = "sk-test", httpClient = client)

		val result = provider.complete("test", model = "custom-model")

		assertEquals("custom-model", result.model)
		assertContains(capturedBody, "custom-model")
	}
}

class OpenAIAIProviderTest {

	@Test
	fun `provider metadata is correct`() {
		val provider = OpenAIAIProvider(apiKey = "test-key")
		assertEquals("openai", provider.id)
		assertEquals("OpenAI", provider.displayName)
		assertEquals(AIProviderKind.HOSTED, provider.kind)
	}

	@Test
	fun `checkAvailability returns false for blank api key`() = runTest {
		val provider = OpenAIAIProvider(apiKey = "")
		assertFalse(provider.checkAvailability())
	}

	@Test
	fun `complete returns parsed result on success`() = runTest {
		val responseBody = """
			{
				"model": "gpt-4o",
				"choices": [{"message": {"content": "Hello"}}],
				"usage": {"prompt_tokens": 8, "completion_tokens": 3, "total_tokens": 11}
			}
		""".trimIndent()
		val client = mockClient { respond(responseBody, HttpStatusCode.OK, jsonHeaders()) }
		val provider = OpenAIAIProvider(apiKey = "sk-test", httpClient = client)

		val result = provider.complete("Say hello")

		assertEquals("Hello", result.text)
		assertEquals("gpt-4o", result.model)
		assertEquals(8, result.promptTokens)
		assertEquals(3, result.completionTokens)
		assertEquals(11, result.totalTokens)
	}

	@Test
	fun `complete throws AuthInvalid on 401`() = runTest {
		val client = mockClient { respond("", HttpStatusCode.Unauthorized) }
		val provider = OpenAIAIProvider(apiKey = "sk-test", httpClient = client)

		assertFailsWith<AIProviderException.AuthInvalid> {
			provider.complete("test")
		}
	}

	@Test
	fun `complete throws ParseFailure when choices empty`() = runTest {
		val responseBody = """{"model": "gpt-4o", "choices": [], "usage": {}}"""
		val client = mockClient { respond(responseBody, HttpStatusCode.OK, jsonHeaders()) }
		val provider = OpenAIAIProvider(apiKey = "sk-test", httpClient = client)

		assertFailsWith<AIProviderException.ParseFailure> {
			provider.complete("test")
		}
	}
}

class GeminiAIProviderTest {

	@Test
	fun `provider metadata is correct`() {
		val provider = GeminiAIProvider(apiKey = "test-key")
		assertEquals("gemini", provider.id)
		assertEquals("Google Gemini", provider.displayName)
		assertEquals(AIProviderKind.HOSTED, provider.kind)
	}

	@Test
	fun `checkAvailability returns false for blank api key`() = runTest {
		val provider = GeminiAIProvider(apiKey = "")
		assertFalse(provider.checkAvailability())
	}

	@Test
	fun `complete returns parsed result on success`() = runTest {
		val responseBody = """
			{
				"candidates": [{"content": {"parts": [{"text": "Gemini response"}]}}],
				"usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 4, "totalTokenCount": 16}
			}
		""".trimIndent()
		val client = mockClient { respond(responseBody, HttpStatusCode.OK, jsonHeaders()) }
		val provider = GeminiAIProvider(apiKey = "test-key", httpClient = client)

		val result = provider.complete("Say hello")

		assertEquals("Gemini response", result.text)
		assertEquals(12, result.promptTokens)
		assertEquals(4, result.completionTokens)
		assertEquals(16, result.totalTokens)
	}

	@Test
	fun `complete throws AuthInvalid on 401`() = runTest {
		val client = mockClient { respond("", HttpStatusCode.Unauthorized) }
		val provider = GeminiAIProvider(apiKey = "test-key", httpClient = client)

		assertFailsWith<AIProviderException.AuthInvalid> {
			provider.complete("test")
		}
	}

	@Test
	fun `complete throws AuthInvalid on 403`() = runTest {
		val client = mockClient { respond("", HttpStatusCode.Forbidden) }
		val provider = GeminiAIProvider(apiKey = "test-key", httpClient = client)

		assertFailsWith<AIProviderException.AuthInvalid> {
			provider.complete("test")
		}
	}

	@Test
	fun `complete throws ParseFailure when no candidates`() = runTest {
		val responseBody = """{"candidates": [], "usageMetadata": {}}"""
		val client = mockClient { respond(responseBody, HttpStatusCode.OK, jsonHeaders()) }
		val provider = GeminiAIProvider(apiKey = "test-key", httpClient = client)

		assertFailsWith<AIProviderException.ParseFailure> {
			provider.complete("test")
		}
	}
}

class OllamaAIProviderTest {

	@Test
	fun `provider metadata is correct`() {
		val provider = OllamaAIProvider()
		assertEquals("ollama", provider.id)
		assertEquals("Ollama", provider.displayName)
		assertEquals(AIProviderKind.LOCAL, provider.kind)
	}

	@Test
	fun `checkAvailability returns true when tags endpoint succeeds`() = runTest {
		val client = mockClient { respond("{}", HttpStatusCode.OK, jsonHeaders()) }
		val provider = OllamaAIProvider(httpClient = client)
		assertTrue(provider.checkAvailability())
	}

	@Test
	fun `checkAvailability returns false when server unreachable`() = runTest {
		val client = mockClient { throw java.io.IOException("Connection refused") }
		val provider = OllamaAIProvider(httpClient = client)
		assertFalse(provider.checkAvailability())
	}

	@Test
	fun `validateConfig returns issue for blank base URL`() = runTest {
		val provider = OllamaAIProvider(baseUrl = "")
		val issues = provider.validateConfig()
		assertTrue(issues.isNotEmpty())
		assertContains(issues.first(), "base URL is not configured")
	}

	@Test
	fun `complete returns parsed result on success`() = runTest {
		val responseBody = """{"response": "Hello from Ollama", "model": "llama3.2"}"""
		val client = mockClient { respond(responseBody, HttpStatusCode.OK, jsonHeaders()) }
		val provider = OllamaAIProvider(httpClient = client)

		val result = provider.complete("Say hello")

		assertEquals("Hello from Ollama", result.text)
		assertNotNull(result.latencyMs)
	}

	@Test
	fun `complete throws Unavailable on server error`() = runTest {
		val client = mockClient {
			respond("""{"error": "model not found"}""", HttpStatusCode.InternalServerError, jsonHeaders())
		}
		val provider = OllamaAIProvider(httpClient = client)

		val ex = assertFailsWith<AIProviderException.Unavailable> {
			provider.complete("test")
		}
		assertTrue(ex.retryable)
		assertContains(ex.message!!, "model not found")
	}

	@Test
	fun `complete throws Unavailable on network error`() = runTest {
		val client = mockClient { throw java.io.IOException("Connection refused") }
		val provider = OllamaAIProvider(httpClient = client)

		val ex = assertFailsWith<AIProviderException.Unavailable> {
			provider.complete("test")
		}
		assertTrue(ex.retryable)
	}
}

// ── Shared test helpers ─────────────────────────────────────────

private fun mockClient(
	handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
		io.ktor.client.request.HttpRequestData,
	) -> io.ktor.client.request.HttpResponseData,
): HttpClient = HttpClient(MockEngine(handler)) {
	install(ContentNegotiation) {
		json(Json { ignoreUnknownKeys = true })
	}
}

private fun jsonHeaders() = headersOf(
	HttpHeaders.ContentType,
	ContentType.Application.Json.toString(),
)
