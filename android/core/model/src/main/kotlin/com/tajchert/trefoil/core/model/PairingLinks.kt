package com.tajchert.trefoil.core.model

import java.net.URI
import java.net.URLDecoder

class PairingException(message: String) : IllegalArgumentException(message)

/** Bootstrap credentials must never appear in diagnostics. */
data class PairingTarget(val baseUrl: String, val token: String) {
    override fun toString() = "PairingTarget(credentials=***)"
}

/** Literal RFC1918 IPv4 only: hostnames are never resolved to grant cleartext access. */
fun isPrivateLanAddress(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it[0] == '0') }) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)
}

fun parsePairingLink(value: String): PairingTarget {
    try {
        var input = value.trim().replaceFirst(Regex("^(Pairing URL:|App link:)\\s*", RegexOption.IGNORE_CASE), "")
        var uri = URI(input)
        fun parameter(query: String?, name: String): String? = query?.split('&')?.firstNotNullOfOrNull { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            if (key == name) URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") else null
        }
        if (uri.scheme.equals("t3code", true)) {
            require(uri.host == "pair" && uri.userInfo == null && uri.port == -1 && uri.path.orEmpty().isEmpty())
            input = parameter(uri.rawQuery, "pairingUrl") ?: throw IllegalArgumentException()
            uri = URI(input)
        }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        require(uri.port in -1..65535 && uri.port != 0)
        val token = parameter(uri.rawFragment, "token") ?: parameter(uri.rawQuery, "token")
        require(!token.isNullOrBlank())
        val base = URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/", null, null).toASCIIString()
        return PairingTarget(base, token)
    } catch (_: Exception) {
        throw PairingException("Invalid pairing link. Copy the complete Pairing URL or App link, including its token.")
    }
}
