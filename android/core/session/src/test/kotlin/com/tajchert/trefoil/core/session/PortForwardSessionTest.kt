package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class PortForwardSessionTest {
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope)
        val server = MockWebServer()
        val discovery = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val discoveryCalls = AtomicInteger()
        val mediaCount = AtomicInteger()
        val tunnels = Channel<RecordedRequest>(Channel.UNLIMITED)
        val tunnelSockets = Channel<WebSocket>(Channel.UNLIMITED)
        val shells = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val acks = Channel<String>(Channel.UNLIMITED)
        @Volatile var rejectDiscovery = false
        @Volatile var capabilities = """{"previewTunnelSetting":true}"""
        /** Extra top-level first-config members, each with a leading comma: `,"settings":{...}`. */
        @Volatile var configExtra = ""
        val configStream = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        private val configStreams = java.util.concurrent.ConcurrentHashMap<String, WebSocket>()
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate","terminal:operate"]}""")
                    request.path == "/api/auth/websocket-ticket/media" -> json("""{"ticket":"media-${mediaCount.incrementAndGet()}","expiresAt":"2999-01-01T00:00:00.000Z"}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/api/preview/tunnel?") -> { tunnels.trySend(request); MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { tunnelSockets.trySend(webSocket) }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send("echo:".encodeUtf8()); webSocket.send(bytes) }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    }) }
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            // The first snapshot's ack hands the stream to the test, so its own acks count only its events.
                            if (frame.string("_tag") == "Ack") { val acked = frame.string("requestId"); configStreams.remove(acked)?.let { configStream.trySend(it to acked) } ?: acks.trySend(acked); return }
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, """{"providers":[],"environment":{"capabilities":$capabilities}$configExtra}""")
                                // The first snapshot is the connection's config; the test sends later events on the stream.
                                "subscribeServerConfig" -> { configStreams[id] = webSocket; chunk(webSocket, id, """{"version":1,"type":"snapshot","config":{"providers":[],"environment":{"capabilities":$capabilities}$configExtra}}""") }
                                "orchestration.subscribeShell" -> { shells.trySend(webSocket to id); chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}""") }
                                "subscribeDiscoveredLocalServers" -> {
                                    discoveryCalls.incrementAndGet()
                                    assertEquals(JsonObject(emptyMap()), frame.obj("payload"))
                                    if (rejectDiscovery) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Unknown request tag: subscribeDiscoveredLocalServers"}]}}""")
                                    else discovery.trySend(webSocket to id)
                                }
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        suspend fun connect() {
            client.connect(SavedEnvironment("env", "Fixture", server.url("/").toString(), "bearer-secret"))
            withTimeout(5000) { client.state.first { it.canWriteThreads } }
        }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }

    private val loopback: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private fun freePort() = ServerSocket(0, 50, loopback).use { it.localPort }

    @Test fun discoveryFeedsTheCatalogAfterTheShellAndDropsOnDisconnect() = runBlocking {
        Fixture().use { f ->
            f.connect()
            assertEquals(true, f.client.state.value.previewTunnelSetting)
            val (ws, id) = withTimeout(5000) { f.discovery.receive() }
            Fixture.chunk(ws, id, """{"servers":[{"host":"127.0.0.1","port":5173,"url":"http://127.0.0.1:5173/","processName":"vite","pid":7,"terminal":null,"agent":{"threadId":"t"},"new":1},{"host":"","port":1,"url":"x"}],"scannedAt":"2026-11-20T10:00:00Z","configuredUrlProbing":true}""")
            withTimeout(5000) { f.client.state.first { it.localServers.live } }
            val catalog = f.client.state.value.localServers
            assertEquals(listOf(5173), catalog.servers.map { it.port }); assertEquals("t", catalog.servers.single().threadId); assertTrue(catalog.supported)
            withTimeout(5000) { while (f.acks.receive() != id) {} }
            // A malformed snapshot keeps the previous list; the next full snapshot replaces it.
            Fixture.chunk(ws, id, """{"scannedAt":"later"}""")
            Fixture.chunk(ws, id, """{"servers":[],"scannedAt":"2026-11-20T10:00:05Z"}""")
            withTimeout(5000) { f.client.state.first { it.localServers.scannedAt == "2026-11-20T10:00:05Z" } }
            assertTrue(f.client.state.value.localServers.servers.isEmpty())
            // A thread-list refresh resynchronizes the shell but does not resubscribe discovery.
            f.client.refreshThreads()
            assertEquals(1, f.discoveryCalls.get())
            f.client.disconnect()
            assertEquals(LocalServerCatalog(), f.client.state.value.localServers)
            f.connect()
            withTimeout(5000) { f.discovery.receive() }
            assertEquals(2, f.discoveryCalls.get())
        }
    }

    @Test fun rejectedDiscoveryIsUnsupportedWithoutAnErrorOrRetry() = runBlocking {
        Fixture().use { f ->
            f.rejectDiscovery = true; f.capabilities = "{}"
            f.connect()
            withTimeout(5000) { f.client.state.first { !it.localServers.supported } }
            val state = f.client.state.value
            assertEquals(ConnectionStatus.Connected, state.connection); assertNull(state.error); assertTrue(state.canWriteThreads)
            assertFalse(state.localServers.live); assertTrue(state.localServers.servers.isEmpty())
            assertNull(state.previewTunnelSetting)
            f.client.refreshThreads()
            assertEquals(1, f.discoveryCalls.get())
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }

    @Test fun theCapabilityAloneNeverDisablesTunnelsOnlyTheServersEffectiveValueDoes() = runBlocking {
        suspend fun disabledWith(capabilities: String, extra: String): Boolean = Fixture().use { f ->
            f.capabilities = capabilities; f.configExtra = extra
            f.connect()
            f.client.state.value.previewTunnelDisabled
        }
        // Current server, setting on, no policy: on. The capability is true there and says nothing else.
        assertFalse(disabledWith("""{"previewTunnelSetting":true}""", ""","settings":{"enablePreviewTunnel":true}"""))
        assertFalse(disabledWith("""{"previewTunnelSetting":true}""", ""","settings":{"enablePreviewTunnel":true},"managedPolicy":{"limits":{"previewTunnel":true}}"""))
        // The setting off, or a policy off, disables.
        assertTrue(disabledWith("""{"previewTunnelSetting":true}""", ""","settings":{"enablePreviewTunnel":false}"""))
        assertTrue(disabledWith("""{"previewTunnelSetting":true}""", ""","settings":{"enablePreviewTunnel":true},"managedPolicy":{"limits":{"previewTunnel":false}}"""))
        // Older servers: no capability, so the tunnel is always on, whatever a setting says.
        assertFalse(disabledWith("{}", ""","settings":{"enablePreviewTunnel":false}"""))
        // A capability of false is not "off" either.
        assertFalse(disabledWith("""{"previewTunnelSetting":false}""", ""","settings":{"enablePreviewTunnel":false}"""))
        // Unknown is not disabled: absent, null and wrong-typed fields.
        assertFalse(disabledWith("""{"previewTunnelSetting":true}""", ""))
        assertFalse(disabledWith("""{"previewTunnelSetting":true}""", ""","settings":{"enablePreviewTunnel":"false"},"managedPolicy":{"limits":{"previewTunnel":"no"}}"""))
        assertFalse(disabledWith("""{"previewTunnelSetting":true}""", ""","settings":"off","managedPolicy":[1]"""))
        assertFalse(disabledWith("""{"previewTunnelSetting":true}""", ""","settings":{"enablePreviewTunnel":null},"managedPolicy":{"limits":null}"""))
        assertFalse(disabledWith("""{"previewTunnelSetting":"true"}""", ""","settings":{"enablePreviewTunnel":false}"""))
    }

    @Test fun theConfigStreamKeepsTheTunnelStateCurrentAndTheNextConnectionStartsFresh() = runBlocking {
        Fixture().use { f ->
            f.configExtra = ""","settings":{"enablePreviewTunnel":true},"managedPolicy":{"limits":{"previewTunnel":true}}"""
            f.connect()
            val (ws, id) = withTimeout(5000) { f.configStream.receive() }
            // The client acks a chunk after handling it, so an ack means its effect is already in the state.
            suspend fun event(json: String) { Fixture.chunk(ws, id, json); withTimeout(5000) { while (f.acks.receive() != id) {} } }
            val tunnelsOff = { f.client.state.value.previewTunnelDisabled }
            assertFalse(tunnelsOff())
            // A settings update flips it; a malformed one changes nothing.
            event("""{"version":1,"type":"settingsUpdated","payload":{"settings":{"enablePreviewTunnel":false}}}""")
            assertTrue(tunnelsOff())
            event("""{"version":1,"type":"settingsUpdated","payload":{"settings":"broken"}}""")
            assertTrue(tunnelsOff())
            event("""{"version":1,"type":"settingsUpdated","payload":{"settings":{"enablePreviewTunnel":true}}}""")
            assertFalse(tunnelsOff())
            // A snapshot brings the policy: off by policy while the setting is on.
            event("""{"version":1,"type":"snapshot","config":{"environment":{"capabilities":{"previewTunnelSetting":true}},"settings":{"enablePreviewTunnel":true},"managedPolicy":{"limits":{"previewTunnel":false}}}}""")
            assertTrue(tunnelsOff())
            // A settings update carries no policy and must not forget it; other events change nothing.
            event("""{"version":1,"type":"settingsUpdated","payload":{"settings":{"enablePreviewTunnel":true}}}""")
            assertTrue(tunnelsOff())
            event("""{"version":1,"type":"providerStatuses","payload":{"providers":[]}}""")
            assertTrue(tunnelsOff())
            // The policy lifted in a later snapshot.
            event("""{"version":1,"type":"snapshot","config":{"environment":{"capabilities":{"previewTunnelSetting":true}},"settings":{"enablePreviewTunnel":true}}}""")
            assertFalse(tunnelsOff())
            event("""{"version":1,"type":"settingsUpdated","payload":{"settings":{"enablePreviewTunnel":false}}}""")
            assertTrue(tunnelsOff())
            f.client.disconnect()
            f.configExtra = ""","settings":{"enablePreviewTunnel":true}"""
            f.connect()
            assertFalse(f.client.state.value.previewTunnelDisabled)
        }
    }

    /** Nothing keeps the flag current once the config stream is gone, so it must not stay frozen at "off". */
    @Test fun aConfigStreamThatEndsOrFailsReturnsTheTunnelStateToNotKnownToBeDisabled() = runBlocking {
        for (exit in listOf(
            """{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Unable to load server config"}]}""",
            """{"_tag":"Success","value":null}""",
        )) Fixture().use { f ->
            f.configExtra = ""","settings":{"enablePreviewTunnel":false}"""
            f.connect()
            val (ws, id) = withTimeout(5000) { f.configStream.receive() }
            assertTrue(f.client.state.value.previewTunnelDisabled)
            ws.send("""{"_tag":"Exit","requestId":"$id","exit":$exit}""")
            withTimeout(5000) { f.client.state.first { !it.previewTunnelDisabled } }
            // The capability is still what the server said, and the session is untouched.
            assertEquals(true, f.client.state.value.previewTunnelSetting)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertNull(f.client.state.value.error)
        }
    }

    @Test fun forwardsCarryBytesShareTheTicketAndStopOnDisconnect() = runBlocking {
        Fixture().use { f ->
            try { f.client.startPortForward(5173); fail("Forwarding needs a connection") } catch (_: IllegalStateException) {}
            f.connect()
            val remote = freePort()
            val forward = f.client.startPortForward(remote)
            assertEquals(PortForward("env", remote, remote, PortForwardStatus.Active, instance = forward.instance), forward)
            assertEquals("http://localhost:$remote/", forward.localUrl)
            assertEquals(forward.localPort, f.client.startPortForward(remote).localPort)
            assertEquals(listOf(forward), f.client.state.value.portForwards)
            val first = connect(remote)
            first.getOutputStream().write("GET /".toByteArray())
            assertEquals("echo:GET /", first.readText(10))
            val second = connect(remote)
            second.getOutputStream().write("HMR".toByteArray())
            assertEquals("echo:HMR", second.readText(8))
            withTimeout(5000) { f.client.state.first { it.portForwards.singleOrNull()?.activeConnections == 2 } }
            val upgrades = listOf(f.tunnels.receive(), f.tunnels.receive())
            upgrades.forEach {
                assertEquals(remote.toString(), it.requestUrl!!.queryParameter("port"))
                assertEquals("media-1", it.requestUrl!!.queryParameter("wsTicket"))
                assertNull(it.getHeader("Authorization"))
            }
            assertEquals(1, f.mediaCount.get())
            assertFalse(f.client.state.value.portForwards.toString().contains("media-1"))
            val remoteSockets = listOf(f.tunnelSockets.receive(), f.tunnelSockets.receive())
            f.client.disconnect()
            assertTrue(f.client.state.value.portForwards.isEmpty())
            assertClosed(first); assertClosed(second); assertRefused(remote)
            assertEquals(2, remoteSockets.size)
            try { f.client.startPortForward(remote); fail("Forwards never start while disconnected") } catch (_: IllegalStateException) {}
        }
    }

    @Test fun reconnectAndCloseStopForwardsWithoutRestartingThem() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val remote = freePort()
            f.client.startPortForward(remote)
            val socket = connect(remote)
            socket.getOutputStream().write("a".toByteArray())
            assertEquals("echo:a", socket.readText(6))
            f.client.reconnect()
            withTimeout(5000) { f.client.state.first { it.canWriteThreads } }
            assertTrue(f.client.state.value.portForwards.isEmpty())
            assertClosed(socket); assertRefused(remote)
            // A new connection mints a new ticket; the old connection's ticket is never reused.
            f.client.startPortForward(remote)
            val again = connect(remote)
            again.getOutputStream().write("b".toByteArray())
            assertEquals("echo:b", again.readText(6))
            assertEquals(2, f.mediaCount.get())
            f.client.close()
            assertTrue(f.client.state.value.portForwards.isEmpty())
            assertClosed(again); assertRefused(remote)
        }
    }

    @Test fun stopClosesOneForwardAndAFailedForwardCanBeRetried() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val a = freePort(); val b = freePort()
            f.client.startPortForward(a); f.client.startPortForward(b)
            assertEquals(listOf(a, b), f.client.state.value.portForwards.map { it.remotePort })
            val socket = connect(a)
            socket.getOutputStream().write("x".toByteArray())
            assertEquals("echo:x", socket.readText(6))
            f.client.stopPortForward(a)
            assertClosed(socket); assertRefused(a)
            assertEquals(listOf(b), f.client.state.value.portForwards.map { it.remotePort })
            f.client.stopPortForward(a) // no-op
            try { f.client.startPortForward(0); fail("Port 0 is invalid") } catch (_: IllegalArgumentException) {}
            try { f.client.startPortForward(65536); fail("Port 65536 is invalid") } catch (_: IllegalArgumentException) {}
        }
    }

    private fun connect(port: Int) = Socket(loopback, port).apply { soTimeout = 5_000 }
    private fun Socket.readText(count: Int): String {
        val out = ByteArray(count); var read = 0
        while (read < count) { val n = getInputStream().read(out, read, count - read); if (n < 0) error("EOF after $read bytes"); read += n }
        return String(out)
    }
    private fun assertClosed(socket: Socket) {
        // Drains bytes already delivered; the connection must then end (EOF or reset) within the socket timeout.
        val result = try { val input = socket.getInputStream(); var next = input.read(); var drained = 0
            while (next >= 0 && drained++ < 1_000_000) next = input.read(); next } catch (_: IOException) { -1 }
        assertEquals(-1, result)
    }
    private fun assertRefused(port: Int) {
        try { Socket(loopback, port).close(); fail("Listener on $port is still open") } catch (_: ConnectException) {}
    }
}
