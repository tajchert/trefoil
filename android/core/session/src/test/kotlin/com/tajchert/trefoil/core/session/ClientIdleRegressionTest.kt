package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Isolated investigation of refresh feedback and retained coroutine jobs. */
class ClientIdleRegressionTest {
    @Test fun threadRefreshSettlesAndDisconnectLeavesNoClientJobs() = runBlocking {
        val server = MockWebServer()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val client = T3Client(scope)
        val stream = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val reads = AtomicInteger()
        val projection = """{"messages":[],"turnItems":[],"runs":[],"runtimeRequests":[]}"""
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"fixture","orchestrationProtocolVersion":2}""")
                request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:operate"]}""")
                request.path.orEmpty().startsWith("/api/auth/websocket-ticket") -> json("""{"ticket":"fixture"}""")
                request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val frame = EffectRpc.decode(text).single()
                        if (frame.string("_tag") != "Request") return
                        val id = frame.string("id")
                        when (frame.string("tag")) {
                            "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"providers":[]}}}""")
                            "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
                            "orchestration.subscribeThread" -> {
                                stream.trySend(webSocket to id)
                                webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":$projection},{"kind":"synchronized"}]}""")
                            }
                            "orchestration.getThreadProjection" -> {
                                reads.incrementAndGet()
                                webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$projection}}""")
                            }
                        }
                    }
                })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            client.connect(SavedEnvironment("fixture", "Fixture", server.url("/").toString(), "fixture-token"))
            withTimeout(5_000) { client.state.first { it.canWrite } }
            repeat(25) { index ->
                client.selectThread("fixture-$index")
                withTimeout(5_000) { client.state.first { it.selectedThreadId == "fixture-$index" && it.canWrite } }
                val selected = withTimeout(5_000) { stream.receive() }
                if (index == 24) selected.first.send("""{"_tag":"Chunk","requestId":"${selected.second}","values":[{"kind":"event","sequence":1,"event":{"type":"fixture.event"}}]}""")
            }
            withTimeout(5_000) { while (reads.get() != 1 || !client.state.value.canWrite) delay(10) }
            delay(250)
            assertEquals("One event must not feed back into repeated projection reads", 1, reads.get())
            assertEquals("Only heartbeat and selected thread refresh should remain", 2, job.children.count { it.isActive })
            client.disconnect()
            withTimeout(5_000) { job.children.toList().joinAll() }
            assertEquals(0, job.children.count())
        } finally {
            client.close()
            scope.cancel()
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
