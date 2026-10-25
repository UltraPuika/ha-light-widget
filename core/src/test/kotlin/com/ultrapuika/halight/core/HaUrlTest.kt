package com.ultrapuika.halight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HaUrlTest {
    private fun valid(input: String) = assertIs<HaUrl.Result.Valid>(HaUrl.normalize(input), input).baseUrl

    @Test
    fun normalisesCommonInputs() {
        assertEquals("http://192.168.1.10:8123", valid("192.168.1.10:8123"))
        assertEquals("http://192.168.1.10:8123", valid(" http://192.168.1.10:8123/ "))
        assertEquals("http://homeassistant.local:8123", valid("http://HomeAssistant.local:8123/api/"))
        assertEquals("https://ha.example.com", valid("https://ha.example.com"))
        assertEquals("https://ha.example.com/ha", valid("https://ha.example.com/ha/"))
        assertEquals("http://[fd7a:115c:a1e0::1]:8123", valid("http://[fd7a:115c:a1e0::1]:8123"))
    }

    @Test
    fun acceptsTailscaleAddressesOverHttp() {
        assertEquals("http://100.101.102.103:8123", valid("http://100.101.102.103:8123"))
        assertEquals("http://homeassistant.tail1234.ts.net:8123", valid("http://homeassistant.tail1234.ts.net:8123"))
        assertEquals("http://homeassistant:8123", valid("http://homeassistant:8123"))
    }

    @Test
    fun rejectsHttpToPublicHosts() {
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize("http://ha.example.com:8123"))
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize("http://8.8.8.8:8123"))
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize("http://100.128.0.1:8123"), "outside 100.64/10")
    }

    @Test
    fun rejectsMalformedInput() {
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize(""))
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize("ftp://192.168.1.2"))
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize("http://user:pw@192.168.1.2:8123"))
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize("http://192.168.1.2:8123/?token=abc"))
        assertIs<HaUrl.Result.Invalid>(HaUrl.normalize("http:// bad host"))
    }

    @Test
    fun privateHostClassification() {
        listOf("10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.0.1", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "100.127.255.255", "fd00::1", "fe80::1", "::1", "nas.lan", "ha.home.arpa", "localhost")
            .forEach { assertTrue(HaUrl.isPrivateHost(it), it) }
        listOf("172.32.0.1", "192.169.0.1", "100.63.0.1", "1.1.1.1", "example.com", "2001:db8::1")
            .forEach { assertFalse(HaUrl.isPrivateHost(it), it) }
    }
}
