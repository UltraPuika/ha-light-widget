package com.ultrapuika.halight.core

import java.net.URI
import java.net.URISyntaxException

/**
 * Validation and normalisation of the user-entered Home Assistant base URL.
 *
 * Plain `http://` is only accepted for hosts that are clearly on the local network or
 * inside a Tailscale tailnet (where WireGuard already encrypts the traffic). Anything
 * else must use `https://`, so the access token is never sent in clear text over the internet.
 */
object HaUrl {
    sealed class Result {
        data class Valid(val baseUrl: String, val isHttps: Boolean) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".ts.net")

    fun normalize(input: String): Result {
        var text = input.trim()
        if (text.isEmpty()) return Result.Invalid("Enter the Home Assistant URL, e.g. http://192.168.1.10:8123")
        if (!text.contains("://")) text = "http://$text"
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return Result.Invalid("Not a valid URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("URL must start with http:// or https://")
        val host = uri.host?.trim('[', ']')
        if (host.isNullOrEmpty()) return Result.Invalid("URL has no host name")
        if (uri.rawUserInfo != null) return Result.Invalid("Do not put user names or passwords in the URL")
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid("Remove the ?query or #fragment from the URL")
        if (scheme == "http" && !isPrivateHost(host)) {
            return Result.Invalid(
                "Plain http:// is only allowed for local or Tailscale addresses. Use https:// for \"$host\"."
            )
        }
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.endsWith("/api")) path = path.removeSuffix("/api")
        val hostPart = if (host.contains(':')) "[$host]" else host.lowercase()
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return Result.Valid("$scheme://$hostPart$port$path", scheme == "https")
    }

    /** True for loopback, RFC 1918, link-local, Tailscale CGNAT (100.64.0.0/10), ULA and LAN-style names. */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        parseIpv4(h)?.let { (a, b) ->
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }
        if (h.contains(':')) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
                h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
        }
        if (h == "localhost" || !h.contains('.')) return true
        return PRIVATE_SUFFIXES.any { h.endsWith(it) }
    }

    private fun parseIpv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.isNotEmpty() && p.all(Char::isDigit) } ?: return null }
        return nums[0] to nums[1]
    }
}
