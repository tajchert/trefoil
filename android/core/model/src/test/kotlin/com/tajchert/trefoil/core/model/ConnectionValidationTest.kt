package com.tajchert.trefoil.core.model

import org.junit.Assert.*
import org.junit.Test

class ConnectionValidationTest {
    @Test fun acceptsEncryptedOpenSshFixture() {
        val key = javaClass.getResource("/keys/ed25519_enc")!!.readText()
        assertNull(privateKeyValidationError(key))
    }
    @Test fun trimsHostAndUsernameBeforeValidation() {
        assertNull(sshInputValidationError(SshConnectionInput("Server", " host.local ", " alice ", password = "secret")))
        assertNotNull(sshInputValidationError(SshConnectionInput("Server", "host.local", "ali ce", password = "secret")))
    }
    @Test fun rejectsPublicAndMalformedPrivateKeysWithoutEchoingContent() {
        val secret = "ssh-ed25519 AAAA private-comment"
        assertTrue(privateKeyValidationError(secret)!!.contains("public key"))
        assertFalse(privateKeyValidationError(secret)!!.contains(secret))
        assertNotNull(privateKeyValidationError("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"))
        assertNotNull(privateKeyValidationError("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
    }
}
