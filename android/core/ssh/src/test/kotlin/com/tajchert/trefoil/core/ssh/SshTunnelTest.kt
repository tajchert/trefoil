package com.tajchert.trefoil.core.ssh

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.common.keyprovider.KeyPairProvider
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.Assert.*

class SshTunnelTest {
    @Test fun untrustedAndChangedKeysNeverAuthenticate() = runBlocking {
        val attempts = AtomicInteger()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair())
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, _, _ -> attempts.incrementAndGet(); true }
            start()
        }
        try {
            val input = SshConnectionInput("Test", "127.0.0.1", "user", server.port, password = "never-send")
            for (pin in listOf(null, "SHA256:wrong")) {
                try { SshTunnelConnector().open(input, pin); fail("Expected host trust rejection") }
                catch (e: SshHostKeyException) {
                    assertEquals(pin != null, e.changed)
                    assertTrue(e.presented.fingerprint.startsWith("SHA256:"))
                }
            }
            assertEquals(0, attempts.get())
        } finally { server.stop(true) }
    }
    @Test fun publicKeyRejectedBeforeOpeningSsh() = runBlocking {
        try {
            SshTunnelConnector().open(SshConnectionInput("Test", "127.0.0.1", "user", 1,
                SshAuthMethod.PrivateKey, privateKey = "ssh-ed25519 AAAA fixture-public-key"), null)
            fail("A public key must never reach SSH authentication")
        } catch (e: SshConnectionException) {
            assertTrue(e.message.orEmpty().contains("public key", ignoreCase = true))
            assertFalse(e.message.orEmpty().contains("AAAA"))
        }
    }
    @Test fun quotesInjectionAsOneLiteralArgument() {
        assertEquals("'a'\\''; $(touch bad)'", remoteQuote("a'; $(touch bad)"))
        assertEquals("\"\$HOME\"/'a b'", remotePath("~/a b"))
    }
}
