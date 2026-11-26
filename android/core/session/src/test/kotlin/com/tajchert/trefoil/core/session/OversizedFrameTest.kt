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

/** A frame over the client's limit (a thread with tens of MB of tool output) closes that thread, not the session. */
class OversizedFrameTest {
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, maxFrameChars = LIMIT)
        val server = MockWebServer()
        @Volatile var bigShell = false
        @Volatile var bigRefresh = false
        @Volatile var threadSocket: Pair<WebSocket, String>? = null
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, """{"providers":[],"environment":{"capabilities":{}},"settings":{}}""")
                                "subscribeServerConfig" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":{"providers":[],"environment":{"capabilities":{}},"settings":{}}}]}""")
                                "orchestration.subscribeShell" -> chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"snapshotSequence":10,"projects":[{"id":"p","title":"P","workspaceRoot":"/w"}],"threads":[{"id":"small","projectId":"p","title":"Small"},{"id":"big","projectId":"p","title":"Big"}${if (bigShell) ""","pad":"$PAD"""" else ""}]}},{"kind":"synchronized"}""")
                                "orchestration.subscribeThread" -> {
                                    val threadId = frame.obj("payload").string("threadId")
                                    val pad = if (threadId == "big" && !bigRefresh) ""","pad":"$PAD"""" else ""
                                    threadSocket = webSocket to id
                                    chunk(webSocket, id, """{"kind":"snapshot","projection":{"thread":{"id":"$threadId"$pad},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[]}},{"kind":"synchronized"}""")
                                }
                                "orchestration.getThreadProjection" -> complete(webSocket, id, """{"thread":{"id":"big","pad":"$PAD"},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[]}""")
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
            const val LIMIT = 4096
            val PAD = "a".repeat(LIMIT)
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }

    @Test fun oversizedThreadSnapshotClosesOnlyThatThread() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            f.client.selectThread("big")
            val closed = withTimeout(5000) { f.client.state.first { it.error == THREAD_TOO_LARGE } }
            assertNull(closed.selectedThreadId)
            assertEquals(ConnectionStatus.Connected, closed.connection)
            assertTrue(closed.canWriteThreads)
            // Another thread still opens on the same connection and clears the notice.
            f.client.selectThread("small")
            val open = withTimeout(5000) { f.client.state.first { it.selectedThreadId == "small" && it.canWrite } }
            assertNull(open.error)
        }
    }

    @Test fun oversizedThreadRefreshClosesOnlyThatThread() = runBlocking<Unit> {
        Fixture().use { f ->
            f.bigRefresh = true
            f.connect()
            f.client.selectThread("big")
            withTimeout(5000) { f.client.state.first { it.selectedThreadId == "big" && it.canWrite } }
            val (ws, id) = f.threadSocket!!
            Fixture.chunk(ws, id, """{"kind":"event","sequence":11,"event":{"type":"thread.updated"}}""")
            val closed = withTimeout(5000) { f.client.state.first { it.error == THREAD_TOO_LARGE } }
            assertNull(closed.selectedThreadId)
            assertEquals(ConnectionStatus.Connected, closed.connection)
        }
    }

    @Test fun oversizedShellFailsTheSessionWithAClearMessage() = runBlocking<Unit> {
        Fixture().use { f ->
            f.bigShell = true
            runCatching { f.client.connect(SavedEnvironment("env", "Fixture", f.server.url("/").toString(), "bearer-secret")) }
            val failed = withTimeout(5000) { f.client.state.first { it.connection == ConnectionStatus.Error } }
            assertEquals("The server sent more data than this app can load; reconnect to retry", failed.error)
        }
    }
}
