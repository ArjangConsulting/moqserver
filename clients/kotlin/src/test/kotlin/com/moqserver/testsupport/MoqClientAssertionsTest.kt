package com.moqserver.testsupport

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MoqClientAssertionsTest {
    private data class Sent(val path: String, val session: String?, val body: String)

    private lateinit var server: HttpServer
    private val sent = CopyOnWriteArrayList<Sent>()
    private val responses = ConcurrentLinkedQueue<String>()
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    private val passing =
        """
        {"ok":true,"count":2,"expected":2,"message":"Found 2 request(s) to GET /v1/me, expected exactly 2.",
         "nearMisses":[],
         "unattributed":[{"id":"9","timestamp":9,"method":"GET","path":"/v1/anon","status":428,"reason":"missing session",
                          "rejection":{"kind":"session","code":"session_required","message":"Missing X-Mock-Session header"}}],
         "matched":[
          {"id":"1","timestamp":1,"method":"GET","path":"/v1/me","endpoint":"GET /v1/me","status":200,"reason":"declared default",
           "addons":{"jwt-claims":{"sub":"u1","iat":"100"}},
           "requestHeaders":[{"name":"authorization","truncated":false,"redacted":true,"scheme":"Bearer","fingerprint":"aaaaaaaaaaaaaaaa"}]},
          {"id":"2","timestamp":2,"method":"GET","path":"/v1/me","endpoint":"GET /v1/me","status":200,"reason":"declared default",
           "addons":{"jwt-claims":{"sub":"u1","iat":"200"}}}
         ]}
        """.trimIndent()

    private val failing =
        """
        {"ok":false,"count":1,"expected":0,"nearMisses":[],"unattributed":[],
         "message":"Expected exactly 0 request(s) matching [rejected], found 1.\nMatched:\n  GET /v1/me → 400 (request_rule missing_required_header: Header 'Authorization' is required)",
         "matched":[{"id":"1","timestamp":1,"method":"GET","path":"/v1/me","endpoint":"GET /v1/me","status":400,
           "reason":"missing_required_header","requestHeaders":[{"name":"authorization","truncated":false,"redacted":true}],
           "rejection":{"kind":"request_rule","code":"missing_required_header","message":"Header 'Authorization' is required"}}]}
        """.trimIndent()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            sent +=
                Sent(
                    exchange.requestURI.rawPath, exchange.requestHeaders.getFirst("X-Mock-Session"),
                    exchange.requestBody.readBytes().decodeToString(),
                )
            val bytes = (responses.poll() ?: "{}").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun body(index: Int = 0): JsonObject = Json.parseToJsonElement(sent[index].body).jsonObject

    @Test
    fun `assertRequest sends predicates and returns matches oldest first`() {
        responses += passing
        val calls =
            MoqClient(baseUrl, sessionId = "s1").assertRequest(
                "GET /v1/me", times = 2,
                headers =
                    listOf(
                        MoqHeaderExpectation.Scheme("Authorization", "Bearer"),
                        MoqHeaderExpectation.Absent("Cookie"),
                        MoqHeaderExpectation.Fingerprint("Authorization", "tok"),
                    ),
                claims = mapOf("sub" to "u1"),
            )

        assertEquals(listOf("100", "200"), calls.map { it.claims["iat"] })
        assertEquals("aaaaaaaaaaaaaaaa", calls.first().header("Authorization")?.fingerprint)
        assertEquals("/_admin/requests/query", sent[0].path)
        assertEquals("s1", sent[0].session)
        val query = body()
        assertEquals("GET /v1/me", query["endpoint"]?.jsonPrimitive?.content)
        assertEquals(2, query["times"]?.jsonPrimitive?.int)
        assertEquals("u1", query["claims"]?.jsonObject?.get("sub")?.jsonPrimitive?.content)
        val headers = query["headers"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Bearer", headers[0]["scheme"]?.jsonPrimitive?.content)
        assertFalse(headers[1]["present"]!!.jsonPrimitive.boolean)
        assertEquals("tok", headers[2]["fingerprintOf"]?.jsonPrimitive?.content)
        assertFalse(query.containsKey("scope"))
    }

    @Test
    fun `a failed assertion throws the server verdict`() {
        responses += failing
        val error = assertFailsWith<MoqRequestAssertionException> { MoqClient(baseUrl).assertNoRejectedRequests() }
        assertTrue(error.message.orEmpty().contains("missing_required_header"))
        assertEquals("request_rule", error.result.matched.single().rejection?.kind)
        assertTrue(body()["rejected"]!!.jsonPrimitive.boolean)
        assertEquals(0, body()["times"]?.jsonPrimitive?.int)
    }

    @Test
    fun `assertRequest polls until the timeout`() {
        responses += failing
        responses += passing
        assertEquals(2, MoqClient(baseUrl).assertRequest("GET /v1/me", timeoutMillis = 2_000).size)
        assertEquals(2, sent.size)
    }

    @Test
    fun `session check sends the sessionless scope and returns unattributed rows`() {
        responses += passing
        val unattributed = MoqClient(baseUrl, sessionId = "s1").assertAllRequestsCarrySession(mapOf("sub" to "u1"))
        assertEquals(listOf("/v1/anon"), unattributed.map { it.path })
        assertEquals("sessionless", body()["scope"]?.jsonPrimitive?.content)
        assertFalse(body().containsKey("includeUnattributed"))
    }

    @Test
    fun `limited variant selection sends times`() {
        MoqClient(baseUrl).selectVariant("unauthorized", "GET", "/v1/me", times = 1)
        assertEquals("/_admin/endpoints/GET/v1/me/variant", sent.single().path)
        assertEquals("""{"variant":"unauthorized","times":1}""", sent.single().body)
    }

    @Test
    fun `records decode headers and rejection`() {
        responses += failing
        val rejected = MoqClient(baseUrl).rejectedRequests().single()
        assertTrue(rejected.isRejected)
        assertFalse(rejected.isUnmatched)
        assertEquals(true, rejected.header("authorization")?.redacted)
    }

    @Test
    fun `serverInfo decodes and requireCapabilities names what is missing`() {
        responses += """{"version":"1.2.3","commit":"abc","capabilities":["request-query"]}"""
        val info = MoqClient(baseUrl).serverInfo()
        assertEquals("1.2.3", info.version)
        assertTrue(info.supports("request-query"))
        assertEquals("/_admin/version", sent.single().path)

        responses += """{"version":null,"commit":null,"capabilities":["request-query"]}"""
        val error =
            assertFailsWith<MoqMissingCapabilitiesException> {
                MoqClient(baseUrl).requireCapabilities("request-query", "limited-overrides")
            }
        assertEquals(listOf("limited-overrides"), error.missing)
        assertTrue(error.message.orEmpty().contains("unreleased build"))
    }

    @Test
    fun `suspending assertions mirror the blocking ones`() =
        runBlocking {
            responses += failing
            responses += passing
            val calls = MoqClient(baseUrl).suspending.assertRequest("GET /v1/me", times = 2, timeoutMillis = 2_000)
            assertEquals(2, calls.size)
            responses += """{"timestamp":12.5}"""
            assertEquals(12.5, MoqClient(baseUrl).suspending.mark())
        }
}
