package com.moqserver.studio.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAPIURLFetcherTest {

	// -- normalizeUrl --

	@Test
	fun `normalizeUrl adds https scheme when missing`() {
		assertEquals("https://example.com/api", OpenAPIURLFetcher.normalizeUrl("example.com/api"))
	}

	@Test
	fun `normalizeUrl preserves existing https`() {
		assertEquals("https://example.com/api", OpenAPIURLFetcher.normalizeUrl("https://example.com/api"))
	}

	@Test
	fun `normalizeUrl preserves existing http`() {
		assertEquals("http://localhost:8080/api", OpenAPIURLFetcher.normalizeUrl("http://localhost:8080/api"))
	}

	@Test
	fun `normalizeUrl trims whitespace`() {
		assertEquals("https://example.com", OpenAPIURLFetcher.normalizeUrl("  https://example.com  "))
	}

	// -- looksLikeSpec --

	@Test
	fun `looksLikeSpec detects JSON OpenAPI 3`() {
		val json = """{"openapi": "3.0.0", "info": {"title": "Test"}}"""
		assertTrue(OpenAPIURLFetcher.looksLikeSpec(json, "application/json"))
	}

	@Test
	fun `looksLikeSpec detects JSON Swagger 2`() {
		val json = """{"swagger": "2.0", "info": {"title": "Test"}}"""
		assertTrue(OpenAPIURLFetcher.looksLikeSpec(json, "application/json"))
	}

	@Test
	fun `looksLikeSpec detects YAML OpenAPI`() {
		val yaml = """
			openapi: 3.0.3
			info:
			  title: Test
		""".trimIndent()
		assertTrue(OpenAPIURLFetcher.looksLikeSpec(yaml, "application/x-yaml"))
	}

	@Test
	fun `looksLikeSpec detects YAML Swagger 2`() {
		val yaml = """
			swagger: "2.0"
			info:
			  title: Test
		""".trimIndent()
		assertTrue(OpenAPIURLFetcher.looksLikeSpec(yaml, "text/yaml"))
	}

	@Test
	fun `looksLikeSpec detects spec even with empty content type`() {
		val json = """{"openapi": "3.1.0", "info": {"title": "API"}}"""
		assertTrue(OpenAPIURLFetcher.looksLikeSpec(json, ""))
	}

	@Test
	fun `looksLikeSpec rejects HTML content`() {
		val html = """<!DOCTYPE html><html><body>Hello</body></html>"""
		assertFalse(OpenAPIURLFetcher.looksLikeSpec(html, "text/html"))
	}

	@Test
	fun `looksLikeSpec rejects plain JSON without openapi or swagger key`() {
		val json = """{"name": "test", "version": 1}"""
		assertFalse(OpenAPIURLFetcher.looksLikeSpec(json, "application/json"))
	}

	@Test
	fun `looksLikeSpec rejects empty content`() {
		assertFalse(OpenAPIURLFetcher.looksLikeSpec("", ""))
	}

	@Test
	fun `looksLikeSpec handles leading whitespace in content`() {
		val json = """
			
			{"openapi": "3.0.0", "info": {"title": "Test"}}
		""".trimIndent()
		assertTrue(OpenAPIURLFetcher.looksLikeSpec(json, ""))
	}

	// -- extractSwaggerBundleUrl --

	@Test
	fun `extractSwaggerBundleUrl finds double-quoted url in SwaggerUIBundle`() {
		val html = """
			<script>
			const ui = SwaggerUIBundle({
				url: "/api/v1/openapi.json",
				dom_id: '#swagger-ui'
			})
			</script>
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractSwaggerBundleUrl(html, "https://example.com/docs")
		assertEquals("https://example.com/api/v1/openapi.json", result)
	}

	@Test
	fun `extractSwaggerBundleUrl finds single-quoted url in SwaggerUIBundle`() {
		val html = """
			<script>
			SwaggerUIBundle({
				url: '/swagger.json',
				dom_id: '#swagger-ui'
			})
			</script>
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractSwaggerBundleUrl(html, "https://example.com/docs/")
		assertEquals("https://example.com/swagger.json", result)
	}

	@Test
	fun `extractSwaggerBundleUrl finds absolute url`() {
		val html = """
			<script>
			SwaggerUIBundle({ url: "https://api.example.com/spec.yaml" })
			</script>
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractSwaggerBundleUrl(html, "https://example.com/docs")
		assertEquals("https://api.example.com/spec.yaml", result)
	}

	@Test
	fun `extractSwaggerBundleUrl returns null when no SwaggerUIBundle found`() {
		val html = """<html><body><h1>Hello</h1></body></html>"""
		assertNull(OpenAPIURLFetcher.extractSwaggerBundleUrl(html, "https://example.com"))
	}

	@Test
	fun `extractSwaggerBundleUrl finds configUrl pattern`() {
		val html = """
			<script>
			const config = { configUrl: "/api-docs/swagger-config" };
			</script>
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractSwaggerBundleUrl(html, "https://example.com")
		assertEquals("https://example.com/api-docs/swagger-config", result)
	}

	// -- extractEmbeddedSpecUrl --

	@Test
	fun `extractEmbeddedSpecUrl finds openapi json url in HTML`() {
		val html = """
			<link rel="openapi" href="/api/openapi.json">
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractEmbeddedSpecUrl(html, "https://example.com")
		assertEquals("https://example.com/api/openapi.json", result)
	}

	@Test
	fun `extractEmbeddedSpecUrl finds swagger yaml url`() {
		val html = """
			<a href="/docs/swagger.yaml">Download spec</a>
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractEmbeddedSpecUrl(html, "https://example.com")
		assertEquals("https://example.com/docs/swagger.yaml", result)
	}

	@Test
	fun `extractEmbeddedSpecUrl finds v3 api-docs url`() {
		val html = """
			<meta name="spec" content="https://api.example.com/v3/api-docs/main">
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractEmbeddedSpecUrl(html, "https://example.com")
		assertEquals("https://api.example.com/v3/api-docs/main", result)
	}

	@Test
	fun `extractEmbeddedSpecUrl returns null when no spec url found`() {
		val html = """<html><body><p>No spec here</p></body></html>"""
		assertNull(OpenAPIURLFetcher.extractEmbeddedSpecUrl(html, "https://example.com"))
	}

	// -- resolveRelativeUrl --

	@Test
	fun `resolveRelativeUrl returns absolute url unchanged`() {
		assertEquals(
			"https://api.example.com/spec.json",
			OpenAPIURLFetcher.resolveRelativeUrl("https://api.example.com/spec.json", "https://example.com"),
		)
	}

	@Test
	fun `resolveRelativeUrl resolves root-relative path`() {
		assertEquals(
			"https://example.com/api/spec.json",
			OpenAPIURLFetcher.resolveRelativeUrl("/api/spec.json", "https://example.com/docs/"),
		)
	}

	@Test
	fun `resolveRelativeUrl resolves relative path against base`() {
		assertEquals(
			"https://example.com/docs/spec.json",
			OpenAPIURLFetcher.resolveRelativeUrl("spec.json", "https://example.com/docs/index.html"),
		)
	}

	@Test
	fun `resolveRelativeUrl handles base url with port`() {
		assertEquals(
			"https://localhost:8080/api/openapi.json",
			OpenAPIURLFetcher.resolveRelativeUrl("/api/openapi.json", "https://localhost:8080/docs"),
		)
	}

	// -- sourceNameFromUrl --

	@Test
	fun `sourceNameFromUrl extracts host and path`() {
		assertEquals(
			"example.com/api/openapi.json",
			OpenAPIURLFetcher.sourceNameFromUrl("https://example.com/api/openapi.json"),
		)
	}

	@Test
	fun `sourceNameFromUrl strips trailing slash`() {
		assertEquals(
			"example.com/api",
			OpenAPIURLFetcher.sourceNameFromUrl("https://example.com/api/"),
		)
	}

	@Test
	fun `sourceNameFromUrl returns host only for root url`() {
		assertEquals(
			"example.com",
			OpenAPIURLFetcher.sourceNameFromUrl("https://example.com"),
		)
	}

	@Test
	fun `sourceNameFromUrl returns original on invalid url`() {
		val badUrl = "not a url at all"
		assertEquals(badUrl, OpenAPIURLFetcher.sourceNameFromUrl(badUrl))
	}

	// -- Real-world Swagger UI HTML patterns --

	@Test
	fun `discovers spec from typical Swagger UI HTML`() {
		val html = """
			<!DOCTYPE html>
			<html>
			<head>
				<title>Swagger UI</title>
				<link rel="stylesheet" type="text/css" href="./swagger-ui.css">
			</head>
			<body>
				<div id="swagger-ui"></div>
				<script src="./swagger-ui-bundle.js"></script>
				<script>
				window.onload = function() {
					const ui = SwaggerUIBundle({
						url: "./openapi.json",
						dom_id: '#swagger-ui',
						presets: [
							SwaggerUIBundle.presets.apis,
							SwaggerUIStandalonePreset
						],
						layout: "StandaloneLayout"
					})
				}
				</script>
			</body>
			</html>
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractSwaggerBundleUrl(html, "https://www.novalingo.com/api/docs/")
		assertNotNull(result)
		assertTrue(result.endsWith("openapi.json"))
	}

	@Test
	fun `discovers spec from Spring Boot Swagger HTML with v3 api-docs`() {
		val html = """
			<!DOCTYPE html>
			<html>
			<head><title>Swagger UI</title></head>
			<body>
				<div id="swagger-ui"></div>
				<script>
				SwaggerUIBundle({
					url: "/v3/api-docs",
					validatorUrl: ""
				})
				</script>
			</body>
			</html>
		""".trimIndent()
		val result = OpenAPIURLFetcher.extractSwaggerBundleUrl(html, "https://myapi.example.com/swagger-ui/")
		assertEquals("https://myapi.example.com/v3/api-docs", result)
	}

	// -- same-origin auth guard --

	@Test
	fun `isSameOrigin matches same scheme host and port`() {
		assertTrue(OpenAPIURLFetcher.isSameOrigin("https://api.example.com/spec.json", "https://api.example.com/docs"))
	}

	@Test
	fun `isSameOrigin treats default port as equal to explicit default`() {
		assertTrue(OpenAPIURLFetcher.isSameOrigin("https://api.example.com:443/spec.json", "https://api.example.com/docs"))
	}

	@Test
	fun `isSameOrigin rejects different host`() {
		assertFalse(OpenAPIURLFetcher.isSameOrigin("https://attacker.example/spec.json", "https://api.example.com/docs"))
	}

	@Test
	fun `isSameOrigin rejects different scheme`() {
		assertFalse(OpenAPIURLFetcher.isSameOrigin("http://api.example.com/spec.json", "https://api.example.com/docs"))
	}

	@Test
	fun `isSameOrigin rejects different port`() {
		assertFalse(OpenAPIURLFetcher.isSameOrigin("https://api.example.com:8443/spec.json", "https://api.example.com/docs"))
	}

	@Test
	fun `authForTarget keeps credentials for same-origin discovered URL`() {
		val auth = URLImportAuth.Bearer("token")
		val result = OpenAPIURLFetcher.authForTarget(
			"https://api.example.com/openapi.json",
			"https://api.example.com/docs",
			auth,
		)
		assertEquals(auth, result)
	}

	@Test
	fun `authForTarget drops credentials for cross-origin discovered URL`() {
		val auth = URLImportAuth.Bearer("token")
		val result = OpenAPIURLFetcher.authForTarget(
			"https://attacker.example/openapi.json",
			"https://api.example.com/docs",
			auth,
		)
		assertNull(result)
	}

	@Test
	fun `authForTarget passes through null auth`() {
		assertNull(OpenAPIURLFetcher.authForTarget("https://api.example.com/spec.json", "https://api.example.com/docs", null))
	}
}
