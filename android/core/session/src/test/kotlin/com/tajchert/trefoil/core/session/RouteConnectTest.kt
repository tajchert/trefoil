package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Multi-route connect, route hints and preflight against isolated fixture hosts (no real threads). */
class RouteConnectTest {
    /** A hang fails in seconds instead of parking the test worker. */
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<T3Client>()

    @After fun stop() {
        clients.forEach { it.close() }
        scope.cancel()
        servers.forEach { runCatching { it.shutdown() } }
    }

    /** A fixture T3 host answering by path; records every request so tests can check where the bearer went. */
    private inner class Host(
        val environmentId: String = "env-1",
        val session: MockResponse = json("""{"authenticated":true,"permissions":["orchestration:operate"]}"""),
        val linkState: () -> MockResponse = { json("", 404) },
        val config: String = """{"providers":[]}""",
        val descriptor: () -> MockResponse = { json("""{"environmentId":"$environmentId","orchestrationProtocolVersion":2}""") },
    ) {
        val server = MockWebServer().also { servers += it }
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val socket = CompletableDeferred<WebSocket>()
        val configStream = CompletableDeferred<String>()
        val dispatched = Channel<String>(Channel.UNLIMITED)
        var answerDispatch = true
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    return when {
                        path == "/.well-known/t3/environment" -> descriptor()
                        path == "/api/auth/session" -> session
                        path.startsWith("/api/auth/websocket-ticket") -> json("""{"ticket":"fixture-ticket"}""")
                        path == "/api/connect/link-state" -> linkState()
                        path.startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(listener)
                        else -> json("", 404)
                    }
                }
            }
            server.start()
        }
        val url: String get() = server.url("/").toString()
        fun route(kind: RouteKind, source: RouteSource = RouteSource.Learned) = EnvironmentRoute(kind, url, source)
        fun tokenSent() = requests.any { it.getHeader("Authorization") != null }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = EffectRpc.decode(text).single()
                if (frame.string("_tag") != "Request") return
                val id = frame.string("id")
                when (frame.string("tag")) {
                    "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$config}}""")
                    "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
                    // The first snapshot is the connection's config; tests send later events on the stream.
                    "subscribeServerConfig" -> { webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":$config}]}"""); configStream.complete(id) }
                    "orchestration.subscribeThread" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":{"thread":{"id":"t"},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[]}},{"kind":"synchronized"}]}""")
                    "orchestration.dispatchCommand" -> {
                        dispatched.trySend(id)
                        if (answerDispatch) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"sequence":1}}}""")
                    }
                }
            }
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun client(timeout: Long = 2_000, settle: Long = 10_000) = T3Client(scope, routeTimeoutMillis = { timeout }, directEndpointsSettleMillis = settle).also { clients += it }
    private fun environment(baseUrl: String) = SavedEnvironment("env-1", "Test", baseUrl, "fixture-token")
    private fun refusedUrl(): String { val dead = MockWebServer(); dead.start(); val url = dead.url("/").toString(); dead.shutdown(); return url }

    @Test fun bestRankedVerifiedRouteWinsAndOnlyItGetsTheToken() = runBlocking {
        val lan = Host(); val tunnel = Host()
        val client = client()
        val routes = listOf(EnvironmentRoute(RouteKind.Lan, refusedUrl(), RouteSource.Paired), lan.route(RouteKind.Tailnet), tunnel.route(RouteKind.Tunnel))
        client.connect(environment(routes.first().baseUrl), routes)
        assertEquals(ConnectionStatus.Connected, client.state.value.connection)
        assertEquals(routes[1], client.state.value.activeRoute)
        assertEquals("The saved environment keeps its paired address", routes.first().baseUrl, client.state.value.environment?.baseUrl)
        assertTrue(lan.requests.any { it.path == "/api/auth/session" && it.getHeader("Authorization") == "Bearer fixture-token" })
        assertFalse("A later route only ever sees the credential-free descriptor", tunnel.tokenSent())
        assertTrue(tunnel.requests.all { it.path == "/.well-known/t3/environment" })
    }

    private fun delayed(environmentId: String, millis: Long): () -> MockResponse = {
        json("""{"environmentId":"$environmentId","orchestrationProtocolVersion":2}""").setHeadersDelay(millis, TimeUnit.MILLISECONDS)
    }

    @Test fun silentBetterRouteDoesNotHoldAVerifiedOneForItsTimeout() = runBlocking {
        val silent = Host(descriptor = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }); val tunnel = Host()
        val client = client(timeout = 5_000)
        val started = System.nanoTime()
        client.connect(environment(silent.url), listOf(silent.route(RouteKind.Lan, RouteSource.Paired), tunnel.route(RouteKind.Tunnel)))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(RouteKind.Tunnel, client.state.value.activeRoute?.kind)
        assertTrue("Settles after the head start, not the 5 s timeout ($elapsed ms)", elapsed in ROUTE_HEAD_START_MS until 3_000)
        assertFalse(silent.tokenSent())
    }

    @Test fun betterRouteAnsweringWithinTheHeadStartWins() = runBlocking {
        val lan = Host(descriptor = delayed("env-1", 150)); val tunnel = Host()
        val client = client(timeout = 5_000)
        client.connect(environment(lan.url), listOf(lan.route(RouteKind.Lan, RouteSource.Paired), tunnel.route(RouteKind.Tunnel)))
        assertEquals(RouteKind.Lan, client.state.value.activeRoute?.kind)
        assertTrue(lan.tokenSent())
        assertFalse("The faster but worse route never gets the token", tunnel.tokenSent())
    }

    @Test fun betterRouteAnsweringAfterTheHeadStartLoses() = runBlocking {
        val lan = Host(descriptor = delayed("env-1", ROUTE_HEAD_START_MS + 1_500)); val tunnel = Host()
        val client = client(timeout = 5_000)
        client.connect(environment(lan.url), listOf(lan.route(RouteKind.Lan, RouteSource.Paired), tunnel.route(RouteKind.Tunnel)))
        assertEquals(RouteKind.Tunnel, client.state.value.activeRoute?.kind)
        delay(2_000)
        assertFalse("The cancelled loser never gets the token", lan.tokenSent())
    }

    @Test fun losingRouteStuckInDnsNeverHoldsUpTheWinnerAndItsTimeoutEndsTheWait() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        // Cancelling an OkHttp call does not reach a lookup in progress: this one only returns when released.
        val dns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (hostname == "stuck.test") { release.await(); throw java.net.UnknownHostException(hostname) }
                return Dns.SYSTEM.lookup(hostname)
            }
        }
        val http = OkHttpClient.Builder().dns(dns).build()
        try {
            val tunnel = Host()
            val stuck = EnvironmentRoute(RouteKind.Lan, "https://stuck.test/", RouteSource.Paired)
            val client = T3Client(scope, routeTimeoutMillis = { 10_000 }, http = http).also { clients += it }
            val connecting = async(Dispatchers.Default) { client.connect(environment(stuck.baseUrl), listOf(stuck, tunnel.route(RouteKind.Tunnel))) }
            assertNotNull("The verified route connects without waiting for the stuck lookup", withTimeoutOrNull(3_000) { connecting.await() })
            assertEquals(RouteKind.Tunnel, client.state.value.activeRoute?.kind)

            val alone = T3Client(scope, routeTimeoutMillis = { 300 }, http = http).also { clients += it }
            val failing = async(Dispatchers.Default) { runCatching { alone.connect(environment(stuck.baseUrl), listOf(stuck)) } }
            val outcome = withTimeoutOrNull(3_000) { failing.await() }
            assertNotNull("The route timeout ends a stuck lookup", outcome)
            assertTrue(outcome!!.exceptionOrNull() is PairingException)
            assertTrue(outcome.exceptionOrNull()!!.message.orEmpty().contains("timed out"))
        } finally { release.countDown() }
    }

    @Test fun dpopLosingRoutesSeeOnlyTheCredentialFreeDescriptor() = runBlocking {
        val keyPair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = JcaDpopKey(keyPair.private, keyPair.public as java.security.interfaces.ECPublicKey)
        val lan = Host(); val tailnet = Host(); val tunnel = Host()
        val client = T3Client(scope, routeTimeoutMillis = { 2_000 }, dpopKey = { key }).also { clients += it }
        val environment = SavedEnvironment("env-1", "Test", lan.url, "fixture-token", tokenType = AccessTokenType.DPoP,
            accessTokenExpiresAtMillis = System.currentTimeMillis() + 3_600_000)
        client.connect(environment, listOf(lan.route(RouteKind.Lan, RouteSource.Paired), tailnet.route(RouteKind.Tailnet), tunnel.route(RouteKind.Tunnel)))
        assertEquals(RouteKind.Lan, client.state.value.activeRoute?.kind)
        assertTrue(lan.requests.any { it.path == "/api/auth/session" && it.getHeader("Authorization") == "DPoP fixture-token" && it.getHeader("DPoP") != null })
        for (loser in listOf(tailnet, tunnel)) {
            assertTrue("Only the descriptor", loser.requests.all { it.path == "/.well-known/t3/environment" })
            assertTrue("No credential or proof", loser.requests.none { it.getHeader("Authorization") != null || it.getHeader("DPoP") != null })
        }
    }

    @Test fun failedSessionReadFallsBackToTheNextVerifiedRoute() = runBlocking {
        val lan = Host(session = json("{}", 503)); val tunnel = Host()
        val client = client()
        client.connect(environment(lan.url), listOf(lan.route(RouteKind.Lan, RouteSource.Paired), tunnel.route(RouteKind.Tunnel)))
        assertEquals(RouteKind.Tunnel, client.state.value.activeRoute?.kind)
        assertEquals(ConnectionStatus.Connected, client.state.value.connection)
        val down = Host(session = json("{}", 503)); val stranger = Host(environmentId = "someone-else")
        try { client.connect(environment(down.url), listOf(stranger.route(RouteKind.Lan), down.route(RouteKind.Tunnel))); fail("No usable route") }
        catch (error: PairingException) { assertEquals("The last route's failure is reported", "Server HTTP error 503", error.message) }
        assertFalse(stranger.tokenSent())
    }

    @Test fun identityMismatchSkipsTheRouteWithoutSendingTheToken() = runBlocking {
        val stranger = Host(environmentId = "someone-else"); val tunnel = Host()
        val client = client()
        client.connect(environment(stranger.url), listOf(stranger.route(RouteKind.Lan, RouteSource.Paired), tunnel.route(RouteKind.Tunnel)))
        assertEquals(RouteKind.Tunnel, client.state.value.activeRoute?.kind)
        assertEquals(listOf("/.well-known/t3/environment"), stranger.requests.map { it.path })
        assertFalse("The bearer never reaches a route that answered as another machine", stranger.tokenSent())
    }

    @Test fun rejectedTokenStopsBeforeLaterRoutes() = runBlocking {
        val lan = Host(session = json("""{"error":"unauthorized"}""", 401)); val tunnel = Host()
        val client = client()
        try { client.connect(environment(lan.url), listOf(lan.route(RouteKind.Lan, RouteSource.Paired), tunnel.route(RouteKind.Tunnel))); fail("A rejected token must stop") }
        catch (error: PairingException) { assertEquals("Authorization expired or denied; pair again", error.message) }
        assertFalse("The token is dead on every route of the host", tunnel.tokenSent())
        assertEquals(ConnectionStatus.Error, client.state.value.connection)
        assertNull(client.state.value.activeRoute)
        val forbidden = Host(session = json("{}", 403)); val other = Host()
        try { client.connect(environment(forbidden.url), listOf(forbidden.route(RouteKind.Lan), other.route(RouteKind.Tunnel))); fail("403 must stop") } catch (_: PairingException) { }
        assertFalse(other.tokenSent())
    }

    @Test fun allRoutesFailingNamesTheLastFailure() = runBlocking {
        val stranger = Host(environmentId = "someone-else")
        val silent = Host(descriptor = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
        val client = client(timeout = 300)
        try { client.connect(environment(stranger.url), listOf(stranger.route(RouteKind.Lan), silent.route(RouteKind.Tunnel))); fail("No route answered") }
        catch (error: PairingException) { assertEquals("T3 connection timed out. Check the server and network, then try again.", error.message) }
        try { client.connect(environment(silent.url), listOf(silent.route(RouteKind.Lan), stranger.route(RouteKind.Tunnel))); fail("No route answered") }
        catch (error: PairingException) { assertEquals("Environment identity changed; pair again", error.message) }
        assertFalse(stranger.tokenSent() || silent.tokenSent())
        assertEquals(ConnectionStatus.Error, client.state.value.connection)
    }

    @Test fun urlPolicyStillGuardsEveryRoute() = runBlocking {
        val tunnel = Host()
        val client = client()
        val blocked = listOf(EnvironmentRoute(RouteKind.Lan, "http://192.168.1.20:3773/", RouteSource.Learned), EnvironmentRoute(RouteKind.Public, "http://8.8.8.8/", RouteSource.Learned))
        client.connect(environment(tunnel.url), blocked + tunnel.route(RouteKind.Tunnel))
        assertEquals(RouteKind.Tunnel, client.state.value.activeRoute?.kind)
        try { client.connect(environment(tunnel.url), blocked); fail("Blocked routes are never contacted") }
        catch (error: PairingException) { assertTrue(error.message.orEmpty().startsWith("Use HTTPS or SSH")) }
    }

    @Test fun aFailedConnectSaysWhetherTheMachineWasOnlyUnreachable() = runBlocking {
        val client = client(timeout = 300)
        suspend fun failure(environment: SavedEnvironment, routes: List<EnvironmentRoute>): ConnectFailure =
            try { client.connect(environment, routes); fail("The connect must fail"); error("unreachable") } catch (error: PairingException) { error.failure }
        // Silent, refused and server errors before the session check are reachability failures.
        val silent = Host(descriptor = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
        assertEquals(ConnectFailure.Unreachable, failure(environment(silent.url), listOf(silent.route(RouteKind.Lan))))
        val broken = Host(descriptor = { json("oops", 502) })
        val closed = Host().also { it.server.shutdown() }
        assertEquals(ConnectFailure.Unreachable, failure(environment(broken.url), listOf(broken.route(RouteKind.Lan), closed.route(RouteKind.Tunnel))))
        val failingSession = Host(session = json("oops", 500))
        assertEquals(ConnectFailure.Unreachable, failure(environment(failingSession.url), listOf(failingSession.route(RouteKind.Lan))))
        // The descriptor answers in time but the saved sign-in is rejected: actionable, never "offline".
        val rejected = Host(session = json("""{"error":"unauthorized"}""", 401))
        assertEquals(ConnectFailure.AuthRejected, failure(environment(rejected.url), listOf(rejected.route(RouteKind.Lan))))
        val signedOut = Host(session = json("""{"authenticated":false}"""))
        assertEquals(ConnectFailure.AuthRejected, failure(environment(signedOut.url), listOf(signedOut.route(RouteKind.Lan))))
        // A refused address or another machine needs the user, even when another route was merely silent.
        val blocked = EnvironmentRoute(RouteKind.Public, "http://8.8.8.8/", RouteSource.Learned)
        assertEquals(ConnectFailure.UrlPolicy, failure(environment(silent.url), listOf(blocked, silent.route(RouteKind.Tunnel))))
        val stranger = Host(environmentId = "someone-else")
        assertEquals(ConnectFailure.Identity, failure(environment(silent.url), listOf(stranger.route(RouteKind.Lan), silent.route(RouteKind.Tunnel))))
        // The session check passed and a later step failed.
        val noTicket = Host().also { host ->
            val base = host.server.dispatcher
            host.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path.orEmpty().startsWith("/api/auth/websocket-ticket")) json("oops", 500) else base.dispatch(request)
            }
        }
        assertEquals(ConnectFailure.AfterSession, failure(environment(noTicket.url), listOf(noTicket.route(RouteKind.Lan))))
    }

    @Test fun singleRouteConnectKeepsClassifiedPairedRoute() = runBlocking {
        val host = Host()
        val client = client()
        client.connect(environment(host.url))
        assertEquals(EnvironmentRoute(RouteKind.Lan, host.url, RouteSource.Paired), client.state.value.activeRoute)
        val stranger = Host(environmentId = "someone-else")
        try { client.connect(environment(stranger.url)); fail("Identity mismatch") } catch (_: PairingException) { }
        assertFalse(stranger.tokenSent())
    }

    @Test fun linkStateMapsToTunnelHints() {
        fun hint(body: String) = tunnelHint(Json.parseToJsonElement(body).jsonObject)
        assertEquals(TunnelHint.Active("prod-fixture.t3coderelay.com"), hint("""{"linked":true,"managedTunnelActive":true,"tunnelHostname":"prod-fixture.t3coderelay.com","relayUrl":null,"future":1}"""))
        assertEquals("managedTunnelActive may be absent", TunnelHint.Active("prod-fixture.t3coderelay.com"), hint("""{"linked":true,"tunnelHostname":"prod-fixture.t3coderelay.com"}"""))
        assertEquals("Older servers omit tunnelHostname", TunnelHint.Unknown, hint("""{"linked":true,"managedTunnelActive":true}"""))
        assertEquals(TunnelHint.Unknown, hint("""{"linked":true,"managedTunnelActive":false,"tunnelHostname":"prod-fixture.t3coderelay.com"}"""))
        assertEquals(TunnelHint.Unknown, hint("""{"linked":true,"tunnelHostname":"  "}"""))
        assertEquals(TunnelHint.NotLinked, hint("""{"linked":false,"tunnelHostname":"prod-fixture.t3coderelay.com"}"""))
        assertEquals(TunnelHint.NotLinked, hint("""{"linked":true,"linkRevoked":true,"tunnelHostname":"prod-fixture.t3coderelay.com"}"""))
        assertEquals(TunnelHint.Unknown, hint("""{}"""))
        assertEquals(TunnelHint.Unknown, hint("""{"linked":"true","tunnelHostname":"prod-fixture.t3coderelay.com"}"""))
    }

    @Test fun directEndpointsAreParsedTolerantly() {
        fun parse(body: String) = directEndpointHints(Json.parseToJsonElement(body).jsonObject)
        assertNull(parse("""{"providers":[]}"""))
        assertEquals(emptyList<DirectEndpointHint>(), parse("""{"directEndpoints":[]}"""))
        assertEquals(listOf(DirectEndpointHint("lan", "http://192.168.1.20:3773"), DirectEndpointHint("vpn", "https://box.example.test")),
            parse("""{"directEndpoints":[{"kind":"lan","httpBaseUrl":"http://192.168.1.20:3773","extra":true},{"kind":"lan"},{"httpBaseUrl":"https://x.test"},7,{"kind":"vpn","httpBaseUrl":"https://box.example.test"}]}"""))
    }

    @Test fun connectEmitsOneHintWithDirectEndpointsAndLinkState() = runBlocking {
        val host = Host(config = """{"providers":[],"directEndpoints":[{"kind":"lan","httpBaseUrl":"http://192.168.1.20:3773"}]}""",
            linkState = { json("""{"linked":true,"managedTunnelActive":true,"tunnelHostname":"prod-fixture.t3coderelay.com","publishAgentActivity":false}""") })
        val client = client()
        val hints = scope.async { withTimeout(5_000) { client.routeHints.first() } }
        yield(); delay(50)
        client.connect(environment(host.url))
        // The first config snapshot's endpoints may predate T3's network discovery: they only add routes.
        assertEquals(RouteHints("env-1", listOf(DirectEndpointHint("lan", "http://192.168.1.20:3773")), TunnelHint.Active("prod-fixture.t3coderelay.com"), additive = true), hints.await())
        val linkState = host.requests.single { it.path == "/api/connect/link-state" }
        assertEquals("Bearer fixture-token", linkState.getHeader("Authorization"))
    }

    @Test fun missingLinkStateAndEndpointsGiveUnknownHintsWithoutFailing() = runBlocking {
        val host = Host(linkState = { json("""{"error":"not found"}""", 404) })
        val client = client()
        val hints = scope.async { withTimeout(5_000) { client.routeHints.first() } }
        yield(); delay(50)
        client.connect(environment(host.url))
        assertEquals(RouteHints("env-1", null, TunnelHint.Unknown, additive = true), hints.await())
        assertEquals(ConnectionStatus.Connected, client.state.value.connection)
        val silent = Host(linkState = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
        val late = scope.async { withTimeout(8_000) { client.routeHints.first() } }
        yield(); delay(50)
        client.connect(environment(silent.url))
        assertEquals("A silent link-state times out as Unknown", RouteHints("env-1", null, TunnelHint.Unknown, additive = true), late.await())
        assertEquals(ConnectionStatus.Connected, client.state.value.connection)
    }

    @Test fun configSnapshotsEmitOnlyChangedDirectEndpointsAndNeverDisturbTheSession() = runBlocking {
        val first = """[{"kind":"lan","httpBaseUrl":"http://192.168.1.20:3773"}]"""
        val host = Host(config = """{"providers":[],"directEndpoints":$first}""")
        val client = client()
        val received = Channel<RouteHints>(Channel.UNLIMITED)
        val collector = scope.launch { client.routeHints.collect { received.send(it) } }
        yield(); delay(50)
        client.connect(environment(host.url))
        assertTrue("The first snapshot's list is provisional", withTimeout(5_000) { received.receive() }.also { assertEquals(TunnelHint.Unknown, it.tunnel) }.additive)
        withTimeout(5_000) { client.state.first { it.canWrite } }
        val socket = host.socket.await(); val stream = host.configStream.await()
        fun chunk(vararg values: String) = socket.send("""{"_tag":"Chunk","requestId":"$stream","values":[${values.joinToString(",")}]}""")
        chunk("""{"version":1,"type":"snapshot","config":{"providers":[],"directEndpoints":$first}}""")
        chunk("""{"version":1,"type":"providerStatuses","payload":{"providers":[]}}""", """{"version":2,"type":"future.event","payload":{}}""")
        chunk("""{"version":1,"type":"snapshot","config":{"providers":[]}}""")
        chunk("""{"version":1,"type":"snapshot","config":{"providers":[],"directEndpoints":[{"kind":"tailnet","httpBaseUrl":"https://box.tail.ts.net"}]}}""")
        val changed = withTimeout(5_000) { received.receive() }
        assertEquals("A later snapshot's list is complete", RouteHints("env-1", listOf(DirectEndpointHint("tailnet", "https://box.tail.ts.net")), TunnelHint.Unknown), changed)
        delay(100)
        assertTrue("Unchanged, absent and unknown events emit nothing", received.tryReceive().isFailure)
        // An older server rejecting or ending the optional config stream leaves the session alone.
        socket.send("""{"_tag":"Exit","requestId":"$stream","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"Unknown"}}]}}""")
        delay(200)
        assertEquals(ConnectionStatus.Connected, client.state.value.connection)
        assertTrue(client.state.value.canWrite)
        collector.cancel()
    }

    @Test fun theFirstSnapshotsEndpointsSettleWhenNoLaterSnapshotChangesThem() = runBlocking {
        val lan = listOf(DirectEndpointHint("lan", "http://192.168.1.20:3773"))
        val host = Host(config = """{"providers":[],"directEndpoints":[{"kind":"lan","httpBaseUrl":"http://192.168.1.20:3773"}]}""")
        val client = client(settle = 300)
        val received = Channel<RouteHints>(Channel.UNLIMITED)
        val collector = scope.launch { client.routeHints.collect { received.send(it) } }
        yield(); delay(50)
        client.connect(environment(host.url))
        assertEquals(RouteHints("env-1", lan, TunnelHint.Unknown, additive = true), withTimeout(5_000) { received.receive() })
        assertEquals("T3 found nothing more, so the list may now remove routes", RouteHints("env-1", lan, TunnelHint.Unknown), withTimeout(5_000) { received.receive() })
        delay(500)
        assertTrue("It settles once", received.tryReceive().isFailure)
        collector.cancel()
    }

    @Test fun aDisconnectBeforeTheEndpointsSettleNeverLetsThemRemoveRoutes() = runBlocking {
        val host = Host(config = """{"providers":[],"directEndpoints":[]}""")
        val client = client(settle = 300)
        val received = Channel<RouteHints>(Channel.UNLIMITED)
        val collector = scope.launch { client.routeHints.collect { received.send(it) } }
        yield(); delay(50)
        client.connect(environment(host.url))
        assertTrue(withTimeout(5_000) { received.receive() }.additive)
        client.disconnect()
        delay(600)
        assertTrue("No complete list from a connection that ended", received.tryReceive().isFailure)
        collector.cancel()
    }

    @Test fun snapshotBeforeLinkStateFoldsIntoTheFirstCombinedHint() = runBlocking {
        val host = Host(config = """{"providers":[],"directEndpoints":[{"kind":"lan","httpBaseUrl":"http://192.168.1.20:3773"}]}""",
            linkState = { MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody("""{"linked":true,"tunnelHostname":"prod-fixture.t3coderelay.com"}""") })
        val client = client()
        val received = Channel<RouteHints>(Channel.UNLIMITED)
        val collector = scope.launch { client.routeHints.collect { received.send(it) } }
        yield(); delay(50)
        client.connect(environment(host.url))
        val socket = host.socket.await(); val stream = host.configStream.await()
        socket.send("""{"_tag":"Chunk","requestId":"$stream","values":[{"version":1,"type":"snapshot","config":{"providers":[],"directEndpoints":[{"kind":"tailnet","httpBaseUrl":"https://box.tail.ts.net"}]}}]}""")
        val first = withTimeout(5_000) { received.receive() }
        assertEquals(RouteHints("env-1", listOf(DirectEndpointHint("tailnet", "https://box.tail.ts.net")), TunnelHint.Active("prod-fixture.t3coderelay.com")), first)
        delay(100)
        assertTrue("The early snapshot is not emitted separately", received.tryReceive().isFailure)
        collector.cancel()
    }

    @Test fun hintsStopWithTheConnection() = runBlocking {
        val host = Host(linkState = { MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody("""{"linked":true,"tunnelHostname":"prod-fixture.t3coderelay.com"}""") })
        val client = client()
        val received = Channel<RouteHints>(Channel.UNLIMITED)
        val collector = scope.launch { client.routeHints.collect { received.send(it) } }
        yield(); delay(50)
        client.connect(environment(host.url))
        client.disconnect()
        delay(1_500)
        assertTrue("A link-state reply after disconnect is dropped", received.tryReceive().isFailure)
        collector.cancel()
    }

    @Test fun preflightIsCredentialFreeBoundedAndLeavesTheConnectionAlone() = runBlocking {
        val host = Host(); val stranger = Host(environmentId = "someone-else")
        val silent = Host(descriptor = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
        val client = client()
        client.connect(environment(host.url))
        val before = withTimeout(5_000) { client.state.first { it.canWrite } }
        assertTrue(client.preflight("env-1", host.route(RouteKind.Tunnel), false, 2_000))
        assertFalse(client.preflight("env-1", stranger.route(RouteKind.Lan), false, 2_000))
        val started = System.nanoTime()
        assertFalse(client.preflight("env-1", silent.route(RouteKind.Lan), false, 300))
        assertTrue("A silent route is abandoned at its timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertFalse(client.preflight("env-1", EnvironmentRoute(RouteKind.Lan, "http://192.168.1.20:3773/", RouteSource.Learned), false, 300))
        assertFalse(client.preflight("env-1", EnvironmentRoute(RouteKind.Ssh, "", RouteSource.Paired), true, 300))
        assertFalse(client.preflight("env-1", EnvironmentRoute(RouteKind.Lan, refusedUrl(), RouteSource.Learned), false, 2_000))
        assertFalse(host.requests.filter { it.path == "/.well-known/t3/environment" }.drop(1).any { it.getHeader("Authorization") != null })
        assertFalse(stranger.tokenSent() || silent.tokenSent())
        assertEquals(before, client.state.value)
    }

    @Test fun idleForRouteSwitchOnlyWithoutMutationInFlight() = runBlocking {
        val host = Host(); host.answerDispatch = false
        val client = client()
        assertTrue(client.isIdleForRouteSwitch())
        client.connect(environment(host.url))
        withTimeout(5_000) { client.state.first { it.canWrite } }
        client.selectThread("t")
        withTimeout(5_000) { client.state.first { it.canWrite && it.selectedThreadId == "t" } }
        assertTrue(client.isIdleForRouteSwitch())
        val socket = host.socket.await()
        val send = scope.async { runCatching { client.sendMessage("fixture") } }
        val id = withTimeout(5_000) { host.dispatched.receive() }
        assertFalse("A pending mutation blocks a route switch", client.isIdleForRouteSwitch())
        socket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"sequence":1}}}""")
        assertTrue(withTimeout(5_000) { send.await() }.isSuccess)
        assertTrue(client.isIdleForRouteSwitch())
    }

    @Test fun reconnectWalksTheLastRoutePlan() = runBlocking {
        val lan = Host(); val tunnel = Host()
        val client = client()
        val routes = listOf(lan.route(RouteKind.Lan, RouteSource.Paired), tunnel.route(RouteKind.Tunnel))
        client.connect(environment(lan.url), routes)
        lan.server.shutdown()
        client.reconnect()
        assertEquals(RouteKind.Tunnel, client.state.value.activeRoute?.kind)
    }
}
