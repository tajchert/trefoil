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

class IssueSessionTest {
    @Test fun capabilityGatesReadsBeforeSendingAndDisconnectClearsIt() = runBlocking {
        Fixture(capabilities = """{"threadStop":true}""").use { f ->
            f.connect()
            assertFalse(f.client.state.value.issuesSupported)
            assertEquals(IssueFailure.NotSupported, failure { f.client.issuesList(IssueQuery()) })
            assertEquals(IssueFailure.NotSupported, failure { f.client.issueDetail(REF) })
            assertTrue(f.client.state.value.threadStopSupported)
            assertEquals(0, f.issueCalls.get())
        }
        Fixture(capabilities = """{"issues":true}""").use { f ->
            f.connect()
            assertTrue(f.client.state.value.issuesSupported)
            assertFalse(f.client.state.value.issueLinkedThreadsSupported)
            assertEquals(IssueFailure.NotSupported, failure { f.client.issueLinkedThreads(KEY) })
            assertEquals(0, f.issueCalls.get())
            f.client.disconnect()
            assertFalse(f.client.state.value.issuesSupported)
            assertEquals(IssueFailure.NotConnected, failure { f.client.issuesList(IssueQuery()) })
            assertEquals(0, f.issueCalls.get())
        }
    }

    @Test fun readsParseRepliesAndSendTheExpectedPayloads() = runBlocking {
        Fixture().use { f ->
            f.connect()
            assertTrue(f.client.state.value.issueLinkedThreadsSupported)
            f.replies["issues.list"] = { success(IssueWireTest.fixture("issue-list.json").toString()) }
            f.replies["issues.detail"] = { success(IssueWireTest.fixture("issue-detail.json").toString()) }
            f.replies["issues.comments"] = { success("""{"comments":[{"id":"c1","body":"x","createdAt":"t"}],"nextCursor":"older"}""") }
            f.replies["issues.linkedThreads"] = { success("""{"threads":[{"id":"t1","projectId":"p","title":"Fixture","archivedAt":null,"settled":false}]}""") }
            val page = f.client.issuesList(IssueQuery(text = " needle "), mapOf("github.com o/r" to "c1"))
            assertEquals(2, page.entries.size)
            val list = f.payloads.receive()
            assertEquals("issues.list", list.first)
            assertEquals("open", list.second.string("state")); assertEquals("needle", list.second.string("query"))
            assertEquals("c1", list.second.obj("cursors").string("github.com o/r"))
            assertEquals("#12", f.client.issueDetail(REF).summary.displayKey)
            val detail = f.payloads.receive().second
            assertFalse("refresh" in detail); assertEquals("1", detail.string("key"))
            val comments = f.client.issueComments(REF, "cursor-1")
            assertEquals("older", comments.nextCursor)
            assertEquals("cursor-1", f.payloads.receive().second.string("cursor"))
            assertEquals(listOf("t1"), f.client.issueLinkedThreads(KEY.copy(host = "GitHub.com")).map { it.id })
            assertEquals("github.com", f.payloads.receive().second.string("host"))
        }
    }

    @Test fun typedFailuresTimeoutsAndMalformedRepliesKeepTheSession() = runBlocking {
        Fixture().use { f ->
            f.connect()
            fun fail(error: String) = { _: JsonObject -> exit("""{"_tag":"Failure","cause":[{"_tag":"Fail","error":$error}]}""") }
            f.replies["issues.detail"] = fail("""{"_tag":"IssueUnavailableError","reason":"tracker-unsupported"}""")
            assertEquals(IssueFailure.Unavailable, failure { f.client.issueDetail(REF) })
            f.replies["issues.detail"] = fail("""{"_tag":"IssueOperationError","operation":"detail","detail":"x","reason":"not-found"}""")
            assertEquals(IssueFailure.NotFound, failure { f.client.issueDetail(REF) })
            f.replies["issues.detail"] = fail("""{"_tag":"IssueOperationError","operation":"detail","detail":"x","reason":"rate-limited","retryAt":5000}""")
            val limited = exception { f.client.issueDetail(REF) }
            assertEquals(IssueFailure.RateLimited, limited.failure); assertEquals(5000L, limited.retryAtMillis)
            f.replies["issues.list"] = fail("""{"_tag":"EnvironmentAuthorizationError"}""")
            assertEquals(IssueFailure.NoPermission, failure { f.client.issuesList(IssueQuery()) })
            f.replies["issues.list"] = { exit("""{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Missing key"}]}""") }
            assertEquals(IssueFailure.Failed, failure { f.client.issuesList(IssueQuery()) })
            f.replies["issues.detail"] = { success("""{"title":"no summary"}""") }
            assertEquals(IssueFailure.Failed, failure { f.client.issueDetail(REF) })
            f.replies["issues.list"] = { null }
            assertEquals(IssueFailure.TimedOut, failure { f.client.issuesList(IssueQuery()) })
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.canWriteThreads)
        }
    }

    @Test fun linkAndUnlinkSendOneCommandWithTheRightFields() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.client.linkIssue(TARGET, ThreadIssueLink(IssueKey("github", "GitHub.com", "o/r", "2"), "p", "https://github.com/o/r/issues/2", displayKey = "#2", title = "Two"))
            val link = f.commands.receive()
            assertEquals("thread.issue.link", link.string("type"))
            assertEquals("t1", link.string("threadId"))
            assertTrue(link.string("commandId").isNotBlank())
            assertEquals("github.com", link.string("host")); assertEquals("2", link.string("key")); assertEquals("manual", link.string("source"))
            assertEquals("#2", link.string("displayKey")); assertEquals("Two", link.string("title")); assertEquals("p", link.string("projectId"))
            f.client.unlinkIssue(TARGET, KEY.copy(host = "GITHUB.com"))
            val unlink = f.commands.receive()
            assertEquals("thread.issue.unlink", unlink.string("type"))
            assertEquals(setOf("type", "commandId", "threadId", "tracker", "host", "scope", "key"), unlink.keys)
            assertNotEquals(link.string("commandId"), unlink.string("commandId"))
        }
    }

    @Test fun localRefusalsSendNothing() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val existing = ThreadIssueLink(KEY.copy(host = "GitHub.COM", scope = "O/R"), "p", "https://github.com/o/r/issues/1", displayKey = "#1", title = "One")
            assertEquals("Already linked", runCatching { f.client.linkIssue(TARGET, existing) }.exceptionOrNull()?.message)
            assertEquals("Not linked", runCatching { f.client.unlinkIssue(TARGET, KEY.copy(key = "99")) }.exceptionOrNull()?.message)
            assertTrue(runCatching { f.client.linkIssue(TARGET, existing.copy(key = KEY.copy(key = "5"), title = " ")) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { f.client.linkIssue(ThreadTarget("env", "archived"), existing.copy(key = KEY.copy(key = "5"))) }.isFailure)
            assertTrue(runCatching { f.client.linkIssue(ThreadTarget("other", "t1"), existing.copy(key = KEY.copy(key = "5"))) }.isFailure)
            assertTrue(f.commands.tryReceive().isFailure)
            assertTrue(f.client.state.value.canWriteThreads)
        }
    }

    @Test fun rejectionKeepsTheSessionAndTimeoutFailsItWithoutResending() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val link = ThreadIssueLink(IssueKey("github", "github.com", "o/r", "2"), "p", "https://github.com/o/r/issues/2", displayKey = "#2", title = "Two")
            f.commandReply = { exit("""{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationV2DispatchCommandError","message":"x"}}]}""") }
            val rejected = runCatching { f.client.linkIssue(TARGET, link) }.exceptionOrNull()
            assertTrue(rejected is IllegalStateException && rejected !is UncertainOutcomeException)
            assertTrue(rejected!!.message!!.contains("rejected the issue link"))
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.canWriteThreads)
            f.commands.receive()
            f.commandReply = { null }
            assertTrue(runCatching { f.client.linkIssue(TARGET, link) }.exceptionOrNull() is UncertainOutcomeException)
            f.commands.receive()
            assertEquals(ConnectionStatus.Error, f.client.state.value.connection)
            assertFalse(f.client.state.value.canWriteThreads)
            assertFalse(f.client.state.value.issuesSupported)
            delay(400)
            assertTrue(f.commands.tryReceive().isFailure)
            assertEquals(2, f.commandCount.get())
        }
    }

    private suspend fun exception(block: suspend () -> Any): IssueException {
        try { block(); error("Expected IssueException") } catch (error: IssueException) { return error }
    }
    private suspend fun failure(block: suspend () -> Any): IssueFailure = exception(block).failure

    private companion object {
        val KEY = IssueKey("github", "github.com", "o/r", "1")
        val REF = IssueRef("p", "github", "github.com", "o/r", "1")
        val TARGET = ThreadTarget("env", "t1")
        fun exit(value: String) = value
        fun success(value: String) = """{"_tag":"Success","value":$value}"""
    }

    private class Fixture(capabilities: String = """{"issues":true,"issueLinkedThreads":true}""") : AutoCloseable {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope, requestTimeoutMillis = 300, issueReadTimeoutMillis = 300)
        val issueCalls = AtomicInteger()
        val commandCount = AtomicInteger()
        val payloads = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
        val commands = Channel<JsonObject>(Channel.UNLIMITED)
        /** Per tag: the Exit JSON to answer with, or null to never answer. */
        val replies = java.util.concurrent.ConcurrentHashMap<String, (JsonObject) -> String?>()
        @Volatile var commandReply: (JsonObject) -> String? = { success("""{"sequence":1}""") }
        private val config = """{"providers":[],"environment":{"capabilities":$capabilities}}"""
        private val rows = """[
            {"id":"t1","projectId":"p","title":"Fixture","issues":[{"tracker":"github","host":"github.com","scope":"o/r","key":"1","projectId":"p","url":"https://github.com/o/r/issues/1","source":"manual","linkedAt":"2026-11-01T00:00:00Z","displayKey":"#1","title":"One"}]},
            {"id":"archived","projectId":"p","title":"Archived","archivedAt":"2026-11-01T00:00:00Z"}
        ]"""
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}""")
                    request.path.orEmpty().startsWith("/api/auth/websocket-ticket") -> json("""{"ticket":"fixture"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            val tag = frame.string("tag")
                            fun answer(exit: String?) { if (exit != null) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":$exit}""") }
                            when {
                                tag == "server.getConfig" -> answer(success(config))
                                tag == "subscribeServerConfig" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":$config}]}""")
                                tag == "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[{"id":"p","title":"Project"}],"threads":$rows}},{"kind":"synchronized"}]}""")
                                tag == "orchestration.dispatchCommand" -> {
                                    commandCount.incrementAndGet()
                                    val payload = frame.obj("payload"); commands.trySend(payload)
                                    answer(commandReply(payload))
                                }
                                tag.startsWith("issues.") -> {
                                    issueCalls.incrementAndGet()
                                    val payload = frame.obj("payload"); payloads.trySend(tag to payload)
                                    answer(replies[tag]?.invoke(payload) ?: return)
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
            client.connect(SavedEnvironment("env", "Fixture", server.url("/").toString(), "fixture-token"))
            withTimeout(5_000) { client.state.first { it.canWriteThreads && it.threads.isNotEmpty() } }
        }
        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
    }
}
