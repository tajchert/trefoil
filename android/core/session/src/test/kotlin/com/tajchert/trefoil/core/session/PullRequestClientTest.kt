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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [T3Client] as the pull request transport: classification, refresh stream lifecycle, forget, HTTP diff. Synthetic fixtures only. */
class PullRequestClientTest {
    private val key = PullRequestKey("p", "github.com", "acme/app", 7)

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, pullRequestMutationTimeoutMillis = 400)
        val server = MockWebServer()
        /** Every client frame after decoding, as (tag or signal, requestId/id, payload). */
        val frames = CopyOnWriteArrayList<JsonObject>()
        val diffRequests = LinkedBlockingQueue<RecordedRequest>()
        @Volatile var diffResponse: () -> MockResponse = { json("""{"patch":"$PATCH","truncated":false,"nextCursor":"c2"}""") }
        /** Answer for `pullRequests.runAction`: null holds it unanswered. */
        @Volatile var actionAnswer: ((WebSocket, String) -> Unit)? = { ws, id -> complete(ws, id, "null") }
        @Volatile var holdDetail = false
        @Volatile var socket: WebSocket? = null
        @Volatile var refreshId: String? = null
        val heldActions = LinkedBlockingQueue<String>()
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate","source-control:write"]}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path == "/api/pull-requests/diff" -> { diffRequests += request; diffResponse() }
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            frames += frame
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            val config = """{"providers":[],"environment":{"capabilities":{"pullRequests":true,"pullRequestChecks":true}},"settings":{}}"""
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, config)
                                "subscribeServerConfig" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":$config}]}""")
                                "orchestration.subscribeShell" -> chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"snapshotSequence":10,"projects":[{"id":"p","title":"P","workspaceRoot":"/w"}],"threads":[{"id":"t1","projectId":"p","title":"One"}]}},{"kind":"synchronized"}""")
                                "orchestration.subscribeThread" -> chunk(webSocket, id, """{"kind":"snapshot","projection":$PROJECTION},{"kind":"synchronized"}""")
                                "pullRequests.detail" -> if (!holdDetail) complete(webSocket, id, DETAIL)
                                "pullRequests.checks" -> complete(webSocket, id, """{"state":"open","checks":[{"name":"ci","status":"success","description":null,"url":null}]}""")
                                "pullRequests.subscribeRefreshes" -> refreshId = id
                                "pullRequests.runAction", "pullRequests.comment" -> actionAnswer?.invoke(webSocket, id) ?: heldActions.add(id)
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
            client.selectThread("t1")
            withTimeout(5000) { client.state.first { it.selectedThreadId == "t1" && it.canWrite && it.threadPullRequests.isNotEmpty() } }
        }
        fun count(tag: String) = frames.count { it.string("_tag") == "Request" && it.string("tag") == tag }
        suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            const val PATCH = "diff --git a/a.txt b/a.txt\\n--- a/a.txt\\n+++ b/a.txt\\n@@ -1 +1 @@\\n-old\\n+new\\n"
            const val PROJECTION = """{"thread":{"id":"t1","projectId":"p","pullRequests":[
                {"host":"github.com","repository":"acme/app","number":7,"url":"https://example.test/pull/7","source":"created","snapshot":{"state":"open","title":"Seven","headBranch":"h","baseBranch":"main","isDraft":false}}]},
                "messages":[],"runs":[],"turnItems":[],"runtimeRequests":[],"checkpoints":[]}"""
            const val DETAIL = """{"provider":"github","title":"Seven fresh","url":"https://example.test/pull/7","state":"open","isDraft":false,
                "headBranch":"h","baseBranch":"main","headSha":"abc","mergeability":"mergeable","additions":1,"deletions":1,"changedFiles":1,
                "capabilities":{"diff":true,"comment":true,"actions":["merge","update-branch","ready","draft"],"mergeMethods":["squash"]},
                "viewerPermissions":{"actions":["merge","update-branch","ready","draft"],"comment":true},"mergeCapabilities":{"squash":true},"checks":[]}"""
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun fail(ws: WebSocket, id: String, error: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":$error}]}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }

    /** Collects the panel (as the UI does) until it has a fresh detail; returns the collector. */
    private suspend fun Fixture.watchLoaded(): Job {
        val panel = client.prPanel(key)
        val job = scope.launch { panel.collect {} }
        withTimeout(5000) { panel.first { it.detail != null && !it.refreshing } }
        return job
    }

    @Test fun typedFailRejectsAndTimeoutIsUnknownWhileTheSessionStaysWritable() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            val watcher = f.watchLoaded()
            assertEquals("Seven fresh", f.client.prPanel(key).value.detail?.title)

            f.actionAnswer = { ws, id -> Fixture.fail(ws, id, """{"_tag":"PullRequestOperationError","operation":"draft","detail":"Head moved"}""") }
            f.client.runPullRequestAction(key, PrAction.Draft)
            assertEquals(PrActionOutcome.Rejected("T3 rejected converting to draft: Head moved"), f.client.prPanel(key).value.pendingAction?.outcome)
            assertEquals(1, f.count("pullRequests.runAction"))

            f.actionAnswer = null
            f.client.runPullRequestAction(key, PrAction.Draft)
            assertEquals(PrActionOutcome.Unknown, f.client.prPanel(key).value.pendingAction?.outcome)
            assertEquals(2, f.count("pullRequests.runAction"))
            val state = f.client.state.value
            assertEquals(ConnectionStatus.Connected, state.connection)
            assertTrue(state.canWrite)
            assertNull(state.error)
            // The timed-out request was interrupted, never resent.
            val held = f.heldActions.poll(1, TimeUnit.SECONDS)
            f.until { f.frames.any { it.string("_tag") == "Interrupt" && it.string("requestId") == held } }
            assertEquals(2, f.count("pullRequests.runAction"))
            watcher.cancel()
        }
    }

    @Test fun refreshStreamOpensOnFirstSubscriberAndIsInterruptedOnLast() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            assertEquals(0, f.count("pullRequests.subscribeRefreshes"))
            val watcher = f.watchLoaded()
            f.until { f.refreshId != null }
            assertTrue(f.client.pullRequestStreamOpen)
            // Recomposition hands back the same flow without another read.
            val before = f.count("pullRequests.detail")
            assertSame(f.client.prPanel(key), f.client.prPanel(key))
            assertEquals(before, f.count("pullRequests.detail"))
            // A tick revalidates the watched panel.
            Fixture.chunk(f.socket!!, f.refreshId!!, "1")
            f.until { f.count("pullRequests.detail") > before }
            watcher.cancel()
            f.until { f.frames.any { it.string("_tag") == "Interrupt" && it.string("requestId") == f.refreshId } }
            f.until { !f.client.pullRequestStreamOpen }
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertEquals(1, f.count("pullRequests.subscribeRefreshes"))
        }
    }

    @Test fun forgetEnvironmentClearsPanels() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            f.watchLoaded().cancel()
            assertNotNull(f.client.prPanel(key).value.detail)
            f.client.forgetEnvironment()
            f.holdDetail = true
            f.connect()
            val panel = f.client.prPanel(key)
            assertNull(panel.value.detail)
            assertEquals("Seven", panel.value.snapshot?.title)
        }
    }

    @Test fun routeSwitchWaitsWhileAMutationIsSending() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            val watcher = f.watchLoaded()
            f.actionAnswer = null
            assertTrue(f.client.isIdleForRouteSwitch())
            val running = f.scope.async { f.client.runPullRequestAction(key, PrAction.Draft, null) }
            val id = withContext(Dispatchers.IO) { f.heldActions.poll(5, TimeUnit.SECONDS) }
            assertNotNull(id)
            assertFalse(f.client.isIdleForRouteSwitch())
            Fixture.complete(f.socket!!, id!!, "null")
            running.await()
            assertTrue(f.client.isIdleForRouteSwitch())
            watcher.cancel()
        }
    }

    @Test fun diffPostsThroughTheAuthorizedLiveRouteAndMaps503() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect()
            val page = f.client.loadPullRequestDiff(key, null)
            assertEquals("c2", page.nextCursor)
            assertEquals(1, page.document.files.size)
            val request = f.diffRequests.poll(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
            assertEquals(f.server.url("/").host, request.requestUrl?.host)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("p", body.string("projectId")); assertEquals("acme/app", body.string("repository")); assertEquals(7, body["number"]?.jsonPrimitive?.int)

            f.diffResponse = { Fixture.json("""{"_tag":"PullRequestUnavailableError","reason":"provider-unsupported"}""").setResponseCode(503) }
            try { f.client.loadPullRequestDiff(key, "c2"); fail("503 accepted") }
            catch (error: PullRequestReadException) { assertEquals("This project's code host does not support pull requests", error.message) }
            assertEquals("c2", Json.parseToJsonElement(f.diffRequests.poll(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject.string("cursor"))
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }
}
