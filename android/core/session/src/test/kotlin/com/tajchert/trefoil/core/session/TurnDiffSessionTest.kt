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
import java.util.concurrent.CopyOnWriteArrayList

/** Checkpoints, PR links, PR capability flags and turn diffs through a live session. Synthetic fixtures only. */
class TurnDiffSessionTest {
    private class Fixture(
        val permissions: String = """"orchestration:read","orchestration:operate","source-control:write"""",
        val capabilities: String = """{"pullRequests":true,"pullRequestChecks":true}""",
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val parsed = CopyOnWriteArrayList<List<Pair<String?, String>>>()
        val client = T3Client(scope, maxFrameChars = LIMIT, diffParser = DiffParser { parts ->
            parsed += parts
            DiffDocument(emptyList(), List(parts.size) { DiffRow.Notice(it.toLong(), -1, "part") })
        })
        val server = MockWebServer()
        /** Diff requests as (tag, payload). */
        val diffRequests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        @Volatile var diffReply: String = """{"threadId":"t1","fromTurnCount":0,"toTurnCount":1,"diff":"DIFF"}"""
        /** When set, diff requests are held here instead of answered. */
        @Volatile var hold: CompletableDeferred<Pair<WebSocket, String>>? = null
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":[$permissions]}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            val config = """{"providers":[],"environment":{"capabilities":$capabilities},"settings":{}}"""
                            when (val tag = frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, config)
                                "subscribeServerConfig" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":$config}]}""")
                                "orchestration.subscribeShell" -> chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"snapshotSequence":10,"projects":[{"id":"p","title":"P","workspaceRoot":"/w"}],"threads":[{"id":"t1","projectId":"p","title":"One"},{"id":"t2","projectId":"p","title":"Two"}]}},{"kind":"synchronized"}""")
                                "orchestration.subscribeThread" -> {
                                    val threadId = frame.obj("payload").string("threadId")
                                    chunk(webSocket, id, """{"kind":"snapshot","projection":${projection(threadId)}},{"kind":"synchronized"}""")
                                }
                                "orchestration.getFullThreadDiff", "orchestration.getTurnDiff" -> {
                                    diffRequests += tag to frame.obj("payload")
                                    val held = hold
                                    if (held != null) held.complete(webSocket to id) else complete(webSocket, id, diffReply)
                                }
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        val environment get() = SavedEnvironment("env", "Fixture", server.url("/").toString(), "bearer-secret")
        suspend fun connect() {
            client.connect(environment)
            withTimeout(5000) { client.state.first { it.canWriteThreads } }
        }
        suspend fun open(threadId: String): ClientState {
            client.selectThread(threadId)
            return withTimeout(5000) { client.state.first { it.selectedThreadId == threadId && it.canWrite } }
        }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            const val LIMIT = 4096
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun fail(ws: WebSocket, id: String, error: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":{"_tag":"Fail","error":$error}}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
            fun projection(threadId: String) = if (threadId == "t1") """{"thread":{"id":"t1","projectId":"p","pullRequests":[
                {"host":"github.com","repository":"acme/app","number":7,"url":"https://example.test/pull/7","source":"created","snapshot":{"state":"open","title":"Seven","headBranch":"h","baseBranch":"main","isDraft":false}},
                {"host":"github.com","repository":"acme/app","number":8,"url":"https://example.test/pull/8","source":"stack-dismissed","snapshot":null}]},
                "messages":[],"runs":[],"turnItems":[],"runtimeRequests":[],"checkpoints":[
                {"id":"c2","appRunOrdinal":2,"status":"ready","files":[{"path":"b.txt","kind":"modified","additions":1,"deletions":1}]},
                {"id":"c0","appRunOrdinal":null,"status":"ready","files":[]},
                {"id":"c1","appRunOrdinal":1,"status":"ready","files":[{"path":"a.txt","kind":"added","additions":2,"deletions":0}]}]}"""
            else """{"thread":{"id":"$threadId","projectId":"p"},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[],"checkpoints":[]}"""
        }
    }

    @Test fun projectionAndFlagsReachTheState() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            val connected = f.client.state.value
            assertTrue(connected.pullRequestsSupported)
            assertTrue(connected.pullRequestChecksSupported)
            assertTrue(connected.canWriteSourceControl)
            val open = f.open("t1")
            assertEquals(listOf(1, 2), open.checkpoints.map { it.turnCount })
            assertEquals(listOf(PullRequestKey("p", "github.com", "acme/app", 7)), open.threadPullRequests.map { it.key })
            assertEquals("Seven", open.threadPullRequests.single().snapshot?.title)
            val other = f.open("t2")
            assertTrue(other.checkpoints.isEmpty())
            assertTrue(other.threadPullRequests.isEmpty())
            f.client.disconnect()
            val down = f.client.state.value
            assertFalse(down.pullRequestsSupported || down.pullRequestChecksSupported || down.canWriteSourceControl)
        }
    }

    @Test fun flagsStayOffWithoutScopeOrCapabilities() = runBlocking<Unit> {
        Fixture(permissions = """"orchestration:read","orchestration:operate"""", capabilities = """{"pullRequests":"yes"}""").use { f ->
            f.connect()
            val state = f.client.state.value
            assertFalse(state.pullRequestsSupported)
            assertFalse(state.pullRequestChecksSupported)
            assertFalse(state.canWriteSourceControl)
        }
    }

    @Test fun turnOneAndLaterTurnsSendTheirRpcAndCache() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.open("t1")
            f.client.loadTurnDiff(1)
            f.diffReply = """{"threadId":"t1","fromTurnCount":1,"toTurnCount":2,"diff":"ALL","repositories":[{"name":"one","diff":"A"},{"name":"two","diff":"B"}]}"""
            val second = f.client.loadTurnDiff(2)
            assertEquals(2, second.rows.size)
            assertEquals(listOf(
                "orchestration.getFullThreadDiff" to buildJsonObject { put("threadId", "t1"); put("toTurnCount", 1) },
                "orchestration.getTurnDiff" to buildJsonObject { put("threadId", "t1"); put("fromTurnCount", 1); put("toTurnCount", 2); put("ignoreWhitespace", false) },
            ), f.diffRequests.toList())
            assertEquals(listOf(listOf(null to "DIFF"), listOf("one" to "A", "two" to "B")), f.parsed.toList())
            // Cached: no second request.
            assertSame(second, f.client.loadTurnDiff(2))
            assertEquals(2, f.diffRequests.size)
            // Forgetting the environment drops the cache.
            f.client.forgetEnvironment()
            f.connect(); f.open("t1")
            f.client.loadTurnDiff(2)
            assertEquals(3, f.diffRequests.size)
        }
    }

    @Test fun replyForAThreadNoLongerSelectedIsDropped() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.open("t1")
            val held = CompletableDeferred<Pair<WebSocket, String>>().also { f.hold = it }
            val load = async { runCatching { f.client.loadTurnDiff(1) } }
            val (ws, id) = withTimeout(5000) { held.await() }
            f.open("t2")
            Fixture.complete(ws, id, f.diffReply)
            val error = withTimeout(5000) { load.await() }.exceptionOrNull()
            assertTrue(error is TurnDiffException)
            assertEquals(TurnDiffException.THREAD_CHANGED, error?.message)
            assertTrue(f.parsed.isEmpty())
            f.hold = null
            f.open("t1")
            f.client.loadTurnDiff(1)
            assertEquals(1, f.parsed.size)
        }
    }

    @Test fun oversizedOrRejectedDiffFailsOnlyThatRequest() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.open("t1")
            f.diffReply = """{"threadId":"t1","fromTurnCount":0,"toTurnCount":1,"diff":"${"x".repeat(Fixture.LIMIT)}"}"""
            val large = runCatching { f.client.loadTurnDiff(1) }.exceptionOrNull()
            assertTrue(large is TurnDiffException)
            assertEquals(TurnDiffException.TOO_LARGE, large?.message)
            val after = f.client.state.value
            assertEquals(ConnectionStatus.Connected, after.connection)
            assertTrue(after.canWrite && after.canWriteThreads)
            assertEquals("t1", after.selectedThreadId)
            assertNull(after.error)
            // A typed failure (with a `cause`) shows the generic message, never the server's text.
            val held = CompletableDeferred<Pair<WebSocket, String>>().also { f.hold = it }
            val load = async { runCatching { f.client.loadTurnDiff(2) } }
            val (ws, id) = withTimeout(5000) { held.await() }
            Fixture.fail(ws, id, """{"_tag":"OrchestrationGetTurnDiffError","message":"secret/path.kt failed","cause":{"detail":"secret"}}""")
            val rejected = withTimeout(5000) { load.await() }.exceptionOrNull()
            assertEquals(TurnDiffException.UNAVAILABLE, rejected?.message)
            assertTrue(f.client.state.value.canWrite)
        }
    }
}
