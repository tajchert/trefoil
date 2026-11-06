package com.tajchert.trefoil.core.model

import java.util.Base64

const val MAX_PRIVATE_KEY_BYTES = 256 * 1024

/** Returns fixed, credential-free messages; never includes input in diagnostics. */
fun privateKeyValidationError(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return "Import or paste a private key."
    if (text.toByteArray(Charsets.UTF_8).size > MAX_PRIVATE_KEY_BYTES) return "Private key must be at most 256 KiB."
    if (text.startsWith("ssh-") || text.startsWith("ecdsa-") || text.startsWith("sk-") || text.contains("PUBLIC KEY"))
        return "This is a public key. Choose the private key file without the .pub extension."
    val match = Regex("-----BEGIN (OPENSSH PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY|PRIVATE KEY|ENCRYPTED PRIVATE KEY)-----\\s*([\\s\\S]*?)\\s*-----END \\1-----").matchEntire(text)
        ?: return "Choose a valid OpenSSH or PEM private key."
    return try {
        val body = match.groupValues[2].lines().filterNot { it.contains(':') || it.isBlank() }.joinToString("").replace(" ", "")
        val bytes = Base64.getDecoder().decode(body)
        val valid = if (match.groupValues[1] == "OPENSSH PRIVATE KEY") {
            val reader = java.nio.ByteBuffer.wrap(bytes)
            val magic = ByteArray(15); reader.get(magic)
            fun string(): ByteArray { val length = reader.int; require(length >= 0 && length <= reader.remaining()); return ByteArray(length).also { reader.get(it) } }
            require(magic.contentEquals("openssh-key-v1\u0000".toByteArray()))
            val cipher = string(); string(); string()
            require(cipher.isNotEmpty() && reader.int == 1)
            require(string().isNotEmpty() && string().isNotEmpty() && !reader.hasRemaining())
            true
        } else {
            // PEM DER must contain one complete sequence. Encrypted traditional PEM
            // uses opaque encrypted blocks and carries explicit encryption headers.
            if (match.groupValues[2].contains("Proc-Type: 4,ENCRYPTED")) bytes.size >= 32
            else {
                require(bytes.size >= 16 && bytes[0] == 0x30.toByte())
                var offset = 2
                val first = bytes[1].toInt() and 255
                val length = if (first < 128) first else {
                    val count = first and 127; require(count in 1..3)
                    var size = 0; repeat(count) { size = (size shl 8) or (bytes[offset++].toInt() and 255) }; size
                }
                length == bytes.size - offset
            }
        }
        if (valid) null else "Choose a valid OpenSSH or PEM private key."
    } catch (_: Exception) { "Choose a valid OpenSSH or PEM private key." }
}

fun sshInputValidationError(input: SshConnectionInput): String? = when {
    input.name.isBlank() -> "Enter a display name."
    input.host.trim().isBlank() || input.host.trim().any { it.isWhitespace() || it.isISOControl() } || input.host.contains("://") -> "Enter a hostname or IP address without a URL scheme or spaces."
    input.username.trim().isBlank() || input.username.trim().any { it.isWhitespace() || it.isISOControl() } -> "Enter an SSH username without spaces."
    input.port !in 1..65535 -> "SSH port must be between 1 and 65535."
    input.authMethod == SshAuthMethod.Password && input.password.isEmpty() -> "Enter the SSH password."
    input.authMethod == SshAuthMethod.PrivateKey -> privateKeyValidationError(input.privateKey) ?: if (input.t3Home.isBlank()) "Enter the T3 data directory." else null
    input.t3Home.isBlank() -> "Enter the T3 data directory."
    else -> null
}
