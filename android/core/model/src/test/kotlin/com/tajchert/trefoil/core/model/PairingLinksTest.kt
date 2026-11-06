package com.tajchert.trefoil.core.model

import org.junit.Assert.*
import org.junit.Test

class PairingLinksTest {
    @Test fun lanConsentIsPersistedAndOldEnvironmentsDefaultToSecure() {
        val json = kotlinx.serialization.json.Json
        val environment = SavedEnvironment("fixture", "Phone", "http://192.168.1.20/", "fixture-token", allowInsecureHttp = true)
        assertTrue(json.decodeFromString<SavedEnvironment>(json.encodeToString(SavedEnvironment.serializer(), environment)).allowInsecureHttp)
        assertFalse(json.decodeFromString<SavedEnvironment>("""{"id":"fixture","name":"Phone","baseUrl":"http://192.168.1.20/","accessToken":"fixture-token"}""").allowInsecureHttp)
    }

    @Test fun parsesCopiedDirectAndWrappedLinks() {
        val direct = "http://192.168.1.20:3773/pair#token=fixture-token"
        val parsed = parsePairingLink("  Pairing URL: $direct  ")
        assertEquals("http://192.168.1.20:3773/", parsed.baseUrl)
        assertEquals("fixture-token", parsed.token)
        assertEquals(parsed, parsePairingLink("App link: t3code://pair?pairingUrl=" + java.net.URLEncoder.encode(direct, "UTF-8")))
        assertFalse(parsed.toString().contains("fixture-token"))
        assertEquals("query-fixture", parsePairingLink("https://example.test/pair?token=query-fixture").token)
    }
    @Test fun classifiesOnlyLiteralRfc1918Addresses() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1").forEach { assertTrue(it, isPrivateLanAddress(it)) }
        listOf("172.15.1.1", "172.32.1.1", "127.0.0.1", "169.254.1.1", "100.64.1.1", "8.8.8.8", "192.168.1.1.example.test", "010.0.0.1", "10.1", "0xa000001", "localhost", "::1").forEach { assertFalse(it, isPrivateLanAddress(it)) }
    }
    @Test fun invalidLinksHaveSanitizedErrors() {
        listOf("https://user:fixture-token@example.test/pair#token=fixture-token", "t3code://other?pairingUrl=fixture-token", "https://example.test/#token=", "invalid-fixture-token").forEach {
            try { parsePairingLink(it); fail("Expected invalid link") } catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("fixture-token")) }
        }
    }
}
