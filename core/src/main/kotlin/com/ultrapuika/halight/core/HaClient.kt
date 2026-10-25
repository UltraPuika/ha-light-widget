package com.ultrapuika.halight.core

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)

data class HttpResponse(val code: Int, val body: String, val location: String? = null)

fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

class HaException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { INVALID_CONFIG, UNAUTHORIZED, NOT_FOUND, UNREACHABLE, TIMEOUT, TLS, REDIRECT, HTTP, BAD_RESPONSE }
}

/**
 * Minimal blocking client for the Home Assistant REST API
 * (https://developers.home-assistant.io/docs/api/rest/).
 *
 * The token is only ever placed in the `Authorization` header. It never appears in URLs,
 * exception messages or [toString].
 */
class HaClient(
    baseUrl: String,
    private val token: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    private val base: String = when (val r = HaUrl.normalize(baseUrl)) {
        is HaUrl.Result.Valid -> r.baseUrl
        is HaUrl.Result.Invalid -> throw HaException(HaException.Kind.INVALID_CONFIG, r.reason)
    }

    init {
        if (token.isBlank()) throw HaException(HaException.Kind.INVALID_CONFIG, "Access token is empty")
    }

    /** `GET /api/` — verifies URL and token. */
    fun checkConnection() {
        val body = request("GET", "/api/", null)
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
        if (message.isNullOrEmpty()) {
            throw HaException(HaException.Kind.BAD_RESPONSE, "The server answered, but it does not look like the Home Assistant API")
        }
    }

    /** `GET /api/states/<entity_id>`. */
    fun getLight(entityId: String): LightState {
        val body = request("GET", "/api/states/${checkEntityId(entityId)}", null)
        return parse { HaJson.parseState(body) }
    }

    /** `GET /api/states`, filtered to `light.*`. */
    fun listLights(): List<LightState> {
        val body = request("GET", "/api/states", null)
        return parse { HaJson.parseLights(body) }
    }

    /** `POST /api/services/light/turn_on` with `brightness_pct` (1..100). */
    fun turnOn(entityId: String, brightnessPct: Int) {
        val payload = JSONObject()
            .put("entity_id", checkEntityId(entityId))
            .put("brightness_pct", Brightness.clampOnPct(brightnessPct))
        request("POST", "/api/services/light/turn_on", payload.toString())
    }

    /** `POST /api/services/light/turn_on` without brightness, for on/off-only lights. */
    fun turnOnPlain(entityId: String) {
        request("POST", "/api/services/light/turn_on", JSONObject().put("entity_id", checkEntityId(entityId)).toString())
    }

    /** `POST /api/services/light/turn_off`. */
    fun turnOff(entityId: String) {
        request("POST", "/api/services/light/turn_off", JSONObject().put("entity_id", checkEntityId(entityId)).toString())
    }

    override fun toString(): String = "HaClient(base=$base)"

    private fun request(method: String, path: String, body: String?): String {
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        }
        val response = try {
            transport.execute(HttpRequest(method, base + path, headers, body))
        } catch (e: HaException) {
            throw e
        } catch (e: SSLException) {
            throw HaException(HaException.Kind.TLS, "Secure connection failed: the server certificate is not trusted or HTTPS is not set up", e)
        } catch (e: UnknownHostException) {
            throw HaException(HaException.Kind.UNREACHABLE, "Host not found. Check the URL, Wi-Fi and that Tailscale is connected", e)
        } catch (e: InterruptedIOException) {
            throw HaException(HaException.Kind.TIMEOUT, "Home Assistant did not respond in time", e)
        } catch (e: ConnectException) {
            throw HaException(HaException.Kind.UNREACHABLE, "Cannot connect to Home Assistant. Check the URL, port and network/Tailscale", e)
        } catch (e: NoRouteToHostException) {
            throw HaException(HaException.Kind.UNREACHABLE, "No network route to Home Assistant. Is Tailscale/Wi-Fi connected?", e)
        } catch (e: IOException) {
            throw HaException(HaException.Kind.UNREACHABLE, "Network error: ${e.javaClass.simpleName}", e)
        }
        return when (response.code) {
            in 200..299 -> response.body
            401, 403 -> throw HaException(HaException.Kind.UNAUTHORIZED, "Access token was rejected (expired or revoked?)")
            404 -> throw HaException(HaException.Kind.NOT_FOUND, if (path.startsWith("/api/states/")) "Entity not found in Home Assistant" else "Home Assistant API not found at this URL")
            in 300..399 -> throw HaException(
                HaException.Kind.REDIRECT,
                "Server redirected to ${response.location?.let(::redactLocation) ?: "another address"}. Enter that address as the URL",
            )
            else -> throw HaException(HaException.Kind.HTTP, "Home Assistant returned HTTP ${response.code}")
        }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw HaException(HaException.Kind.BAD_RESPONSE, "Unexpected response from Home Assistant", e)
    }

    companion object {
        private val ENTITY_ID = Regex("^light\\.[a-z0-9_]+$")

        fun checkEntityId(entityId: String): String {
            if (!ENTITY_ID.matches(entityId)) throw HaException(HaException.Kind.INVALID_CONFIG, "Invalid light entity id")
            return entityId
        }

        /** Keeps only scheme, host and port of a redirect target. */
        private fun redactLocation(location: String): String =
            runCatching { URI(location).toURL().let { "${it.protocol}://${it.host}${if (it.port != -1) ":${it.port}" else ""}" } }
                .getOrDefault("another address")
    }
}

/**
 * [HttpTransport] backed by [HttpURLConnection]. Uses the platform's default TLS
 * certificate validation (never disabled) and does not follow redirects, so the
 * `Authorization` header cannot leak to a different host.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                conn.doOutput = true
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResponse(code, body, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }
}
