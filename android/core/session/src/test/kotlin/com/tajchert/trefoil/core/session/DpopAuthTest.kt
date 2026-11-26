package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class DpopAuthTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val key = JcaDpopKey(keyPair.private, keyPair.public as ECPublicKey)
    private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{}}"""
    private val renewingDescriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{"accessTokenRenewal":true}}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun dpopRejection(reason: String) = json("""{"_tag":"EnvironmentAuthInvalidError","code":"auth_invalid","reason":"invalid_credential","dpopFailureReason":"$reason","traceId":"t"}""")
        .setResponseCode(401).setHeader("WWW-Authenticate", "DPoP")
    private fun b64(value: String) = Base64.getUrlDecoder().decode(value)
    private fun part(proof: String, index: Int) = EffectRpc.json.parseToJsonElement(b64(proof.split(".")[index]).decodeToString()).jsonObject
    private fun ath(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
    private fun dpopEnvironment(url: String) = SavedEnvironment("env-1", "Test", url, "dpop-secret", tokenType = AccessTokenType.DPoP)

    /** Verifies a proof's ES256 signature against [key] after converting raw r‖s back to DER. */
    private fun verifies(proof: String): Boolean {
        val (header, payload, signature) = proof.split(".")
        val raw = b64(signature); assertEquals(64, raw.size)
        fun der(bytes: ByteArray) = BigInteger(1, bytes).toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
        val body = der(raw.copyOfRange(0, 32)) + der(raw.copyOfRange(32, 64))
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public); update("$header.$payload".encodeToByteArray()); verify(byteArrayOf(0x30, body.size.toByte()) + body)
        }
    }

    @Test fun proofMatchesT3Shape() {
        val proof = Dpop.proof(key, "post", "https://host.example:8443/oauth/token?drop=1#frag".toHttpUrl(), null, 1_700_000_000)
        val header = part(proof, 0); val payload = part(proof, 1)
        assertEquals("dpop+jwt", header.string("typ")); assertEquals("ES256", header.string("alg"))
        val jwk = header["jwk"]!!.jsonObject
        assertEquals(setOf("kty", "crv", "x", "y"), jwk.keys); assertEquals("EC", jwk.string("kty")); assertEquals("P-256", jwk.string("crv"))
        assertEquals(32, b64(jwk.string("x")).size); assertEquals(32, b64(jwk.string("y")).size)
        assertEquals("POST", payload.string("htm")); assertEquals("https://host.example:8443/oauth/token", payload.string("htu"))
        assertEquals(1_700_000_000L, payload["iat"]!!.jsonPrimitive.long); assertTrue(payload.string("jti").isNotBlank()); assertNull(payload["ath"])
        assertTrue(verifies(proof))
        val bound = Dpop.proof(key, "GET", "http://127.0.0.1:3773/api/auth/session".toHttpUrl(), "token", 1)
        assertEquals(ath("token"), part(bound, 1).string("ath"))
        assertNotEquals(part(bound, 1).string("jti"), part(Dpop.proof(key, "GET", "http://127.0.0.1:3773/".toHttpUrl(), "token", 1), 1).string("jti"))
        // RFC 7638 member order, as T3 hashes it.
        assertEquals(ath("""{"crv":"P-256","kty":"EC","x":"${jwk.string("x")}","y":"${jwk.string("y")}"}"""), key.publicJwk.thumbprint)
    }

    @Test fun derSignaturesBecomeFixedWidth() {
        // r with a sign byte, s shorter than 32 bytes.
        val r = ByteArray(32) { 0xff.toByte() }; val s = byteArrayOf(0x01, 0x02)
        val der = byteArrayOf(0x30, 39, 0x02, 33, 0x00) + r + byteArrayOf(0x02, 2) + s
        val raw = Dpop.derToRaw(der)
        assertEquals(64, raw.size); assertArrayEquals(r, raw.copyOfRange(0, 32)); assertArrayEquals(ByteArray(30) + s, raw.copyOfRange(32, 64))
    }

    @Test fun oldVaultJsonStaysBearer() {
        val old = """[{"id":"env-1","name":"Test","baseUrl":"https://host.example/","accessToken":"secret"}]"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(SavedEnvironment.serializer()), old).single()
        assertEquals(AccessTokenType.Bearer, decoded.tokenType); assertNull(decoded.accessTokenExpiresAtMillis)
        val dpop = decoded.copy(tokenType = AccessTokenType.DPoP, accessTokenExpiresAtMillis = 5)
        assertEquals(dpop, Json.decodeFromString(SavedEnvironment.serializer(), Json.encodeToString(SavedEnvironment.serializer(), dpop)))
        assertFalse(dpop.toString().contains("secret"))
    }

    @Test fun dpopPairingSendsProofAndSavesBoundToken() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, dpopKey = { key })
        try {
            server.enqueue(json(renewingDescriptor)); server.enqueue(json("""{"access_token":"dpop-secret","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","token_type":"DPoP","expires_in":3600,"scope":"orchestration:read"}"""))
            val before = System.currentTimeMillis()
            val saved = client.pair(server.url("/pair#token=bootstrap-secret").toString(), "Phone")
            assertEquals(AccessTokenType.DPoP, saved.tokenType); assertEquals("dpop-secret", saved.accessToken)
            assertTrue(saved.accessTokenExpiresAtMillis!! in (before + 3_599_000)..(System.currentTimeMillis() + 3_600_000))
            server.takeRequest()
            val exchange = server.takeRequest(); val proof = exchange.getHeader("DPoP")!!
            assertNull(exchange.getHeader("Authorization"))
            assertEquals("POST", part(proof, 1).string("htm")); assertEquals(server.url("/oauth/token").toString(), part(proof, 1).string("htu"))
            assertNull("The exchange proof has no access token yet", part(proof, 1)["ath"]); assertTrue(verifies(proof))
            // An older server that ignores the proof answers Bearer, and the saved environment stays bearer.
            server.enqueue(json(renewingDescriptor)); server.enqueue(json("""{"access_token":"bearer-secret","token_type":"Bearer"}"""))
            assertEquals(AccessTokenType.Bearer, client.pair(server.url("/pair#token=bootstrap-secret").toString(), "Phone").tokenType)
            server.takeRequest(); server.takeRequest()
            // A device whose key cannot sign keeps the one-time code for a bearer exchange.
            val keyless = T3Client(scope, dpopKey = { error("keystore unavailable") })
            server.enqueue(json(renewingDescriptor)); server.enqueue(json("""{"access_token":"bearer-secret","token_type":"Bearer"}"""))
            assertEquals(AccessTokenType.Bearer, keyless.pair(server.url("/pair#token=bootstrap-secret").toString(), "Phone").tokenType)
            server.takeRequest(); assertNull(server.takeRequest().getHeader("DPoP"))
            keyless.close()
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun defaultPairingStaysBearerAndSendsNoProof() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, dpopKey = { key })
        try {
            server.enqueue(json(descriptor)); server.enqueue(json("""{"access_token":"bearer-secret","token_type":"Bearer","expires_in":2592000}"""))
            val saved = client.pair(server.url("/pair#token=bootstrap-secret").toString(), "Phone")
            assertEquals(AccessTokenType.Bearer, saved.tokenType); assertNotNull(saved.accessTokenExpiresAtMillis)
            // A host without `accessTokenRenewal` gets no proof, so its session cannot strand the device after an hour.
            server.takeRequest(); assertNull(server.takeRequest().getHeader("DPoP"))
            // A DPoP token the client never asked for is refused.
            server.enqueue(json(descriptor)); server.enqueue(json("""{"access_token":"x","token_type":"DPoP"}"""))
            try { client.pair(server.url("/pair#token=bootstrap-secret").toString(), "Phone"); fail("Unrequested DPoP token") } catch (_: PairingException) { }
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun pairingRetriesOnceAfterClockSkew() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, dpopKey = { key })
        try {
            val serverNow = System.currentTimeMillis() + 3_600_000
            server.enqueue(json(renewingDescriptor)); server.enqueue(dpopRejection("time_window").setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(serverNow).atOffset(ZoneOffset.UTC))))
            server.enqueue(json("""{"access_token":"dpop-secret","token_type":"DPoP","expires_in":3600}"""))
            assertEquals(AccessTokenType.DPoP, client.pair(server.url("/pair#token=bootstrap-secret").toString(), "Phone").tokenType)
            server.takeRequest(); server.takeRequest()
            val retried = part(server.takeRequest().getHeader("DPoP")!!, 1)["iat"]!!.jsonPrimitive.long
            assertTrue("Retry adopts the server clock", kotlin.math.abs(retried - serverNow / 1000) < 30)
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun dpopConnectProvesEveryAuthenticatedRequestAndTheUpgrade() = runBlocking {
        val server = MockWebServer(); server.dispatcher = LinkStateTolerantQueue(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, dpopKey = { key })
        val listener = object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = EffectRpc.decode(text).single(); if (frame.string("_tag") != "Request") return
                val id = frame.string("id")
                when (frame.string("tag")) {
                    "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"providers":[]}}}""")
                    "subscribeServerConfig" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":{"providers":[]}}]}""")
                    "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
                }
            }
        }
        try {
            server.enqueue(json(descriptor))
            server.enqueue(dpopRejection("replay"))
            server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}"""))
            server.enqueue(json("""{"ticket":"rpc-ticket"}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(listener))
            client.connect(dpopEnvironment(server.url("/").toString()))
            withTimeout(5000) { client.state.first { it.connection == ConnectionStatus.Connected } }
            server.takeRequest()
            val rejected = server.takeRequest(5, TimeUnit.SECONDS)!!; val session = server.takeRequest(5, TimeUnit.SECONDS)!!
            val ticket = server.takeRequest(5, TimeUnit.SECONDS)!!; val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
            listOf(rejected, session, ticket).forEach { request ->
                assertEquals("DPoP dpop-secret", request.getHeader("Authorization"))
                val proof = request.getHeader("DPoP")!!; assertTrue(verifies(proof))
                assertEquals(request.method, part(proof, 1).string("htm"))
                assertEquals(request.requestUrl!!.newBuilder().query(null).build().toString(), part(proof, 1).string("htu"))
                assertEquals(ath("dpop-secret"), part(proof, 1).string("ath"))
            }
            assertEquals("/api/auth/session", session.path); assertEquals("/api/auth/websocket-ticket/rpc", ticket.path)
            assertNotEquals("Replay retry signs a fresh proof", part(rejected.getHeader("DPoP")!!, 1).string("jti"), part(session.getHeader("DPoP")!!, 1).string("jti"))
            val upgradeUrl = upgrade.requestUrl!!
            assertEquals("rpc-ticket", upgradeUrl.queryParameter("wsTicket"))
            val upgradeProof = upgradeUrl.queryParameter("dpop")!!; assertTrue(verifies(upgradeProof))
            assertEquals("GET", part(upgradeProof, 1).string("htm"))
            assertEquals(server.url("/ws").toString(), part(upgradeProof, 1).string("htu"))
            assertEquals(ath("rpc-ticket"), part(upgradeProof, 1).string("ath"))
            assertNull(upgrade.getHeader("Authorization"))
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun authDefiniteDpopFailureAndMissingKeyStopWithPairAgain() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, dpopKey = { key })
        try {
            // A rejected DPoP token renews once; a refused renewal is definite.
            server.enqueue(json(descriptor)); server.enqueue(dpopRejection("token_mismatch")); server.enqueue(dpopRejection("key_mismatch"))
            try { client.connect(dpopEnvironment(server.url("/").toString())); fail("A token mismatch is definite") }
            catch (error: PairingException) { assertTrue(error.message.orEmpty().contains("pair again")) }
            assertEquals("One renewal, no further retries", 3, server.requestCount)
            server.takeRequest(); server.takeRequest(); assertEquals("/oauth/token", server.takeRequest().path)
            val keyless = T3Client(scope, dpopKey = { error("keystore unavailable") })
            server.enqueue(json(descriptor))
            try { keyless.connect(dpopEnvironment(server.url("/").toString())); fail("A DPoP token without the key cannot be used") }
            catch (error: PairingException) { assertTrue(error.message.orEmpty().contains("Pair again")) }
            assertEquals("The token never leaves without its proof", 4, server.requestCount)
            keyless.close()
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun probeSendsDpopSession() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":true}"""))
            assertEquals(MachineProbe.Online, EnvironmentProbe(dpopKey = { key }).probe(dpopEnvironment(server.url("/").toString())))
            server.takeRequest(); val session = server.takeRequest()
            assertEquals("DPoP dpop-secret", session.getHeader("Authorization")); assertTrue(verifies(session.getHeader("DPoP")!!))
            server.enqueue(json(descriptor))
            assertEquals(MachineProbe.AuthFailed, EnvironmentProbe().probe(dpopEnvironment(server.url("/").toString())))
            server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":true}"""))
            assertEquals(MachineProbe.Online, EnvironmentProbe(dpopKey = { key }).probe(SavedEnvironment("env-1", "Test", server.url("/").toString(), "bearer-secret")))
            server.takeRequest(); server.takeRequest(); val bearer = server.takeRequest()
            assertEquals("Bearer bearer-secret", bearer.getHeader("Authorization")); assertNull(bearer.getHeader("DPoP"))
        } finally { server.shutdown() }
    }
}
