package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The thread list must not keep a stale run status: a refresh resubscribes the shell and its snapshot wins. */
class ShellRefreshTest {
    /** A hang fails in seconds instead of parking the test worker. */
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{}}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun row(status: String, extra: String = "") =
        """{"id":"t","projectId":"p","title":"Fixture","status":"$status","latestRunId":"run","latestRunCompletedAt":"2026-11-19T10:00:00Z"$extra}"""
    private fun snapshot(vararg rows: String) =
        """{"kind":"snapshot","snapshot":{"snapshotSequence":1,"projects":[{"id":"p","title":"Project","workspaceRoot":"/tmp/p"}],"threads":[${rows.joinToString(",")}],"archivedThreads":[]}}"""

    private class Fixture(val server: MockWebServer, val client: T3Client, val scope: CoroutineScope, val requests: MutableList<Pair<String, String>>, val socket: () -> WebSocket?)

    /** [shell] answers the n-th `subscribeShell` (0-based) with its chunk values, or null to stay silent. */
    private fun fixture(shell: (Int, String) -> String?): Fixture {
        val server = MockWebServer(); server.dispatcher = LinkStateTolerantQueue(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, requestTimeoutMillis = 1_500)
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        var ws: WebSocket? = null
        var shells = 0
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ws = webSocket }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = EffectRpc.decode(text).single()
                val id = frame.string("requestId").ifBlank { frame.string("id") }
                requests += (if (frame.string("_tag") == "Request") frame.string("tag") else frame.string("_tag")) to id
                if (frame.string("_tag") != "Request") return
                when (frame.string("tag")) {
                    "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"providers":[]}}}""")
                    "subscribeServerConfig" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":{"providers":[]}}]}""")
                    "orchestration.subscribeShell" -> shell(shells++, id)?.let { webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[$it]}""") }
                }
            }
        }
        server.enqueue(json(descriptor))
        server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}"""))
        server.enqueue(json("""{"ticket":"ticket"}""")); server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        return Fixture(server, client, scope, requests) { ws }
    }

    private suspend fun Fixture.connect() {
        client.connect(SavedEnvironment("env-1", "Test", server.url("/").toString(), "bearer"))
        withTimeout(5_000) { client.state.first { it.canWriteThreads && it.threads.isNotEmpty() } }
    }
    private fun Fixture.close() = runBlocking<Unit> { client.disconnect(); scope.cancel(); runCatching { server.shutdown() } }

    @Test fun refreshReplacesStaleRowsWithFreshSnapshotAndIgnoresTheOldStream() = runBlocking<Unit> {
        val shellIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val f = fixture { n, id ->
            shellIds += id
            // The first snapshot predates the subagent: the row looks finished. The server's later updates never arrive.
            if (n == 0) "${snapshot(row("completed"))}, {\"kind\":\"synchronized\"}"
            else "${snapshot(row("completed", ""","pendingBackgroundTasks":[{"taskId":"agent","kind":"subagent"}]"""))}, {\"kind\":\"synchronized\"}"
        }
        try {
            f.connect()
            assertFalse(f.client.state.value.threads.single().completionHeldBackgroundWork)
            f.client.refreshThreads()
            val row = f.client.state.value.threads.single()
            assertTrue("Fresh snapshot carries the subagent hold", row.completionHeldBackgroundWork)
            assertTrue(f.client.state.value.canWriteThreads)
            // The old stream was interrupted, and its late frames no longer touch the list.
            assertTrue(f.requests.contains("Interrupt" to shellIds.first()))
            f.socket()!!.send("""{"_tag":"Chunk","requestId":"${shellIds.first()}","values":[{"kind":"thread.updated","sequence":9,"location":"active","thread":${row("completed")}}]}""")
            f.socket()!!.send("""{"_tag":"Chunk","requestId":"${shellIds.last()}","values":[{"kind":"thread.updated","sequence":10,"location":"active","thread":${row("running", ""","activityRunStatus":"running"""")}}]}""")
            val live = withTimeout(5_000) { f.client.state.first { it.threads.single().status == "running" } }
            assertEquals("running", live.threads.single().activityRunStatus)
            assertTrue("Refresh is read-only", f.requests.none { it.first == "orchestration.dispatchCommand" })
        } finally { f.close() }
    }

    private fun T3Client.ageLink(millis: Long) = listOf("pongAt", "frameAt").forEach {
        T3Client::class.java.getDeclaredField(it).apply { isAccessible = true }.setLong(this, System.currentTimeMillis() - millis)
    }

    @Test fun slowSnapshotOnAHealthyLinkReportsTheRefreshButKeepsTheConnection() = runBlocking<Unit> {
        val shellIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val f = fixture { n, id -> shellIds += id; if (n == 0) "${snapshot(row("completed"))}, {\"kind\":\"synchronized\"}" else null }
        try {
            f.connect()
            try { f.client.refreshThreads(); fail("An unsynchronized refresh must not look done") } catch (_: T3Client.RequestTimedOut) { }
            assertEquals("Heartbeat is fresh: in-flight work stays certain", ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.canWriteThreads)
            // A retry joins the pending refresh rather than restarting the snapshot.
            try { f.client.refreshThreads(); fail("Still unsynchronized") } catch (_: T3Client.RequestTimedOut) { }
            assertEquals(2, shellIds.size)
            // The slow snapshot finally lands on the still-open stream.
            f.socket()!!.send("""{"_tag":"Chunk","requestId":"${shellIds.last()}","values":[${snapshot(row("running", ""","activityRunStatus":"running""""))},{"kind":"synchronized"}]}""")
            withTimeout(5_000) { f.client.state.first { it.threads.single().status == "running" } }
        } finally { f.close() }
    }

    @Test fun refreshTimeoutOnAStaleLinkFailsTheConnection() = runBlocking<Unit> {
        val f = fixture { n, _ -> if (n == 0) "${snapshot(row("completed"))}, {\"kind\":\"synchronized\"}" else null }
        try {
            f.connect()
            f.client.ageLink(SHELL_REFRESH_STALE_LINK_MS + 5_000)
            try { f.client.refreshThreads(); fail("A silent shell must not look refreshed") } catch (_: T3Client.RequestTimedOut) { }
            assertEquals(ConnectionStatus.Error, f.client.state.value.connection)
            assertFalse(f.client.state.value.canWrite)
            try { f.client.refreshThreads(); fail("Refresh needs a live connection") } catch (_: IllegalStateException) { }
        } finally { f.close() }
    }

    @Test fun resyncFollowsTheRefreshTimeoutRules() = runBlocking<Unit> {
        val f = fixture { n, _ -> if (n == 0) "${snapshot(row("completed"))}, {\"kind\":\"synchronized\"}" else null }
        try {
            f.connect()
            try { f.client.resyncThreads(); fail("An unsynchronized resync must not look done") } catch (_: T3Client.RequestTimedOut) { }
            assertEquals("Healthy link: only the resync is reported", ConnectionStatus.Connected, f.client.state.value.connection)
            f.client.ageLink(SHELL_REFRESH_STALE_LINK_MS + 5_000)
            try { f.client.resyncThreads(); fail("Joins the pending resync, which is still silent") } catch (_: T3Client.RequestTimedOut) { }
            assertEquals(2, f.requests.count { it.first == "orchestration.subscribeShell" })
            assertEquals("Stale link: dropped so reconnect runs", ConnectionStatus.Error, f.client.state.value.connection)
            try { f.client.resyncThreads(); fail("Resync needs a live connection") } catch (_: IllegalStateException) { }
        } finally { f.close() }
    }

    @Test fun refreshDuringTheFirstSnapshotWaitsForItInsteadOfResubscribing() = runBlocking<Unit> {
        val shellIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val f = fixture { _, id -> shellIds += id; null }
        try {
            f.client.connect(SavedEnvironment("env-1", "Test", f.server.url("/").toString(), "bearer"))
            withTimeout(5_000) { while (shellIds.isEmpty()) delay(20) }
            val pull = async { f.client.refreshThreads() }
            delay(200)
            assertFalse("Waits for the first snapshot", pull.isCompleted)
            f.socket()!!.send("""{"_tag":"Chunk","requestId":"${shellIds.single()}","values":[${snapshot(row("completed"))},{"kind":"synchronized"}]}""")
            withTimeout(5_000) { pull.await() }
            assertEquals(1, shellIds.size)
            assertEquals("t", f.client.state.value.threads.single().id)
        } finally { f.close() }
    }

    @Test fun emptySnapshotMarksTheShellLoaded() = runBlocking<Unit> {
        val f = fixture { _, _ -> """{"kind":"snapshot","snapshot":{"snapshotSequence":1,"projects":[],"threads":[],"archivedThreads":[]}}, {"kind":"synchronized"}""" }
        try {
            assertFalse(f.client.state.value.shellLoaded)
            f.client.connect(SavedEnvironment("env-1", "Test", f.server.url("/").toString(), "bearer"))
            val loaded = withTimeout(5_000) { f.client.state.first { it.canWriteThreads } }
            assertTrue("No threads at all is a loaded list", loaded.shellLoaded)
            assertTrue(loaded.threads.isEmpty() && loaded.projects.isEmpty())
        } finally { f.close() }
    }

    @Test fun archiveMoveRemovalKeepsTheRowInItsNewLocation() = runBlocking<Unit> {
        val f = fixture { _, _ -> "${snapshot(row("completed"))}, {\"kind\":\"synchronized\"}" }
        try {
            f.connect()
            val shellId = f.requests.first { it.first == "orchestration.subscribeShell" }.second
            f.socket()!!.send("""{"_tag":"Chunk","requestId":"$shellId","values":[{"kind":"thread.updated","sequence":2,"location":"archive","thread":${row("completed", ""","archivedAt":"2026-11-19T11:00:00Z"""")}},{"kind":"thread.removed","sequence":3,"location":"active","threadId":"t"}]}""")
            val archived = withTimeout(5_000) { f.client.state.first { it.threads.singleOrNull()?.archived == true } }
            assertEquals("t", archived.threads.single().id)
            f.socket()!!.send("""{"_tag":"Chunk","requestId":"$shellId","values":[{"kind":"thread.removed","sequence":4,"location":"archive","threadId":"t"}]}""")
            withTimeout(5_000) { f.client.state.first { it.threads.isEmpty() } }
        } finally { f.close() }
    }
}
