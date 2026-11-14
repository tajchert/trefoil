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
        @Volatile var shellPayload = JsonObject(emptyMap())
        @Volatile var environmentId = "env"
        @Volatile var holdConfiguration = false
        @Volatile var mayOperate = true
        val configurations = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val itemReads = Channel<Pair<WebSocket, JsonObject>>(Channel.UNLIMITED)
        val otherCalls = Channel<String>(Channel.UNLIMITED)
        val commands = Channel<JsonObject>(Channel.UNLIMITED)
        @Volatile var holdCommands = false
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"$environmentId","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read"${if (mayOperate) ",\"orchestration:operate\"" else ""}]}""")
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
                                "orchestration.subscribeShell" -> { shellPayload = frame.obj("payload"); shells.trySend(webSocket to id) }
                                "orchestration.subscribeThread" -> { subscriptions.incrementAndGet(); threads.trySend(webSocket to id) }
                                "orchestration.getTurnItem" -> itemReads.trySend(webSocket to frame)
                                "orchestration.dispatchCommand" -> {
                                    commands.trySend(frame.obj("payload"))
                                    if (!holdCommands) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{}}}""")
                                    Unit
                                }
                                else -> otherCalls.trySend(frame.string("tag"))
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
                repeat(24) { row("settled-$it") { put("settledOverride", "settled"); put("settledAt", "2026-11-06T09:00:00Z") } }
                repeat(4) { row("open-$it") }
            }
        }
    }
    @Test fun rowActionsAndUndoKeepTheirTargetAcrossSelectionAndRejectStaleLifecycle() = runBlocking {
        Fixture().use { f ->
            val shell = f.connect(); f.shell(shell)
            f.chunk(shell, """{"kind":"thread.updated","thread":{"id":"open-0","projectId":"p","title":"Fixture","pinnedAt":"2026-11-09T08:00:00Z","pinOrderKey":"a0","snoozedUntil":"2099-01-01T00:00:00Z"}}""")
            val target = ThreadTarget("env", "open-0")
            f.client.threadAction(target, ThreadAction.Settle)
            assertEquals("open-0", withTimeout(5000) { f.commands.receive() }.string("threadId"))
            val undo = f.client.state.value.settlementUndo!!
            f.client.selectThread("open-1")
            val stream = withTimeout(5000) { f.threads.receive() }; f.threadSnapshot(stream, "Fixture")
            f.chunk(stream, """{"kind":"event","event":{"type":"thread.unsettled"}}""")
            assertFalse(f.client.state.value.canWrite)
            assertTrue(f.client.state.value.canWriteThreads)
            f.chunk(shell, """{"kind":"thread.updated","thread":{"id":"open-0","projectId":"p","pendingRequestKind":"approval"}}""")
            f.client.undoSettlement(undo)
            val command = withTimeout(5000) { f.commands.receive() }
            assertEquals("open-0", command.string("threadId"))
            assertEquals("thread.unsettle", command.string("type"))
            assertEquals("user", command.string("reason"))
            val pin = withTimeout(5000) { f.commands.receive() }
            val snooze = withTimeout(5000) { f.commands.receive() }
            assertEquals("thread.pin", pin.string("type"))
            assertEquals("a0", pin.string("orderKey"))
            assertEquals("open-0", pin.string("threadId"))
            assertEquals("thread.snooze", snooze.string("type"))
            assertEquals("2099-01-01T00:00:00Z", snooze.string("snoozedUntil"))
            assertEquals("open-0", snooze.string("threadId"))
            assertEquals("open-1", f.client.state.value.selectedThreadId)
            for ((operation, value) in listOf(ThreadAction.Pin to null, ThreadAction.Unpin to null,
                ThreadAction.Rename to "Fixture renamed", ThreadAction.Snooze to "2099-01-01T00:00:00Z", ThreadAction.Wake to null)) {
                f.client.threadAction(target, operation, value)
                val sent = withTimeout(5000) { f.commands.receive() }
                assertEquals("open-0", sent.string("threadId"))
                assertEquals("open-1", f.client.state.value.selectedThreadId)
                assertTrue(sent.string("commandId").isNotBlank())
            }
            try { f.client.threadAction(target.copy(environmentId = "other"), ThreadAction.Pin); fail("Wrong environment") } catch (_: IllegalStateException) { }
            f.client.disconnect()
            assertNull(f.client.state.value.settlementUndo)
            try { f.client.undoSettlement(undo); fail("Stale Undo") } catch (_: IllegalStateException) { }
            assertTrue(f.commands.tryReceive().isFailure)
        }
    }

    @Test fun disconnectDuringRowMutationDisablesWritesAndNeverReplays() = runBlocking {
        Fixture().use { f ->
            val shell = f.connect(); f.shell(shell); f.holdCommands = true
            val result = async { runCatching { f.client.threadAction(ThreadTarget("env", "open-0"), ThreadAction.Settle) } }
            withTimeout(5000) { f.commands.receive() }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.client.threadAction(ThreadTarget("env", "open-1"), ThreadAction.Pin) } }
            f.client.disconnect()
            assertTrue(result.await().exceptionOrNull() is UncertainOutcomeException)
            assertTrue(queued.await().isFailure)
            assertNull(f.client.state.value.settlementUndo)
            assertFalse(f.client.state.value.canWrite)
            val next = f.connect(); f.shell(next)
            assertTrue(f.commands.tryReceive().isFailure)
        }
    }

    @Test fun fullSubscriptionRetainsEverySettledRowAndChildLineageAcrossEnrichment() = runBlocking {
        Fixture().use { f ->
            val shell = f.connect()
            assertEquals(false, f.shellPayload["omitOlderSettled"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(false, f.shellPayload["omitIdleSubagents"]?.jsonPrimitive?.booleanOrNull)
            val rows = buildJsonArray {
                repeat(3) { add(buildJsonObject { put("id", "open-$it"); put("projectId", "p"); put("status", "completed") }) }
                repeat(2) { add(buildJsonObject { put("id", "working-$it"); put("projectId", "p"); put("status", "running"); put("latestRunId", "run-$it") }) }
                add(buildJsonObject { put("id", "snoozed"); put("projectId", "p"); put("snoozedUntil", "2099-01-01T00:00:00Z") })
                repeat(25) { add(buildJsonObject { put("id", "settled-$it"); put("projectId", "p"); put("settledOverride", "settled"); put("settledAt", "2026-11-06T09:00:00Z") }) }
                repeat(56) { add(buildJsonObject {
                    put("id", "child-$it"); put("projectId", "p"); put("status", "idle")
                    put("lineage", buildJsonObject { put("parentThreadId", "open-0"); put("relationshipToParent", "subagent"); put("rootThreadId", "open-0") })
                }) }
            }
            f.chunk(shell, """{"kind":"snapshot","snapshot":{"projects":[{"id":"p","title":"Fixture"}],"threads":$rows,"archivedThreads":[]}}, {"kind":"synchronized"}""")
            assertEquals(87, f.client.state.value.threads.size)
            assertEquals(25, f.client.state.value.threads.count { it.settledOverride == "settled" })
            assertEquals(56, f.client.state.value.threads.count { it.relationshipToParent == "subagent" })
            f.chunk(shell, """{"kind":"snapshot","resolvedRepositoryIdentityRoots":[],"snapshot":{"projects":[{"id":"p","title":"Enriched"}],"threads":[],"archivedThreads":[]}}""")
            assertEquals(87, f.client.state.value.threads.size)
            assertEquals(25, f.client.state.value.threads.count { it.settledOverride == "settled" })
            f.chunk(shell, """{"kind":"thread.updated","thread":{"id":"open-0","projectId":"p","settledOverride":"settled","settledAt":"2026-11-07T10:00:00Z"}}""")
            assertEquals(26, f.client.state.value.threads.count { it.settledOverride == "settled" })
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
            assertEquals("working", f.client.state.value.selectedThreadId)
            assertEquals("Working reply", f.client.state.value.messages.single().text)
            assertEquals(30, f.client.state.value.threads.size)
            assertFalse(f.client.state.value.canWrite)
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

    @Test fun conversationRunMetadataIsRetainedWithItsProjectionAndClearedForUncachedSelections() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.chunk(withTimeout(5000) { f.threads.receive() }, """{"kind":"snapshot","projection":{
                "messages":[],"turnItems":[],"runtimeRequests":[],
                "runs":[{"id":"original-run","status":"completed","completedAt":"2026-11-06T10:00:00Z"}],
                "attempts":[{"id":"original-attempt","runId":"original-run","status":"completed"}]
            }}, {"kind":"synchronized"}""")
            assertEquals("original-run", f.client.state.value.runs.single().id)
            f.client.selectThread("open-0")
            assertTrue(f.client.state.value.runs.isEmpty())
            assertTrue(f.client.state.value.attempts.isEmpty())
            f.threadSnapshot(withTimeout(5000) { f.threads.receive() }, "Other thread")
            f.client.disconnect()
            f.client.selectThread("working")
            assertEquals("original-run", f.client.state.value.runs.single().id)
            assertEquals("original-attempt", f.client.state.value.attempts.single().id)
            assertFalse(f.client.state.value.canWrite)
            f.client.disconnect(); f.environmentId = "other-env"; f.connect()
            assertTrue(f.client.state.value.runs.isEmpty())
            assertTrue(f.client.state.value.attempts.isEmpty())
        }
    }

    @Test fun strippedFileDiffLoadsOnDemandAndSurvivesUnchangedSnapshotOnly() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            val stream = withTimeout(5000) { f.threads.receive() }
            val snapshot = """{"kind":"snapshot","projection":{"messages":[],"runs":[],"runtimeRequests":[],"turnItems":[{"id":"file","type":"file_change","fileName":"Main.kt","status":"completed","updatedAt":"2026-11-06T10:00:00Z"}]}}, {"kind":"synchronized"}"""
            f.chunk(stream, snapshot)
            val load = async { f.client.loadActivity("file") }
            val (socket, request) = withTimeout(5000) { f.itemReads.receive() }
            socket.send("""{"_tag":"Exit","requestId":"${request.string("id")}","exit":{"_tag":"Success","value":{"item":{"id":"file","type":"file_change","fileName":"Main.kt","status":"completed","updatedAt":"2026-11-06T10:00:00Z","diffStr":"-old +new"}}}}""")
            load.await()
            assertEquals("-old +new", f.client.state.value.activities.single().diffStr)
            f.chunk(stream, snapshot)
            assertEquals("-old +new", f.client.state.value.activities.single().diffStr)
            f.chunk(stream, snapshot.replace("10:00:00Z", "10:01:00Z"))
            assertNull(f.client.state.value.activities.single().diffStr)
            assertTrue(f.otherCalls.tryReceive().isFailure)
        }
    }
    @Test fun expandingOmittedOutputUsesReadRpcAndRetainsTheHydratedProjectionOffline() = runBlocking {
        Fixture().use { f ->
            f.mayOperate = false
            f.shell(f.connect()); f.client.selectThread("working")
            f.chunk(withTimeout(5000) { f.threads.receive() }, omittedSnapshot)
            assertFalse(f.client.state.value.canWrite)
            val load = async { f.client.loadActivity("command") }
            val (socket, request) = withTimeout(5000) { f.itemReads.receive() }
            assertEquals("working", request.obj("payload").string("threadId"))
            assertEquals("command", request.obj("payload").string("itemId"))
            socket.send("""{"_tag":"Exit","requestId":"${request.string("id")}","exit":{"_tag":"Success","value":{"item":$fullCommand}}}""")
            load.await()
            assertEquals("Full output", f.client.state.value.activities.single().output)
            assertFalse(f.client.state.value.activities.single().outputOmitted)
            f.client.disconnect(); f.client.selectThread("working")
            assertEquals("Full output", f.client.state.value.activities.single().output)
            assertFalse(f.client.state.value.canWrite)
            try { f.client.loadActivity("command"); fail("Offline reads must fail") } catch (_: IllegalStateException) { }
            assertTrue(f.otherCalls.tryReceive().isFailure)
        }
    }

    @Test fun failedOutputReadCanRetryWithoutDisconnectingOrDispatchingAMutation() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.chunk(withTimeout(5000) { f.threads.receive() }, omittedSnapshot)
            val failed = async { runCatching { f.client.loadActivity("command") } }
            val (socket, request) = withTimeout(5000) { f.itemReads.receive() }
            socket.send("""{"_tag":"Exit","requestId":"${request.string("id")}","exit":{"_tag":"Failure","cause":{}}}""")
            assertTrue(failed.await().isFailure)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.activities.single().outputOmitted)
            val retry = async { f.client.loadActivity("command") }
            val second = withTimeout(5000) { f.itemReads.receive() }
            second.first.send("""{"_tag":"Exit","requestId":"${second.second.string("id")}","exit":{"_tag":"Success","value":{"item":$fullCommand}}}""")
            retry.await()
            assertEquals("Full output", f.client.state.value.activities.single().output)
            assertTrue(f.otherCalls.tryReceive().isFailure)
        }
    }

    @Test fun inheritedActivityReadsUseItsExplicitOwnerAndPreserveTheVisiblePosition() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            f.chunk(withTimeout(5000) { f.threads.receive() }, """{"kind":"snapshot","projection":{"messages":[],"runs":[],"runtimeRequests":[],
                "visibleTurnItems":[{"position":12,"sourceThreadId":"parent","sourceItemId":"source-command","item":{
                "id":"visible-command","threadId":"parent","type":"command_execution","ordinal":3,"runId":"parent-run",
                "status":"completed","updatedAt":"2026-11-06T10:00:00Z","input":"test","outputOmitted":true}}]}}, {"kind":"synchronized"}""")
            val load = async { f.client.loadActivity("visible-command") }
            val (socket, request) = withTimeout(5000) { f.itemReads.receive() }
            assertEquals("parent", request.obj("payload").string("threadId"))
            assertEquals("source-command", request.obj("payload").string("itemId"))
            val full = fullCommand.replace("\"command\"", "\"source-command\"").replace("\"working\"", "\"parent\"")
            socket.send("""{"_tag":"Exit","requestId":"${request.string("id")}","exit":{"_tag":"Success","value":{"item":$full}}}""")
            load.await()
            val activity = f.client.state.value.activities.single()
            assertEquals("visible-command", activity.id)
            assertEquals("parent-run", activity.runId)
            assertEquals(12, activity.ordinal)
            assertEquals("Full output", activity.output)
            assertEquals("source-command", activity.sourceItemId)
        }
    }

    @Test fun lateOutputReadCannotOverwriteANewerProjectionOrAnotherThread() = runBlocking {
        Fixture().use { f ->
            f.shell(f.connect()); f.client.selectThread("working")
            val stream = withTimeout(5000) { f.threads.receive() }
            f.chunk(stream, omittedSnapshot)
            val load = async { runCatching { f.client.loadActivity("command") } }
            val (socket, request) = withTimeout(5000) { f.itemReads.receive() }
            f.chunk(stream, omittedSnapshot.replace("completed", "failed").replace("10:00:00Z", "10:01:00Z"))
            socket.send("""{"_tag":"Exit","requestId":"${request.string("id")}","exit":{"_tag":"Success","value":{"item":$fullCommand}}}""")
            assertTrue(load.await().isFailure)
            assertEquals("failed", f.client.state.value.activities.single().status)
            assertTrue(f.client.state.value.activities.single().outputOmitted)
            val secondLoad = async { runCatching { f.client.loadActivity("command") } }
            val second = withTimeout(5000) { f.itemReads.receive() }
            f.client.selectThread("open-0")
            second.first.send("""{"_tag":"Exit","requestId":"${second.second.string("id")}","exit":{"_tag":"Success","value":{"item":$fullCommand}}}""")
            assertTrue(secondLoad.await().isFailure)
            assertEquals("open-0", f.client.state.value.selectedThreadId)
            assertTrue(f.client.state.value.activities.isEmpty())
            assertTrue(f.otherCalls.tryReceive().isFailure)
        }
    }

    private val omittedSnapshot = """{"kind":"snapshot","projection":{"messages":[],"runtimeRequests":[],"runs":[],"turnItems":[
        {"id":"command","type":"command_execution","threadId":"working","ordinal":7,"status":"completed",
        "updatedAt":"2026-11-06T10:00:00Z","input":"test","outputOmitted":true}]}}, {"kind":"synchronized"}"""
    private val fullCommand = """{"id":"command","type":"command_execution","threadId":"working","ordinal":7,"status":"completed",
        "updatedAt":"2026-11-06T10:00:00Z","input":"test","output":"Full output","exitCode":0}"""
}
