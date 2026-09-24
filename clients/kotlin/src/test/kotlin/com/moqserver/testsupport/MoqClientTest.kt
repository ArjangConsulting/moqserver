package com.moqserver.testsupport

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

class MoqClientTest {
    private data class Recorded(val method: String, val path: String, val session: String?, val auth: String?, val body: String)

    private lateinit var server: HttpServer
    private val recorded = CopyOnWriteArrayList<Recorded>()
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    private val history =
        """
        [
          {"id":"3","timestamp":3,"method":"GET","path":"/missing","status":404,"reason":"endpoint not found",
           "addons":{"jwt-claims":{"sub":"alice"}}},
          {"id":"2","timestamp":2,"method":"POST","path":"/ai","endpoint":"POST /ai","status":200,
           "variant":"success","reason":"declared default","callNumber":1,"futureField":true,
           "requestBody":{"value":"{\"action\":\"translate\"}","encoding":"utf8","size":22,"truncated":false}},
          {"id":"1","timestamp":1,"method":"GET","path":"/balance","status":404,"reason":"endpoint not found"}
        ]
        """.trimIndent()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val path = exchange.requestURI.rawPath
            recorded +=
                Recorded(
                    exchange.requestMethod, path, exchange.requestHeaders.getFirst("X-Mock-Session"),
                    exchange.requestHeaders.getFirst("Authorization"), body,
                )
            val (status, response) =
                when {
                    path == "/_admin/sessions" && exchange.requestMethod == "POST" -> 200 to """{"id":"s-1"}"""
                    path == "/_admin/requests" && exchange.requestMethod == "GET" -> 200 to history
                    path.contains("rejected") -> 401 to "unauthorized"
                    else -> 200 to "{}"
                }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `session client sends its id and admin auth on every call`() {
        val session = MoqClient(baseUrl, MoqClient.Auth.Bearer("admin")).createSession()
        assertEquals("s-1", session.sessionId)
        session.selectVariant("error", "get", "/users/{id}")
        session.closeSession()

        val select = recorded[1]
        assertEquals("PUT", select.method)
        assertEquals("/_admin/endpoints/GET/users/%7Bid%7D/variant", select.path)
        assertEquals("s-1", select.session)
        assertEquals("Bearer admin", select.auth)
        assertEquals("""{"variant":"error"}""", select.body)
        assertEquals(Recorded("DELETE", "/_admin/sessions/s-1", "s-1", "Bearer admin", ""), recorded[2])
    }

    @Test
    fun `activateScenario and resets hit the admin routes`() {
        val client = MoqClient(baseUrl)
        client.activateScenario("out-of-credits")
        client.resetAll()
        client.resetCallCount("POST", "/lessons/")
        assertEquals(
            listOf("PUT /_admin/scenario", "DELETE /_admin/state", "DELETE /_admin/endpoints/POST/lessons/call-count"),
            recorded.map { "${it.method} ${it.path}" },
        )
        assertEquals("""{"name":"out-of-credits"}""", recorded[0].body)
    }

    @Test
    fun `requests decode history, tolerating unknown fields`() {
        val records = MoqClient(baseUrl).requests()
        assertEquals(3, records.size)
        assertEquals(mapOf("jwt-claims" to mapOf("sub" to "alice")), records[0].addons)
        assertEquals("translate", records[1].requestBody?.jsonObject()?.get("action")?.jsonPrimitive?.content)
        assertNull(records[2].requestBody)
    }

    @Test
    fun `assertNoUnmatchedRequests lists unmatched paths oldest first`() {
        val error = assertFailsWith<MoqUnmatchedRequestsException> { MoqClient(baseUrl).assertNoUnmatchedRequests() }
        assertEquals(listOf("/balance", "/missing"), error.requests.map { it.path })
        assertTrue(error.message.orEmpty().contains("GET /balance"))
    }

    @Test
    fun `rejections are typed`() {
        val error = assertFailsWith<MoqControlException.Rejected> { MoqClient(baseUrl).resetAll("GET", "/rejected") }
        assertEquals(401, error.status)
        assertEquals("unauthorized", error.body)
    }

    @Test
    fun `readiness succeeds against a live server and respects the deadline otherwise`() {
        assertTrue(MoqClient(baseUrl).waitUntilReady(1_000))
        val port = server.address.port
        server.stop(0)
        val start = System.nanoTime()
        assertFalse(MoqClient("http://127.0.0.1:$port").waitUntilReady(100))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    }
}
