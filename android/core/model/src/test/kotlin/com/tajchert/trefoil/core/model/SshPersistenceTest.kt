package com.tajchert.trefoil.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SshPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = SshHostKey("mac.local", 22, "ssh-ed25519", "SHA256:test")
    @Test fun `old direct environments still decode`() {
        val env = json.decodeFromString<SavedEnvironment>("""{"id":"env","name":"Mac","baseUrl":"https://mac.example/","accessToken":"token"}""")
        assertNull(env.ssh)
        assertEquals("https://mac.example/", env.baseUrl)
    }
    @Test fun `unsaved credentials never enter serialized SSH profile`() {
        val input = SshConnectionInput("Mac", "mac.local", "user", password = "secret-password")
        val env = SavedEnvironment("env", "Mac", "http://127.0.0.1:4567/", "token", input.savedProfile(key)).forStorage()
        val encoded = json.encodeToString(env)
        assertFalse(encoded.contains("secret-password"))
        assertFalse(encoded.contains("4567"))
        assertEquals("", env.baseUrl)
        assertEquals("mac.local", env.ssh?.host)
    }
    @Test fun `explicitly saved key credentials survive encrypted vault serialization`() {
        val input = SshConnectionInput("Mac", "mac.local", "user", authMethod = SshAuthMethod.PrivateKey,
            privateKey = "private-key", passphrase = "secret-passphrase", rememberCredentials = true)
        val profile = input.savedProfile(key)
        val decoded = json.decodeFromString<SshProfile>(json.encodeToString(profile))
        assertEquals("private-key", decoded.privateKey)
        assertEquals("secret-passphrase", decoded.passphrase)
        assertNull(decoded.password)
        assertFalse(decoded.toString().contains("private-key"))
    }
    @Test fun `direct endpoint is retained for storage`() {
        val env = SavedEnvironment("env", "Mac", "https://mac.example/", "token")
        assertEquals(env, env.forStorage())
        assertFalse(env.toString().contains("token"))
    }
}
