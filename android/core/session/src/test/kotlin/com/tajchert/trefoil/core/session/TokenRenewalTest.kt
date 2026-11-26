package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class TokenRenewalTest {
    /** A hang fails in seconds instead of parking the test worker. */
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val key = JcaDpopKey(keyPair.private, keyPair.public as ECPublicKey)
    private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{"accessTokenRenewal":true}}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun renewal(token: String) = json("""{"access_token":"$token","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","token_type":"DPoP","expires_in":3600,"scope":"orchestration:read"}""")
    private fun part(proof: String, index: Int) = EffectRpc.json.parseToJsonElement(Base64.getUrlDecoder().decode(proof.split(".")[index]).decodeToString()).jsonObject
    private fun form(request: RecordedRequest) = request.body.readUtf8().split("&").associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
    private fun environment(url: String, expiresAt: Long?) =
        SavedEnvironment("env-1", "Test", url, "old-secret", tokenType = AccessTokenType.DPoP, accessTokenExpiresAtMillis = expiresAt)
    private class Saved(val renewed: SavedEnvironment, val replaces: Set<String>)

    private val socketListener = object : WebSocketListener() {
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

    /** Requests until [path] arrives (link-state and others may interleave after connect). */
    private fun MockWebServer.takeUntil(path: String): RecordedRequest {
        while (true) { val request = takeRequest(5, TimeUnit.SECONDS) ?: error("No $path request"); if (request.path == path) return request }
    }

    @Test fun expiredTokenRenewsOnConnectWithT3RequestShapeAndPersists() = runBlocking {
        val server = MockWebServer(); server.dispatcher = LinkStateTolerantQueue(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saved = CopyOnWriteArrayList<Saved>()
        val tokens = EnvironmentTokenRenewer({ key }, onRenewed = { renewed, replaces -> saved += Saved(renewed, replaces) })
        val client = T3Client(scope, tokens = tokens)
        try {
            server.enqueue(json(descriptor))
            server.enqueue(renewal("new-secret"))
            server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}"""))
            server.enqueue(json("""{"ticket":"rpc-ticket"}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(socketListener))
            val before = System.currentTimeMillis()
            // Expired an hour ago: the session still renews, so connect renews before using the token.
            client.connect(environment(server.url("/").toString(), before - 3_600_000))
            withTimeout(5000) { client.state.first { it.connection == ConnectionStatus.Connected } }
            server.takeRequest()
            val renew = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", renew.method); assertEquals("/oauth/token", renew.path)
            assertNull("The subject token travels in the body only", renew.getHeader("Authorization"))
            val fields = form(renew)
            assertEquals(mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
                "subject_token" to "old-secret",
                "subject_token_type" to "urn:ietf:params:oauth:token-type:access_token",
                "requested_token_type" to "urn:ietf:params:oauth:token-type:access_token",
            ), fields)
            val proof = part(renew.getHeader("DPoP")!!, 1)
            assertEquals("POST", proof.string("htm")); assertEquals(server.url("/oauth/token").toString(), proof.string("htu")); assertNull(proof["ath"])
            assertEquals("DPoP new-secret", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals("DPoP new-secret", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            val stored = saved.single()
            assertEquals("new-secret", stored.renewed.accessToken); assertEquals(AccessTokenType.DPoP, stored.renewed.tokenType)
            assertTrue(stored.renewed.accessTokenExpiresAtMillis!! >= before + 3_599_000)
            assertTrue("old-secret" in stored.replaces); assertFalse("new-secret" in stored.replaces)
            assertEquals("The live environment carries the renewed token", "new-secret", client.state.value.environment?.accessToken)
            assertEquals("new-secret", tokens.current(environment("x", 0)).accessToken)
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun liveConnectionRenewsBeforeExpiryWithoutReconnecting() = runBlocking {
        val server = MockWebServer(); server.dispatcher = LinkStateTolerantQueue(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, dpopKey = { key })
        try {
            server.enqueue(json(descriptor))
            server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}"""))
            server.enqueue(json("""{"ticket":"rpc-ticket"}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(socketListener))
            server.enqueue(renewal("new-secret"))
            // Not due at connect; due about a second later.
            client.connect(environment(server.url("/").toString(), System.currentTimeMillis() + EnvironmentTokenRenewer.RENEW_BEFORE_EXPIRY_MS + 1_000))
            withTimeout(5000) { client.state.first { it.connection == ConnectionStatus.Connected } }
            assertEquals("DPoP old-secret", server.takeUntil("/api/auth/session").getHeader("Authorization"))
            withTimeout(10_000) { client.state.first { it.environment?.accessToken == "new-secret" } }
            assertEquals("old-secret", form(server.takeUntil("/oauth/token"))["subject_token"])
            assertEquals("The socket keeps its session", ConnectionStatus.Connected, client.state.value.connection)
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun dpopRejectionRenewsOnceAndRetries() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(json(descriptor))
            server.enqueue(json("""{"_tag":"EnvironmentAuthInvalidError","code":"auth_invalid","reason":"invalid_credential"}""").setResponseCode(401).setHeader("WWW-Authenticate", "DPoP"))
            server.enqueue(renewal("new-secret"))
            server.enqueue(json("""{"authenticated":true}"""))
            // Unknown expiry: only the host's rejection renews it.
            assertEquals(MachineProbe.Online, EnvironmentProbe(dpopKey = { key }).probe(environment(server.url("/").toString(), null)))
            server.takeRequest()
            assertEquals("DPoP old-secret", server.takeRequest().getHeader("Authorization"))
            assertEquals("old-secret", form(server.takeRequest())["subject_token"])
            assertEquals("DPoP new-secret", server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun concurrentCallersShareOneRenewal() = runBlocking {
        val server = MockWebServer(); server.start()
        val saved = CopyOnWriteArrayList<Saved>()
        val tokens = EnvironmentTokenRenewer({ key }, onRenewed = { renewed, replaces -> saved += Saved(renewed, replaces) })
        try {
            server.enqueue(renewal("new-secret").setBodyDelay(300, TimeUnit.MILLISECONDS))
            server.enqueue(renewal("unexpected"))
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            val results = (1..4).map { async(Dispatchers.Default) { tokens.fresh(expired, server.url("/")) } }.awaitAll()
            assertEquals(List(4) { "new-secret" }, results.map { it.accessToken })
            assertEquals("Callers behind the renewal reuse it", 1, server.requestCount); assertEquals(1, saved.size)
            // Every caller rejected with the same old token also reuses the renewal instead of renewing again.
            assertEquals("new-secret", tokens.fresh(expired, server.url("/"), rejectedToken = "old-secret").accessToken)
            assertEquals(1, server.requestCount)
            // A newer pairing's token is not mapped onto the old session's renewal.
            assertEquals("paired-again", tokens.fresh(expired.copy(accessToken = "paired-again", accessTokenExpiresAtMillis = null), server.url("/")).accessToken)
        } finally { server.shutdown() }
    }

    @Test fun slowStoreNeverHoldsUpTheNextRenewalAndTheNewestTokenIsStoredLast() = runBlocking {
        val server = MockWebServer(); server.start()
        val saved = CopyOnWriteArrayList<Saved>()
        val storing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val tokens = EnvironmentTokenRenewer({ key }, onRenewed = { renewed, replaces ->
            if (renewed.accessToken == "first") { storing.complete(Unit); release.await() }
            saved += Saved(renewed, replaces)
        })
        try {
            server.enqueue(renewal("first")); server.enqueue(renewal("second"))
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            val first = async(Dispatchers.Default) { tokens.fresh(expired, server.url("/")) }
            withTimeout(5_000) { storing.await() }
            assertEquals("Adopted before it is stored", "first", tokens.current(expired).accessToken)
            // The host refuses "first" while it is still being stored: the next renewal must not wait for that store.
            val second = async(Dispatchers.Default) { tokens.fresh(expired, server.url("/"), rejectedToken = "first") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val renewAgain = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("first", form(renewAgain!!)["subject_token"])
            // The host saw the request before the client read the answer: wait for the adoption, still before any store.
            withTimeout(5_000) { while (tokens.current(expired).accessToken != "second") delay(10) }
            assertTrue("The newer store waits behind the older one", saved.isEmpty())
            release.complete(Unit)
            assertEquals("first", withTimeout(5_000) { first.await() }.accessToken)
            assertEquals("second", withTimeout(5_000) { second.await() }.accessToken)
            assertEquals("Stores run in order, newest last", listOf("first", "second"), saved.map { it.renewed.accessToken })
            assertEquals(setOf("old-secret", "first"), saved.last().replaces)
        } finally { release.complete(Unit); server.shutdown() } // a failed assertion must not leave the store parked
    }

    @Test fun storeHeldUpAcrossManyRenewalsStillReplacesTheStoredToken() = runBlocking {
        val server = MockWebServer(); server.start()
        val vault = java.util.concurrent.atomic.AtomicReference("old-secret")
        val storing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        // Like the app's vault: a renewal replaces the stored token only when that token is one of `replaces`.
        val tokens = EnvironmentTokenRenewer({ key }, onRenewed = { renewed, replaces ->
            if (renewed.accessToken == "t1") { storing.complete(Unit); release.await() }
            vault.updateAndGet { if (it in replaces) renewed.accessToken else it }
        })
        try {
            (1..10).forEach { server.enqueue(renewal("t$it")) }
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            val calls = mutableListOf(async(Dispatchers.Default) { tokens.fresh(expired, server.url("/")) })
            withTimeout(5_000) { storing.await() }
            // The host refuses each newest token in turn while the first store is still held up.
            for (n in 2..10) {
                val previous = expired.copy(accessToken = "t${n - 1}")
                calls += async(Dispatchers.Default) { tokens.fresh(previous, server.url("/"), rejectedToken = previous.accessToken) }
                withTimeout(5_000) { while (tokens.current(previous).accessToken != "t$n") delay(5) }
            }
            release.complete(Unit)
            withTimeout(5_000) { calls.awaitAll() }
            assertEquals("The newest token reaches the vault", "t10", vault.get())
        } finally { release.complete(Unit); server.shutdown() }
    }

    @Test fun unconfirmedStoresKeepTheChainBackToTheStoredToken() = runBlocking {
        val server = MockWebServer(); server.start()
        val sent = CopyOnWriteArrayList<Saved>()
        // The store holds nothing new until t10 (e.g. its writes failed): each store says so.
        val tokens = EnvironmentTokenRenewer({ key }, storeRenewed = { renewed, replaces -> sent += Saved(renewed, replaces); renewed.accessToken == "t10" })
        try {
            (1..11).forEach { server.enqueue(renewal("t$it")) }
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            tokens.fresh(expired, server.url("/"))
            for (n in 2..10) tokens.fresh(expired.copy(accessToken = "t${n - 1}"), server.url("/"), rejectedToken = "t${n - 1}")
            assertEquals((1..10).map { "t$it" }, sent.map { it.renewed.accessToken })
            assertTrue("Still replaces the token actually stored", "old-secret" in sent.last().replaces)
            tokens.fresh(expired.copy(accessToken = "t10"), server.url("/"), rejectedToken = "t10")
            assertTrue("Confirmed: the chain starts again from t10", "t10" in sent.last().replaces)
        } finally { server.shutdown() }
    }

    @Test fun cancelledRenewalAbortsTheCallAndReleasesTheLock() = runBlocking {
        val server = MockWebServer(); server.start()
        val saved = CopyOnWriteArrayList<Saved>()
        val tokens = EnvironmentTokenRenewer({ key }, onRenewed = { renewed, replaces -> saved += Saved(renewed, replaces) })
        try {
            server.enqueue(renewal("late-secret").setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            val caller = launch(Dispatchers.Default) { tokens.fresh(expired, server.url("/")) }
            // Once the request is at the server (and has taken the unanswered response), the route probe gives up: the
            // renewal must stop with it, not run out its own 15 s timeout.
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            caller.cancel()
            assertNotNull("Cancellation ends the call promptly", withTimeoutOrNull(3_000) { caller.join() })
            assertTrue(caller.isCancelled)
            server.enqueue(renewal("new-secret"))
            val next = withTimeout(3_000) { tokens.fresh(expired, server.url("/")) }
            assertEquals("The lock is free for the next caller", "new-secret", next.accessToken)
            assertEquals(listOf("new-secret"), saved.map { it.renewed.accessToken })
        } finally { server.shutdown() }
    }

    @Test fun responseReceivedBeforeCancellationIsStillStored() = runBlocking {
        val server = MockWebServer(); server.start()
        val saved = CopyOnWriteArrayList<Saved>()
        lateinit var caller: Job
        // The response body is fully read, then the caller is cancelled before the renewer sees the result.
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyEnd(call: Call, byteCount: Long) { caller.cancel() }
        }).build()
        val tokens = EnvironmentTokenRenewer({ key }, http, onRenewed = { renewed, replaces -> saved += Saved(renewed, replaces) })
        try {
            server.enqueue(renewal("new-secret"))
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            // Assigned before it starts, so the listener always sees it.
            caller = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { tokens.fresh(expired, server.url("/")) }
            caller.start(); caller.join()
            assertEquals(listOf("new-secret"), saved.map { it.renewed.accessToken })
            assertEquals(setOf("old-secret"), saved.single().replaces)
            assertEquals("new-secret", tokens.current(expired).accessToken)
        } finally { server.shutdown() }
    }

    @Test fun lateRenewalOfAnOlderSessionIsNotAdoptedByANewerPairing() = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tokens = EnvironmentTokenRenewer({ key })
        val client = T3Client(scope, tokens = tokens)
        @Suppress("UNCHECKED_CAST")
        val mutable = T3Client::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(client) as kotlinx.coroutines.flow.MutableStateFlow<ClientState>
        val adopt = T3Client::class.java.getDeclaredMethod("adoptToken", SavedEnvironment::class.java).apply { isAccessible = true }
        try {
            server.enqueue(renewal("new-secret"))
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            // A renewal of the old session finishes for a caller that no longer matters...
            val renewed = tokens.fresh(expired, server.url("/"))
            // ...while the same machine is already live under a newer pairing.
            val paired = expired.copy(accessToken = "paired-again", accessTokenExpiresAtMillis = System.currentTimeMillis() + 3_600_000)
            mutable.value = ClientState(environment = paired, connection = ConnectionStatus.Connected)
            adopt.invoke(client, renewed)
            assertEquals("paired-again", client.state.value.environment?.accessToken)
            // The same session's live copy still adopts its renewal.
            mutable.value = ClientState(environment = expired, connection = ConnectionStatus.Connected)
            adopt.invoke(client, renewed)
            assertEquals("new-secret", client.state.value.environment?.accessToken)
        } finally { client.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun ownCallTimeoutWithAnActiveCallerIsUnavailableNotCancelled() = runBlocking {
        val server = MockWebServer(); server.start()
        // OkHttp's call timeout cancels the call itself; the caller is still active and must get a retriable failure.
        val tokens = EnvironmentTokenRenewer({ key }, timeoutMillis = 200)
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
            val outcome = runCatching { tokens.fresh(expired, server.url("/")) }.exceptionOrNull()
            assertTrue("Got $outcome", outcome is TokenRenewalUnavailable)
            assertTrue(isActive)
        } finally { server.shutdown() }
    }

    @Test fun refusalIsDefiniteAndNetworkFailureIsRetriable() = runBlocking {
        val server = MockWebServer(); server.start()
        val tokens = EnvironmentTokenRenewer({ key })
        val expired = environment(server.url("/").toString(), System.currentTimeMillis() - 1)
        try {
            // Session expired or revoked: T3 answers invalid credential.
            server.enqueue(json("""{"_tag":"EnvironmentAuthInvalidError","code":"auth_invalid","reason":"invalid_credential"}""").setResponseCode(401))
            try { tokens.fresh(expired, server.url("/")); fail("Refused renewal") } catch (_: TokenRenewalRejected) { }
            server.enqueue(MockResponse().setResponseCode(503))
            try { tokens.fresh(expired, server.url("/")); fail("Server error") } catch (_: TokenRenewalUnavailable) { }
            // Host unreachable (nothing listens on the port).
            val gone = MockWebServer().apply { start() }; val goneUrl = gone.url("/"); gone.shutdown()
            try { tokens.fresh(expired, goneUrl); fail("Unreachable host") } catch (_: TokenRenewalUnavailable) { }
            // A failed attempt is not cached: the next one renews.
            server.enqueue(renewal("new-secret"))
            assertEquals("new-secret", tokens.fresh(expired, server.url("/")).accessToken)
        } finally { server.shutdown() }
    }

    @Test fun connectKeepsUnexpiredTokenOnNetworkFailureAndAsksToPairOnlyWhenRefused() = runBlocking {
        val server = MockWebServer(); server.dispatcher = LinkStateTolerantQueue(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, dpopKey = { key })
        try {
            // Due but unexpired, renewal fails: the current token still connects.
            server.enqueue(json(descriptor)); server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(json("""{"authenticated":true,"permissions":[]}""")); server.enqueue(json("""{"ticket":"rpc-ticket"}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(socketListener))
            client.connect(environment(server.url("/").toString(), System.currentTimeMillis() + 30_000))
            withTimeout(5000) { client.state.first { it.connection == ConnectionStatus.Connected } }
            assertEquals("DPoP old-secret", server.takeUntil("/api/auth/session").getHeader("Authorization"))
            client.disconnect(); server.close()

            // Expired and the host is unreachable for renewal: retry later, not pair again.
            val offline = MockWebServer(); offline.start()
            try {
                offline.enqueue(json(descriptor)); offline.enqueue(MockResponse().setResponseCode(502))
                try { client.connect(environment(offline.url("/").toString(), System.currentTimeMillis() - 1)); fail("No usable token") }
                catch (error: PairingException) { assertFalse(error.message.orEmpty().contains("pair again", ignoreCase = true)); assertTrue(error.message.orEmpty().contains("renew")) }
                // Expired and refused: pair again.
                offline.enqueue(json(descriptor)); offline.enqueue(json("""{"code":"auth_invalid"}""").setResponseCode(401))
                try { client.connect(environment(offline.url("/").toString(), System.currentTimeMillis() - 1)); fail("Refused") }
                catch (error: PairingException) { assertTrue(error.message.orEmpty().contains("pair again")) }
            } finally { offline.shutdown() }
        } finally { client.close(); scope.cancel(); runCatching { server.shutdown() } }
    }

    @Test fun learnedServerClockStaysWithItsHost() {
        val authorizer = EnvironmentAuthorizer({ key })
        val skewed = "https://skewed.example/api/auth/session".toHttpUrl()
        val serverNow = System.currentTimeMillis() + 3_600_000
        val rejection = Response.Builder().request(Request.Builder().url(skewed).build()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
            .header("WWW-Authenticate", "DPoP").header("Date", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(serverNow).atOffset(java.time.ZoneOffset.UTC)))
            .body("""{"dpopFailureReason":"time_window"}""".toResponseBody()).build()
        assertTrue(authorizer.shouldRetry(rejection))
        fun iat(url: String) = part(authorizer.proof("GET", url.toHttpUrl(), null), 1)["iat"]!!.jsonPrimitive.long
        assertTrue("The skewed host gets its clock", kotlin.math.abs(iat("https://skewed.example/ws") - serverNow / 1000) < 30)
        assertTrue("Another host keeps the device clock", kotlin.math.abs(iat("https://other.example/api/auth/session") - System.currentTimeMillis() / 1000) < 30)
    }
}
