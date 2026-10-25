package com.ultrapuika.halight.core

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "secret-token-123"

class HaClientTest {
    private class FakeTransport(var respond: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return respond(request)
        }
    }

    private fun client(t: HttpTransport) = HaClient("http://192.168.1.10:8123/", TOKEN, t)

    @Test
    fun checkConnection_sendsBearerTokenInHeaderOnly() {
        val t = FakeTransport { HttpResponse(200, """{"message":"API running."}""") }
        client(t).checkConnection()
        val r = t.requests.single()
        assertEquals("GET", r.method)
        assertEquals("http://192.168.1.10:8123/api/", r.url)
        assertEquals("Bearer $TOKEN", r.headers["Authorization"])
        assertFalse(r.url.contains(TOKEN))
    }

    @Test
    fun checkConnection_rejectsNonHomeAssistantServer() {
        val t = FakeTransport { HttpResponse(200, "<html>router login</html>") }
        val e = assertFailsWith<HaException> { client(t).checkConnection() }
        assertEquals(HaException.Kind.BAD_RESPONSE, e.kind)
    }

    @Test
    fun turnOn_postsBrightnessPct() {
        val t = FakeTransport { HttpResponse(200, "[]") }
        client(t).turnOn("light.kitchen", 37)
        val r = t.requests.single()
        assertEquals("POST", r.method)
        assertEquals("http://192.168.1.10:8123/api/services/light/turn_on", r.url)
        assertEquals("application/json", r.headers["Content-Type"])
        val body = JSONObject(r.body!!)
        assertEquals("light.kitchen", body.getString("entity_id"))
        assertEquals(37, body.getInt("brightness_pct"))
    }

    @Test
    fun turnOn_clampsBrightnessToValidRange() {
        val t = FakeTransport { HttpResponse(200, "[]") }
        client(t).turnOn("light.kitchen", 0)
        client(t).turnOn("light.kitchen", 250)
        assertEquals(listOf(1, 100), t.requests.map { JSONObject(it.body!!).getInt("brightness_pct") })
    }

    @Test
    fun turnOnPlainAndTurnOff_postEntityOnly() {
        val t = FakeTransport { HttpResponse(200, "[]") }
        client(t).turnOff("light.kitchen")
        client(t).turnOnPlain("light.kitchen")
        assertEquals("http://192.168.1.10:8123/api/services/light/turn_off", t.requests[0].url)
        assertEquals("http://192.168.1.10:8123/api/services/light/turn_on", t.requests[1].url)
        t.requests.forEach {
            val body = JSONObject(it.body!!)
            assertEquals("light.kitchen", body.getString("entity_id"))
            assertFalse(body.has("brightness_pct"))
        }
    }

    @Test
    fun getLight_readsState() {
        val t = FakeTransport {
            HttpResponse(200, """{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":26,"supported_color_modes":["brightness"]}}""")
        }
        val s = client(t).getLight("light.kitchen")
        assertEquals("http://192.168.1.10:8123/api/states/light.kitchen", t.requests.single().url)
        assertEquals(10, s.brightnessPct)
    }

    @Test
    fun listLights_usesStatesEndpoint() {
        val t = FakeTransport { HttpResponse(200, """[{"entity_id":"light.a","state":"off","attributes":{}}]""") }
        assertEquals(listOf("light.a"), client(t).listLights().map { it.entityId })
        assertEquals("http://192.168.1.10:8123/api/states", t.requests.single().url)
    }

    @Test
    fun rejectsEntityIdsThatCouldAlterThePath() {
        val t = FakeTransport { HttpResponse(200, "{}") }
        listOf("switch.x", "light.../../config", "light.a?x=1", "light.A", "").forEach { id ->
            val e = assertFailsWith<HaException>(id) { client(t).getLight(id) }
            assertEquals(HaException.Kind.INVALID_CONFIG, e.kind)
        }
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun mapsHttpStatusCodes() {
        val cases = mapOf(
            401 to HaException.Kind.UNAUTHORIZED,
            403 to HaException.Kind.UNAUTHORIZED,
            404 to HaException.Kind.NOT_FOUND,
            302 to HaException.Kind.REDIRECT,
            500 to HaException.Kind.HTTP,
            502 to HaException.Kind.HTTP,
        )
        cases.forEach { (code, kind) ->
            val t = FakeTransport { HttpResponse(code, "", "https://other.example.com/login?token=$TOKEN") }
            val e = assertFailsWith<HaException> { client(t).getLight("light.kitchen") }
            assertEquals(kind, e.kind, "HTTP $code")
            assertFalse(e.message!!.contains(TOKEN), "message must not leak the token")
        }
    }

    @Test
    fun mapsTransportExceptions() {
        val cases = listOf<Pair<IOException, HaException.Kind>>(
            UnknownHostException("ha.local") to HaException.Kind.UNREACHABLE,
            ConnectException("refused") to HaException.Kind.UNREACHABLE,
            SocketTimeoutException("timeout") to HaException.Kind.TIMEOUT,
            SSLHandshakeException("PKIX path building failed") to HaException.Kind.TLS,
            IOException("boom") to HaException.Kind.UNREACHABLE,
        )
        cases.forEach { (ex, kind) ->
            val e = assertFailsWith<HaException> { client(FakeTransport { throw ex }).checkConnection() }
            assertEquals(kind, e.kind, ex.javaClass.simpleName)
        }
    }

    @Test
    fun malformedJsonIsBadResponse() {
        val e = assertFailsWith<HaException> { client(FakeTransport { HttpResponse(200, "not json") }).getLight("light.kitchen") }
        assertEquals(HaException.Kind.BAD_RESPONSE, e.kind)
    }

    @Test
    fun constructorValidatesConfiguration() {
        assertEquals(HaException.Kind.INVALID_CONFIG, assertFailsWith<HaException> { HaClient("http://example.com", TOKEN) }.kind)
        assertEquals(HaException.Kind.INVALID_CONFIG, assertFailsWith<HaException> { HaClient("http://10.0.0.2:8123", " ") }.kind)
    }

    @Test
    fun toStringDoesNotContainToken() {
        assertFalse(client(FakeTransport { HttpResponse(200, "") }).toString().contains(TOKEN))
    }
}

/** Exercises [UrlConnectionTransport] against a real local HTTP server. */
class UrlConnectionTransportTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun start(configure: HttpServer.() -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.configure()
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun HttpServer.respond(path: String, code: Int, body: String, extra: Map<String, String> = emptyMap(), onRequest: (com.sun.net.httpserver.HttpExchange) -> Unit = {}) {
        createContext(path) { ex ->
            onRequest(ex)
            ex.requestBody.readBytes()
            extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
    }

    @Test
    fun endToEndAgainstFakeHomeAssistant() {
        var authHeader: String? = null
        var postedBody: String? = null
        val base = start {
            respond("/api/states/light.kitchen", 200, """{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":255,"supported_color_modes":["brightness"]}}""") {
                authHeader = it.requestHeaders.getFirst("Authorization")
            }
            createContext("/api/services/light/turn_on") { ex ->
                postedBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val bytes = "[]".toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        val client = HaClient(base, TOKEN, UrlConnectionTransport())
        assertEquals(100, client.getLight("light.kitchen").brightnessPct)
        assertEquals("Bearer $TOKEN", authHeader)
        client.turnOn("light.kitchen", 55)
        assertEquals(55, JSONObject(postedBody!!).getInt("brightness_pct"))
    }

    @Test
    fun invalidTokenIsReportedAsUnauthorized() {
        val base = start { respond("/api/", 401, "401: Unauthorized") }
        val e = assertFailsWith<HaException> { HaClient(base, "wrong", UrlConnectionTransport()).checkConnection() }
        assertEquals(HaException.Kind.UNAUTHORIZED, e.kind)
    }

    @Test
    fun redirectsAreNotFollowed() {
        var redirectedHit = false
        val base = start {
            respond("/api/", 301, "", mapOf("Location" to "/elsewhere/api/"))
            respond("/elsewhere/", 200, """{"message":"API running."}""") { redirectedHit = true }
        }
        val e = assertFailsWith<HaException> { HaClient(base, TOKEN, UrlConnectionTransport()).checkConnection() }
        assertEquals(HaException.Kind.REDIRECT, e.kind)
        assertFalse(redirectedHit, "the token must not be sent to the redirect target")
    }

    @Test
    fun closedPortIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<HaException> { HaClient("http://127.0.0.1:$port", TOKEN, UrlConnectionTransport()).checkConnection() }
        assertEquals(HaException.Kind.UNREACHABLE, e.kind)
    }

    @Test
    fun slowServerTimesOut() {
        val base = start {
            createContext("/api/") { ex -> Thread.sleep(1_500); ex.sendResponseHeaders(200, -1) }
        }
        val e = assertFailsWith<HaException> {
            HaClient(base, TOKEN, UrlConnectionTransport(connectTimeoutMs = 500, readTimeoutMs = 300)).checkConnection()
        }
        assertEquals(HaException.Kind.TIMEOUT, e.kind)
        assertNull(e.message?.takeIf { it.contains(TOKEN) })
    }
}
