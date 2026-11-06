package com.tajchert.trefoil.security

/** Versioned AES-GCM envelope: version, 12-byte nonce, authenticated ciphertext and tag. */
internal object SecretEnvelope {
    fun encode(nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(nonce.size == 12 && ciphertext.size >= 16)
        return byteArrayOf(1) + nonce + ciphertext
    }
    fun decode(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid credential envelope" }
        return bytes.copyOfRange(1, 13) to bytes.copyOfRange(13, bytes.size)
    }
}
