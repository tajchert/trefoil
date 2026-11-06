package com.tajchert.trefoil.security

import org.junit.Assert.*
import org.junit.Test

class SecretEnvelopeTest {
    @Test fun `envelope preserves nonce and ciphertext`() {
        val nonce = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(50) { (it + 5).toByte() }
        val decoded = SecretEnvelope.decode(SecretEnvelope.encode(nonce, ciphertext))
        assertArrayEquals(nonce, decoded.first)
        assertArrayEquals(ciphertext, decoded.second)
    }
    @Test fun `truncated envelopes rejected`() {
        for (size in 0..28) {
            assertThrows(IllegalArgumentException::class.java) { SecretEnvelope.decode(ByteArray(size)) }
        }
    }
}
