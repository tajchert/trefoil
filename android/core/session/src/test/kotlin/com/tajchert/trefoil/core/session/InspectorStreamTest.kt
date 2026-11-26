package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The read-only inspector streams a side thread beside the selected one without touching it or the session. */
class InspectorStreamTest {
    private class Fixture(val sideChats: Boolean? = true) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, maxFrameChars = LIMIT)
        val server = MockWebServer()
        /** Latest `subscribeThread` request per thread id. */
        val threadStreams = ConcurrentHashMap<String, Pair<WebSocket, String>>()
        val subscribed = CopyOnWriteArrayList<String>()
        val interrupts = CopyOnWriteArrayList<String>()
        @Volatile var shellStream: Pair<WebSocket, String>? = null
        @Volatile var bigSide = false
        @Volatile var rejectRefresh = false
        @Volatile var holdSide = false
        @Volatile var sideText = "Side hello"
        init {
            val capabilities = if (sideChats == null) "{}" else """{"sideChats":$sideChats}"""
            val config = """{"providers":[],"environment":{"capabilities":$capabilities},"settings":{}}"""
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") == "Interrupt") { interrupts += frame.string("requestId"); return }
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, config)
                                "subscribeServerConfig" -> chunk(webSocket, id, """{"version":1,"type":"snapshot","config":$config}""")
                                "orchestration.subscribeShell" -> { shellStream = webSocket to id; chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"snapshotSequence":10,"projects":[{"id":"p","title":"P","workspaceRoot":"/w"}],"threads":[$THREADS]}},{"kind":"synchronized"}""") }
                                "orchestration.subscribeThread" -> {
                                    val threadId = frame.obj("payload").string("threadId")
                                    threadStreams[threadId] = webSocket to id
                                    subscribed += threadId
                                    if (threadId == "side" && holdSide) return
                                    val pad = if (threadId == "side" && bigSide) ""","pad":"$PAD"""" else ""
                                    chunk(webSocket, id, """{"kind":"snapshot","projection":${projection(threadId, pad)}},{"kind":"synchronized"}""")
                                }
                                "orchestration.getThreadProjection" -> {
                                    val threadId = frame.obj("payload").string("threadId")
                                    if (rejectRefresh && threadId != "parent") reject(webSocket, id) else complete(webSocket, id, projection(threadId, ""))
                                }
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        fun projection(threadId: String, pad: String): String {
            val text = when (threadId) { "parent" -> "Parent hello"; "side" -> sideText; else -> "Child hello" }
            val run = if (threadId == "parent") ""","runs":[{"id":"run","status":"running","requestedAt":"2026-11-06T10:00:00Z"}]""" else ""","runs":[]"""
            return """{"thread":{"id":"$threadId"$pad},"messages":[{"id":"m-$threadId","role":"assistant","text":"$text"}]$run,"turnItems":[],"runtimeRequests":[]}"""
        }
        suspend fun connect() {
            client.connect(SavedEnvironment("env", "Fixture", server.url("/").toString(), "bearer-secret"))
            withTimeout(5000) { client.state.first { it.canWriteThreads } }
        }
        suspend fun selectParent(): ClientState {
            client.selectThread("parent")
            return withTimeout(5000) { client.state.first { it.selectedThreadId == "parent" && it.canWrite && it.messages.isNotEmpty() } }
        }
        suspend fun inspector(predicate: (InspectorThread) -> Boolean): InspectorThread =
            withTimeout(5000) { client.state.first { s -> s.inspector?.let(predicate) == true } }.inspector!!
        suspend fun awaitInterrupt(id: String) = withTimeout(5000) { while (id !in interrupts) delay(10) }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            const val LIMIT = 4096
            val PAD = "a".repeat(LIMIT)
            const val THREADS = """{"id":"parent","projectId":"p","title":"Parent"},""" +
                """{"id":"side","projectId":"p","title":"Side","createdAt":"2026-11-06T10:00:00Z","lineage":{"parentThreadId":"parent","sideChat":true}},""" +
                """{"id":"sub","projectId":"p","title":"Sub","lineage":{"parentThreadId":"parent","relationshipToParent":"subagent"}},""" +
                """{"id":"other","projectId":"p","title":"Other"}"""
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun reject(ws: WebSocket, id: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{}}]}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }

    /** The selected thread's slice, which the inspector must never change. */
    private fun ClientState.parentSlice() = listOf(selectedThreadId, canWrite, canRead, canStop, canSteer, queuedMessages, queueHeld, messages, activeRunId, connection)

    @Test fun sideChatsCapabilityIsParsedAndClearedOnDisconnect() = runBlocking<Unit> {
        Fixture(sideChats = true).use { f ->
            f.connect()
            assertTrue(f.client.state.value.sideChatsSupported)
            f.client.disconnect()
            assertFalse(f.client.state.value.sideChatsSupported)
        }
        Fixture(sideChats = null).use { f -> f.connect(); assertFalse(f.client.state.value.sideChatsSupported) }
        Fixture(sideChats = false).use { f -> f.connect(); assertFalse(f.client.state.value.sideChatsSupported) }
    }

    @Test fun openingStreamsTheChildWithoutTouchingTheParent() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            val before = f.selectParent()
            assertTrue(before.canStop)
            f.client.openInspector("side")
            val shown = f.inspector { it.ready && it.messages.isNotEmpty() }
            assertEquals("side", shown.threadId)
            assertEquals(SideThreadKind.SideChat, shown.kind)
            assertEquals("Side hello", shown.messages.single().text)
            assertEquals(before.parentSlice(), f.client.state.value.parentSlice())
            // A child event refetches the child only; the parent is never marked stale.
            f.sideText = "Side v2"
            val (ws, id) = f.threadStreams.getValue("side")
            Fixture.chunk(ws, id, """{"kind":"event","sequence":11,"event":{"type":"thread.updated"}}""")
            f.inspector { it.messages.singleOrNull()?.text == "Side v2" }
            assertEquals(before.parentSlice(), f.client.state.value.parentSlice())
            assertEquals(listOf("parent", "side"), f.subscribed.toList())
        }
    }

    @Test fun atMostOneStreamAndReopeningTheSameIsANoOp() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.selectParent()
            f.client.openInspector("side")
            f.inspector { it.threadId == "side" && it.ready }
            val sideRequest = f.threadStreams.getValue("side").second
            f.client.openInspector("sub")
            f.awaitInterrupt(sideRequest)
            val sub = f.inspector { it.threadId == "sub" && it.ready }
            assertEquals(SideThreadKind.Subagent, sub.kind)
            f.client.openInspector("sub")
            assertEquals(1, f.subscribed.count { it == "sub" })
            assertEquals("parent", f.client.state.value.selectedThreadId)
        }
    }

    @Test fun refusesThreadsThatAreNotSideThreadsOfTheSelection() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            assertTrue(runCatching { f.client.openInspector("side") }.isFailure) // nothing selected
            f.selectParent()
            assertTrue(runCatching { f.client.openInspector("other") }.isFailure)
            assertTrue(runCatching { f.client.openInspector("missing") }.isFailure)
            assertTrue(runCatching { f.client.openInspector("parent") }.isFailure)
            assertNull(f.client.state.value.inspector)
            f.client.disconnect()
            assertTrue(runCatching { f.client.openInspector("side") }.isFailure)
            assertEquals(listOf("parent"), f.subscribed.toList())
        }
    }

    @Test fun parentSwitchClosesTheStreamWithInterrupt() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.selectParent()
            f.client.openInspector("side")
            f.inspector { it.ready }
            val sideRequest = f.threadStreams.getValue("side").second
            f.client.selectThread("other")
            f.awaitInterrupt(sideRequest)
            assertNull(f.client.state.value.inspector)
        }
    }

    @Test fun disconnectInterruptsKeepsStaleRowsAndNeverResubscribes() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.selectParent()
            f.client.openInspector("side")
            f.inspector { it.ready }
            val sideRequest = f.threadStreams.getValue("side").second
            f.client.disconnect()
            f.awaitInterrupt(sideRequest)
            val stale = f.client.state.value.inspector!!
            assertFalse(stale.ready)
            assertEquals("Side hello", stale.messages.single().text)
            f.connect()
            withTimeout(5000) { f.client.state.first { it.selectedThreadId == "parent" && it.canWrite } }
            assertEquals(1, f.subscribed.count { it == "side" })
        }
    }

    @Test fun environmentSwitchClearsTheInspector() = runBlocking<Unit> {
        Fixture().use { f ->
            Fixture().use { other ->
                f.connect(); f.selectParent()
                f.client.openInspector("side")
                f.inspector { it.ready }
                val sideRequest = f.threadStreams.getValue("side").second
                // The fixture server reports the identity `env`, so this connect ends refused; the switch has happened by then.
                runCatching { f.client.connect(SavedEnvironment("env2", "Other", other.server.url("/").toString(), "bearer-secret")) }
                assertEquals("env2", f.client.state.value.environment?.id)
                f.awaitInterrupt(sideRequest)
                assertNull(f.client.state.value.inspector)
            }
        }
    }

    @Test fun oversizedChildFrameSetsTooLargeWithoutFailingTheSession() = runBlocking<Unit> {
        Fixture().use { f ->
            f.bigSide = true
            f.connect()
            val before = f.selectParent()
            f.client.openInspector("side")
            val failed = f.inspector { it.error == InspectorError.TooLarge }
            assertFalse(failed.ready)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertNull(f.client.state.value.error)
            assertEquals(before.parentSlice(), f.client.state.value.parentSlice())
            // Retry on the same connection works once the child fits.
            f.bigSide = false
            f.client.openInspector("side")
            f.inspector { it.ready && it.error == null }
        }
    }

    @Test fun refreshFailureAndStreamEndOnlyStopTheInspector() = runBlocking<Unit> {
        Fixture().use { f ->
            f.rejectRefresh = true
            f.connect()
            val before = f.selectParent()
            f.client.openInspector("side")
            f.inspector { it.ready }
            val (ws, id) = f.threadStreams.getValue("side")
            Fixture.chunk(ws, id, """{"kind":"event","sequence":11,"event":{"type":"thread.updated"}}""")
            f.inspector { it.error == InspectorError.RefreshFailed }
            assertEquals(before.parentSlice(), f.client.state.value.parentSlice())

            f.client.openInspector("sub")
            f.inspector { it.threadId == "sub" && it.ready }
            val (subWs, subId) = f.threadStreams.getValue("sub")
            Fixture.reject(subWs, subId)
            f.inspector { it.threadId == "sub" && it.error == InspectorError.RefreshFailed }
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertEquals(before.parentSlice(), f.client.state.value.parentSlice())
        }
    }

    @Test fun childLeavingTheShellIsGone() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.selectParent()
            f.client.openInspector("side")
            f.inspector { it.ready }
            val sideRequest = f.threadStreams.getValue("side").second
            val (ws, id) = f.shellStream!!
            Fixture.chunk(ws, id, """{"kind":"thread.removed","threadId":"side","location":"active","sequence":11}""")
            f.inspector { it.error == InspectorError.Gone }
            f.awaitInterrupt(sideRequest)
            assertEquals("parent", f.client.state.value.selectedThreadId)
            assertTrue(f.client.state.value.canWrite)
        }
    }

    @Test fun snapshotIsCachedSoMaximizeRepaintsFromMemory() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.selectParent()
            f.client.openInspector("side")
            f.inspector { it.ready }
            f.holdSide = true // the maximized child's own stream never answers here
            f.client.closeInspector()
            assertNull(f.client.state.value.inspector)
            f.client.selectThread("side")
            val shown = f.client.state.value
            assertEquals("side", shown.selectedThreadId)
            assertEquals("Side hello", shown.messages.single().text)
            assertFalse(shown.canWrite)
        }
    }

    @Test fun latestChildProjectionIsCachedWhenTheStreamCloses() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.selectParent()
            f.client.openInspector("side")
            f.inspector { it.ready }
            f.sideText = "Side v2"
            val (ws, id) = f.threadStreams.getValue("side")
            Fixture.chunk(ws, id, """{"kind":"event","sequence":11,"event":{"type":"thread.updated"}}""")
            f.inspector { it.messages.singleOrNull()?.text == "Side v2" }
            f.holdSide = true
            f.client.closeInspector()
            f.client.selectThread("side")
            assertEquals("Side v2", f.client.state.value.messages.single().text)
        }
    }

    @Test fun cachedChildNeverEvictsTheKeptParent() {
        val cache = ThreadProjectionCache(maxThreads = 3, maxRows = 10)
        fun entry(rows: Int) = CachedThread(JsonObject(emptyMap()), threadPresentation(JsonObject(emptyMap())).copy(
            messages = List(rows) { ConversationMessage("m$it", "assistant", "x") }))
        val parent = "env" to "parent"
        cache.put(parent, entry(4))
        cache.put("env" to "old", entry(1))
        // A chatty child stored repeatedly over budget: the parent stays, older rows go.
        repeat(5) { cache.put("env" to "child", entry(7), keep = parent) }
        assertTrue(parent in cache.keys)
        assertTrue(("env" to "child") in cache.keys)
        assertFalse(("env" to "old") in cache.keys)
        // Without the pin the same store evicts the parent (today's least-recently-used rule).
        val plain = ThreadProjectionCache(maxThreads = 3, maxRows = 10)
        plain.put(parent, entry(4)); plain.put("env" to "child", entry(7))
        assertFalse(parent in plain.keys)
    }
}
