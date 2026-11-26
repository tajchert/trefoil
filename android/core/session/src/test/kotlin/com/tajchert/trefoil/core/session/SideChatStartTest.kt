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

/** `startSideChat` forks, waits for the shell, sends once, and cleans up only after a definite failure; sends carry thread context. */
class SideChatStartTest {
    private enum class Reply { Accept, Reject, Silent }

    private class Fixture(sideChats: Boolean = true, requestTimeoutMillis: Long = 30_000) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, requestTimeoutMillis = requestTimeoutMillis, sideChatShellWaitMillis = 700)
        val server = MockWebServer()
        val commands = CopyOnWriteArrayList<JsonObject>()
        @Volatile var shellStream: Pair<WebSocket, String>? = null
        @Volatile var fork = Reply.Accept
        @Volatile var message = Reply.Accept
        /** Whether an accepted fork reaches the shell. */
        @Volatile var listFork = true
        init {
            val config = """{"providers":[],"environment":{"capabilities":{"sideChats":$sideChats}},"settings":{}}"""
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
                                "server.getConfig" -> complete(webSocket, id, config)
                                "subscribeServerConfig" -> chunk(webSocket, id, """{"version":1,"type":"snapshot","config":$config}""")
                                "orchestration.subscribeShell" -> { shellStream = webSocket to id; chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"snapshotSequence":10,"projects":[{"id":"p","title":"P","workspaceRoot":"/w"}],"threads":[$THREADS]}},{"kind":"synchronized"}""") }
                                "orchestration.subscribeThread" -> {
                                    val threadId = frame.obj("payload").string("threadId")
                                    chunk(webSocket, id, """{"kind":"snapshot","projection":${projection(threadId)}},{"kind":"synchronized"}""")
                                }
                                "orchestration.getThreadProjection" -> complete(webSocket, id, projection(frame.obj("payload").string("threadId")))
                                "orchestration.dispatchCommand" -> {
                                    val command = frame.obj("payload"); commands += command
                                    val reply = when (command.string("type")) { "thread.fork" -> fork; "message.dispatch" -> message; else -> Reply.Accept }
                                    when (reply) {
                                        Reply.Accept -> {
                                            if (command.string("type") == "thread.fork" && listFork) {
                                                val (ws, shell) = shellStream!!
                                                val row = """{"id":"${command.string("targetThreadId")}","projectId":"p","title":"Side chat","lineage":{"parentThreadId":"${command.string("sourceThreadId")}","sideChat":true}}"""
                                                chunk(ws, shell, """{"kind":"thread.updated","sequence":11,"thread":$row}""")
                                            }
                                            complete(webSocket, id, """{"sequence":11}""")
                                        }
                                        Reply.Reject -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationDispatchCommandError","message":"Command rejected"}}]}}""")
                                        Reply.Silent -> Unit
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
        fun projection(threadId: String): String {
            val role = if (threadId == "parent") "user" else "assistant"
            return """{"thread":{"id":"$threadId"},"messages":[{"id":"m-$threadId","role":"$role","text":"Hello"}],"runs":[],"turnItems":[],"runtimeRequests":[]}"""
        }
        suspend fun connect() {
            client.connect(SavedEnvironment("env", "Fixture", server.url("/").toString(), "bearer-secret"))
            withTimeout(5000) { client.state.first { it.canWriteThreads } }
        }
        suspend fun select(threadId: String = "parent"): ClientState {
            client.selectThread(threadId)
            return withTimeout(5000) { client.state.first { it.selectedThreadId == threadId && it.canWrite && it.messages.isNotEmpty() } }
        }
        fun commands(type: String) = commands.filter { it.string("type") == type }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            const val THREADS = """{"id":"parent","projectId":"p","title":"Parent","latestUserMessageAt":"2026-11-06T10:00:00Z"},""" +
                """{"id":"fresh","projectId":"p","title":"Fresh"},""" +
                """{"id":"side","projectId":"p","title":"Side","latestUserMessageAt":"2026-11-06T10:00:00Z","lineage":{"parentThreadId":"parent","sideChat":true}},""" +
                """{"id":"sub","projectId":"p","title":"Sub","latestUserMessageAt":"2026-11-06T10:00:00Z","lineage":{"parentThreadId":"parent","relationshipToParent":"subagent"}},""" +
                """{"id":"other","projectId":"p","title":"Other [notes]"}"""
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable =
        try { block(); fail("Expected a failure"); error("unreachable") } catch (error: Exception) { if (error is CancellationException) throw error; error }

    @Test fun forksExactlyThenSendsTheQuestionToTheSideChat() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.select()
            val id = f.client.startSideChat("Why is the sky blue?")
            val fork = f.commands("thread.fork").single()
            assertEquals(setOf("type", "commandId", "createdBy", "creationSource", "sourceThreadId", "targetThreadId", "sourcePoint", "title", "sideChat"), fork.keys)
            assertEquals("user", fork.string("createdBy"))
            assertEquals("mobile", fork.string("creationSource"))
            assertEquals("parent", fork.string("sourceThreadId"))
            assertEquals(id, fork.string("targetThreadId"))
            assertEquals(java.util.UUID.fromString(id).toString(), id)
            assertEquals(buildJsonObject { put("type", "latest_stable") }, fork["sourcePoint"])
            assertEquals("Side chat", fork.string("title"))
            assertEquals(JsonPrimitive(true), fork["sideChat"])
            assertTrue(fork.string("commandId").isNotBlank())
            val message = f.commands("message.dispatch").single()
            assertEquals(id, message.string("threadId"))
            assertEquals("Why is the sky blue?", message.string("text"))
            assertEquals("user", message.string("createdBy"))
            assertEquals("mobile", message.string("creationSource"))
            assertEquals(JsonArray(emptyList()), message["attachments"])
            assertEquals("start_immediately", message.obj("dispatchMode").string("type"))
            assertFalse("Same fields as a normal send: no model or mode", "modelSelection" in message || "runtimeMode" in message || "context" in message)
            assertNotEquals(fork.string("commandId"), message.string("commandId"))
            assertTrue(f.commands("thread.delete").isEmpty())
            val state = f.client.state.value
            assertEquals("The selected thread stays selected", "parent", state.selectedThreadId)
            assertEquals(ConnectionStatus.Connected, state.connection)
            assertTrue(state.threads.single { it.id == id }.sideChat)
        }
    }

    @Test fun noMessageWhenTheShellNeverListsTheSideChat() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.select()
            f.listFork = false
            val error = failure { f.client.startSideChat("Question") }
            assertTrue(error.message.orEmpty().contains("not here yet"))
            val target = f.commands("thread.fork").single().string("targetThreadId")
            assertTrue(f.commands("message.dispatch").isEmpty())
            val delete = f.commands("thread.delete").single()
            assertEquals(target, delete.string("threadId"))
            assertEquals(JsonPrimitive(true), delete["onlyIfUnused"])
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }

    @Test fun definitelyRejectedMessageDeletesTheUnusedSideChatOnce() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.select()
            f.message = Reply.Reject
            val error = failure { f.client.startSideChat("Question") }
            assertFalse(error is UncertainOutcomeException)
            val target = f.commands("thread.fork").single().string("targetThreadId")
            assertEquals(1, f.commands("message.dispatch").size)
            val delete = f.commands("thread.delete").single()
            assertEquals(target, delete.string("threadId"))
            assertEquals(JsonPrimitive(true), delete["onlyIfUnused"])
            val state = f.client.state.value
            assertEquals(ConnectionStatus.Connected, state.connection)
            assertTrue(state.canWrite)
            assertEquals("parent", state.selectedThreadId)
        }
    }

    @Test fun uncertainMessageFailsTheSessionWithoutDeleting() = runBlocking<Unit> {
        Fixture(requestTimeoutMillis = 1_500).use { f ->
            f.connect(); f.select()
            f.message = Reply.Silent
            val error = failure { f.client.startSideChat("Question") }
            assertTrue(error is UncertainOutcomeException)
            assertEquals("Uncertain mutations are never replayed", 1, f.commands("message.dispatch").size)
            assertTrue(f.commands("thread.delete").isEmpty())
            val state = f.client.state.value
            assertEquals(ConnectionStatus.Error, state.connection)
            assertFalse(state.canWrite)
        }
    }

    @Test fun uncertainForkFailsTheSessionAndSendsNothingElse() = runBlocking<Unit> {
        Fixture(requestTimeoutMillis = 1_500).use { f ->
            f.connect(); f.select()
            f.fork = Reply.Silent
            assertTrue(failure { f.client.startSideChat("Question") } is UncertainOutcomeException)
            assertEquals(listOf("thread.fork"), f.commands.map { it.string("type") })
            assertEquals(ConnectionStatus.Error, f.client.state.value.connection)
        }
    }

    @Test fun rejectedForkSendsNothingElse() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.select()
            f.fork = Reply.Reject
            assertFalse(failure { f.client.startSideChat("Question") } is UncertainOutcomeException)
            assertEquals(listOf("thread.fork"), f.commands.map { it.string("type") })
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }

    @Test fun refusedLocallyWithoutSendingAnything() = runBlocking<Unit> {
        Fixture(sideChats = false).use { f ->
            f.connect(); f.select()
            assertTrue(failure { f.client.startSideChat("Question") }.message.orEmpty().contains("does not support side chats"))
            assertTrue(f.commands.isEmpty())
        }
        Fixture().use { f ->
            f.connect()
            failure { f.client.startSideChat("Question") } // nothing selected
            f.select()
            failure { f.client.startSideChat("   ") }
            f.select("side")
            assertTrue(failure { f.client.startSideChat("Question") }.message.orEmpty().contains("main conversation"))
            f.select("sub")
            assertTrue(failure { f.client.startSideChat("Question") }.message.orEmpty().contains("main conversation"))
            f.select("fresh")
            assertTrue(failure { f.client.startSideChat("Question") }.message.orEmpty().contains("first message"))
            f.select()
            f.client.disconnect()
            failure { f.client.startSideChat("Question") }
            assertTrue(f.commands.isEmpty())
        }
    }

    @Test fun sendsCarryThreadContextOnlyForResolvableMarkers() = runBlocking<Unit> {
        Fixture().use { f ->
            f.connect(); f.select()
            val marked = "See ${threadReferenceMarker("other", "Other [notes]")} please"
            f.client.sendMessage(marked)
            f.client.sendMessage(marked, FollowUpMode.Queue)
            f.client.sendMessage("No markers here")
            f.client.sendMessage("Gone ${threadReferenceMarker("missing", "Missing")}")
            val sent = f.commands("message.dispatch")
            assertEquals(4, sent.size)
            val expected = buildJsonObject {
                put("version", 1)
                put("records", buildJsonArray { add(buildJsonObject {
                    put("version", 1); put("kind", "thread"); put("contextId", "thread_other"); put("label", "Other notes")
                    put("environmentId", "env"); put("threadId", "other"); put("title", "Other notes")
                }) })
            }
            assertEquals(expected, sent[0]["context"])
            assertEquals(expected, sent[1]["context"])
            assertEquals(marked, sent[0].string("text"))
            assertFalse("context" in sent[2])
            assertFalse("context" in sent[3])
        }
    }
}
