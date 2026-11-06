package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetainedSessionTest {
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope)
        val server = MockWebServer()
        val shells = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val threads = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val acks = Channel<String>(Channel.UNLIMITED)
        val subscriptions = AtomicInteger()
        @Volatile var environmentId = "env"
        @Volatile var holdConfiguration = false
        val configurations = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"$environmentId","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:operate"]}""")
                    request.path.orEmpty().startsWith("/api/auth/websocket-ticket") -> json("""{"ticket":"fixture"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            val id = frame.string("id")
                            if (frame.string("_tag") == "Ack") { acks.trySend(frame.string("requestId")); return }
                            if (frame.string("_tag") != "Request") return
                            when (frame.string("tag")) {
                                "server.getConfig" -> if (holdConfiguration) { configurations.trySend(webSocket to id); Unit } else webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"providers":[]}}}""")
                                "orchestration.subscribeShell" -> shells.trySend(webSocket to id)
                                "orchestration.subscribeThread" -> { subscriptions.incrementAndGet(); threads.trySend(webSocket to id) }
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        fun environment() = SavedEnvironment(environmentId, "Fixture", server.url("/").toString(), "fixture-token")
        suspend fun connect(): Pair<WebSocket, String> { client.connect(environment()); return withTimeout(5000) { shells.receive() } }
        suspend fun chunk(stream: Pair<WebSocket, String>, values: String) {
            stream.first.send("""{"_tag":"Chunk","requestId":"${stream.second}","values":[$values]}""")
            withTimeout(5000) { while (acks.receive() != stream.second) {} }
        }
        suspend fun shell(stream: Pair<WebSocket, String>) = chunk(stream, """{"kind":"snapshot","snapshot":{"projects":[{"id":"p","title":"Project","workspaceRoot":"/fixture"},{"id":"other","title":"Other"}],"threads":$rows}}, {"kind":"synchronized"}""")
        suspend fun threadSnapshot(stream: Pair<WebSocket, String>, text: String) = chunk(stream, """{"kind":"snapshot","projection":{"messages":[{"id":"m","role":"assistant","text":"$text"}],"turnItems":[],"runs":[],"runtimeRequests":[]}}, {"kind":"synchronized"}""")
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            val rows = buildJsonArray {
                fun row(id: String, status: String = "idle", extra: JsonObjectBuilder.() -> Unit = {}) = add(buildJsonObject {
                    put("id", id); put("projectId", "p"); put("title", id); put("status", status); extra()
                })
                row("working", "running")
                row("snoozed") { put("snoozedUntil", "2099-01-01T00:00:00Z") }
                repeat(24) { row("settled-$it") { put("settledAt", "2026-11-06T09:00:00Z") } }
                repeat(4) { row("open-$it") }
            }
        }
    }
    @Test fun enrichmentCannotEraseThreadsOrUnrelatedProjectsButAuthoritativeSnapshotCan() = runBlocking {
        Fixture().use { f ->
            val shell = f.connect(); f.shell(shell)
            assertEquals(30, f.client.state.value.threads.size)
            f.chunk(shell, """{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/fixture"],"snapshot":{"projects":[{"id":"p","title":"Enriched","workspaceRoot":"/fixture"}],"threads":[],"archivedThreads":[]}}""")
            assertEquals(30, f.client.state.value.threads.size)
            assertEquals(setOf("p", "other"), f.client.state.value.projects.map { it.id }.toSet())
            assertEquals("Enriched", f.client.state.value.projects.first { it.id == "p" }.title)
            f.chunk(shell, """{"kind":"thread.updated","thread":{"id":"working","projectId":"p","title":"Updated","status":"running"}}""")
            assertEquals(30, f.client.state.value.threads.size)
            f.chunk(shell, """{"kind":"snapshot","snapshot":{"projects":[],"threads":[],"archivedThreads":[]}}""")
            assertTrue(f.client.state.value.threads.isEmpty())
        }
    }
    @Test fun sameEnvironmentReconnectKeepsShellAndConversationUntilFreshSnapshots() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Cached reply")
            f.client.disconnect()
            val reconnect = f.connect()
            assertEquals(30, f.client.state.value.threads.size)
            assertEquals("working", f.client.state.value.selectedThreadId)
            assertEquals("Cached reply", f.client.state.value.messages.single().text)
            assertFalse(f.client.state.value.canWrite)
            f.shell(reconnect)
            assertFalse(f.client.state.value.canWrite)
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Fresh reply")
            assertTrue(f.client.state.value.canWrite)
            assertEquals("Fresh reply", f.client.state.value.messages.single().text)
            f.client.disconnect(); f.environmentId = "another"
            f.connect()
            assertTrue(f.client.state.value.threads.isEmpty())
            assertTrue(f.client.state.value.messages.isEmpty())
            assertNull(f.client.state.value.selectedThreadId)
        }
    }
    @Test fun cachedThreadsCanBeReadOfflineAndForgettingRemovesTheirState() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Cached working reply")
            f.client.selectThread("open-0")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Cached open reply")
            f.client.disconnect()
            f.client.selectThread("working")
            assertEquals("Cached working reply", f.client.state.value.messages.single().text)
            assertFalse(f.client.state.value.canWrite)
            assertEquals(2, f.subscriptions.get())
            try { f.client.sendMessage("Must stay local"); fail("Offline writes must fail") } catch (_: IllegalStateException) { }
            f.client.forgetEnvironment()
            f.connect()
            assertTrue(f.client.state.value.threads.isEmpty())
            assertTrue(f.client.state.value.messages.isEmpty())
            assertNull(f.client.state.value.selectedThreadId)
        }
    }
    @Test fun forgettingInactiveEnvironmentClearsOnlyItsCachedCredentialsAndContent() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Original environment reply")
            f.client.disconnect(); f.environmentId = "second"
            f.shell(f.connect()); f.client.selectThread("working")
            assertTrue(f.client.state.value.messages.isEmpty())
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Second environment reply")
            f.client.forgetEnvironment("env")
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertEquals("Second environment reply", f.client.state.value.messages.single().text)
            assertTrue(f.client.state.value.canWrite)
            f.client.disconnect(); f.environmentId = "env"
            f.connect()
            assertTrue(f.client.state.value.threads.isEmpty())
            assertTrue(f.client.state.value.messages.isEmpty())
            assertNull(f.client.state.value.selectedThreadId)
            f.client.selectThread("working")
            assertTrue(f.client.state.value.messages.isEmpty())
        }
    }
    @Test fun cachedSelectionDuringReconnectWinsOverThePreviousSelection() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Working reply")
            f.client.selectThread("open-0")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Open reply")
            f.client.selectThread("working")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Working reply")
            f.holdConfiguration = true
            val reconnect = async { f.connect() }
            val config = withTimeout(5000) { f.configurations.receive() }
            assertEquals(ConnectionStatus.Connecting, f.client.state.value.connection)
            f.client.selectThread("open-0")
            assertEquals("Open reply", f.client.state.value.messages.single().text)
            assertFalse(f.client.state.value.canWrite)
            config.first.send("""{"_tag":"Exit","requestId":"${config.second}","exit":{"_tag":"Success","value":{"providers":[]}}}""")
            f.shell(withTimeout(5000) { reconnect.await() })
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Fresh open reply")
            assertEquals("open-0", f.client.state.value.selectedThreadId)
            assertEquals("Fresh open reply", f.client.state.value.messages.single().text)
            assertTrue(f.client.state.value.canWrite)
        }
    }
    @Test fun openingSameThreadIsIdempotentAndRevisitingUsesCachedProjectionReadOnly() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Working reply")
            f.client.selectThread("working")
            assertEquals(1, f.subscriptions.get())
            assertEquals("Working reply", f.client.state.value.messages.single().text)
            assertTrue(f.client.state.value.canWrite)
            f.client.selectThread("open-0")
            assertTrue(f.client.state.value.messages.isEmpty())
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Other reply")
            f.client.selectThread("working")
            assertEquals("Working reply", f.client.state.value.messages.single().text)
            assertFalse(f.client.state.value.canWrite)
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Refreshed working reply")
            assertTrue(f.client.state.value.canWrite)
        }
    }
}
