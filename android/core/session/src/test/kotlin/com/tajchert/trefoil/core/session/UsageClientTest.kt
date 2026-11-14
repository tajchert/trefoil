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

class UsageClientTest {
    @Test fun permissionsAreCheckedBeforeSendingAndResetOnDisconnect() = runBlocking {
        Fixture(diagnostics = false).use { f ->
            f.connect()
            assertEquals(UsageFailure.NoPermission, failure { f.client.usageReport(UsageRange.Week, "UTC") })
            assertEquals(0, f.reads.get())
            f.client.disconnect()
            assertFalse(f.client.state.value.canReadUsage)
            assertEquals(UsageFailure.NotConnected, failure { f.client.usageReport(UsageRange.Week, "UTC") })
        }
    }

    @Test fun provisionalThenAwaitRefreshAndCacheServesOnlyProvisionalReads() = runBlocking {
        Fixture().use { f ->
            f.connect()
            assertTrue(f.client.state.value.canReadUsage)
            assertTrue(f.client.usageReport(UsageRange.Week, "UTC").refreshing)
            assertTrue(f.client.usageReport(UsageRange.Week, "UTC").refreshing)
            assertFalse(f.client.usageReport(UsageRange.Week, "UTC", true).refreshing)
            f.client.usageReport(UsageRange.Week, "UTC", true) // explicit refresh bypasses the cache
            assertFalse(f.client.usageReport(UsageRange.Week, "UTC").refreshing)
            assertEquals(3, f.reads.get())
            assertEquals(true, f.payloads.receive()["provisional"]?.jsonPrimitive?.boolean)
            assertEquals(true, f.payloads.receive()["awaitRefresh"]?.jsonPrimitive?.boolean)
            f.now += 60_001
            f.client.usageReport(UsageRange.Week, "UTC")
            assertEquals(4, f.reads.get())
        }
    }

    @Test fun timeoutRejectionAndMalformedReplyKeepWritesEnabled() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.mode = "timeout"
            assertEquals(UsageFailure.TimedOut, failure { f.client.usageReport(UsageRange.Week, "UTC") })
            f.mode = "reject"
            assertEquals(UsageFailure.Failed, failure { f.client.usageReport(UsageRange.Week, "UTC") })
            f.mode = "malformed"
            assertEquals(UsageFailure.Failed, failure { f.client.usageReport(UsageRange.Week, "UTC") })
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.canWrite)
        }
    }

    @Test fun limitsCoalesceThrottleAndUpdateProviders() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val automatic = async(Dispatchers.Default) { f.client.refreshUsageLimits(true) }
            val pending = withTimeout(5_000) { f.refreshes.receive() }
            val manual = async(start = CoroutineStart.UNDISPATCHED) { f.client.refreshUsageLimits(false) }
            f.success(pending.first, pending.second, """{"providers":[{"instanceId":"claude","driver":"claude","usageLimits":{"checkedAt":"now","windows":[]}}]}""")
            assertTrue(automatic.await()); assertTrue(manual.await())
            assertEquals("now", f.client.state.value.providers.single().usageLimits?.checkedAt)
            assertFalse(f.client.refreshUsageLimits(true))
            f.now += 300_001
            val next = async { runCatching { f.client.refreshUsageLimits(true) } }
            val refresh = withTimeout(5_000) { f.refreshes.receive() }
            f.success(refresh.first, refresh.second, "{}")
            assertEquals(UsageFailure.Failed, (next.await().exceptionOrNull() as UsageException).reason)
            assertEquals("claude", f.client.state.value.providers.single().instanceId)
            assertTrue(f.client.state.value.canWrite)
        }
    }

    @Test fun oldGenerationCannotApplyLimitsAndDisconnectResetsUsagePermission() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val read = async { runCatching { f.client.refreshUsageLimits() } }
            val pending = withTimeout(5_000) { f.refreshes.receive() }
            f.client.disconnect()
            f.success(pending.first, pending.second, """{"providers":[{"instanceId":"stale","driver":"claude"}]}""")
            assertEquals(UsageFailure.Failed, (read.await().exceptionOrNull() as UsageException).reason)
            assertTrue(f.client.state.value.providers.isEmpty())
            assertFalse(f.client.state.value.canReadUsage)
        }
    }

    private suspend fun failure(block: suspend () -> Any): UsageFailure {
        try { block(); error("Expected UsageException") } catch (error: UsageException) { return error.reason }
    }

    private class Fixture(diagnostics: Boolean = true) : AutoCloseable {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile var now = 1_000L
        val client = T3Client(scope, requestTimeoutMillis = 150, usageClockMillis = { now })
        val reads = AtomicInteger()
        val payloads = Channel<JsonObject>(Channel.UNLIMITED)
        val refreshes = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        @Volatile var mode = "success"
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"fixture","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"scopes":["orchestration:operate"${if (diagnostics) ",\"diagnostics:read\"" else ""}]}""")
                    request.path.orEmpty().startsWith("/api/auth/websocket-ticket") -> json("""{"ticket":"fixture"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> success(webSocket, id, """{"providers":[]}""")
                                "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
                                "server.refreshProviders" -> refreshes.trySend(webSocket to id)
                                "server.getUsageSummary" -> {
                                    reads.incrementAndGet()
                                    val payload = frame.obj("payload"); payloads.trySend(payload)
                                    when (mode) {
                                        "timeout" -> Unit
                                        "reject" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{}}]}}""")
                                        "malformed" -> success(webSocket, id, "{}")
                                        else -> scope.launch {
                                            // Longer than the normal request timeout: awaitRefresh uses its own 120-second budget.
                                            if (payload["awaitRefresh"] != null) delay(250)
                                            success(webSocket, id, UsageProjectionTest.summary(payload["awaitRefresh"] == null).toString())
                                        }
                                    }
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
            client.connect(SavedEnvironment("fixture", "Fixture", server.url("/").toString(), "fixture-token"))
            withTimeout(5_000) { client.state.first { it.canWrite } }
        }
        fun success(socket: WebSocket, id: String, value: String) { socket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
    }
}
