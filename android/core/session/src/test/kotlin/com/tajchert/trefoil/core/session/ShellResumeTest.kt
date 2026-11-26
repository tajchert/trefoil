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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class ShellResumeTest {
    /** A hang fails in seconds instead of parking the test worker. */
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val origin = ShellOrigin("env", "1.2.3", 40)
    /** Rows as the client hands them over: compared by identity, so equal but different lists never resume. */
    private val rows = ShellRows(mutableListOf<Any>(), mutableListOf<Any>())
    private fun ShellResume.record(item: JsonObject) = applied(item, rows)
    private fun item(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun snapshot(sequence: Long) = item("""{"kind":"snapshot","snapshot":{"snapshotSequence":$sequence,"projects":[],"threads":[]}}""")
    private fun delta(sequence: Long) = item("""{"kind":"thread.removed","sequence":$sequence,"location":"active","threadId":"t"}""")

    @Test fun originNeedsTheServerBuildOfTheSameEnvironment() {
        assertEquals(origin, shellOrigin("env", item("""{"environment":{"environmentId":"env","serverVersion":" 1.2.3 ","databaseMigration":40}}""")))
        assertEquals(ShellOrigin("env", "1.2.3", null), shellOrigin("env", item("""{"environment":{"serverVersion":"1.2.3"}}""")))
        assertNull(shellOrigin("env", item("""{"providers":[]}""")))
        assertNull(shellOrigin("env", item("""{"environment":{"serverVersion":""}}""")))
        assertNull(shellOrigin("env", item("""{"environment":{"environmentId":"other","serverVersion":"1.2.3"}}""")))
        assertNull(shellOrigin("env", item("""{"environment":{"serverVersion":"1.2.3","databaseMigration":"40"}}""")))
    }

    @Test fun inputCarriesTheCursorOnlyWhenResuming() {
        val fresh = shellSubscribeInput(null)
        assertNull(fresh["afterSequence"])
        assertEquals(true, fresh["requestCompletionMarker"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, fresh["omitIdleSubagents"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, fresh["omitOlderSettled"]?.jsonPrimitive?.booleanOrNull)
        assertNull(fresh["httpSnapshotFallback"])
        assertEquals(12L, shellSubscribeInput(12)["afterSequence"]?.jsonPrimitive?.longOrNull)
    }

    @Test fun cursorFollowsAuthoritativeSnapshotsAndDeltasButNotEnrichment() {
        val resume = ShellResume { 1_000 }
        assertNull(resume.begin("env", origin, rows, retainedEnvironments = setOf("env")))
        resume.record(snapshot(10))
        assertEquals(10L, resume.cursor("env")?.sequence)
        resume.record(delta(14)); resume.record(delta(12))
        assertEquals(14L, resume.cursor("env")?.sequence)
        resume.record(item("""{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/r"],"snapshot":{"snapshotSequence":99,"projects":[],"threads":[]}}"""))
        resume.record(item("""{"kind":"synchronized"}"""))
        assertEquals(14L, resume.cursor("env")?.sequence)
        assertEquals(14L, resume.begin("env", origin, rows, retainedEnvironments = setOf("env")))
        resume.record(snapshot(3))
        assertEquals(3L, resume.cursor("env")?.sequence)
    }

    @Test fun anythingUnaccountedDropsTheCursor() {
        val resume = ShellResume { 1_000 }
        fun started(): ShellResume { resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(10)); return resume }
        started().record(item("""{"kind":"thread.updated","location":"active","thread":{"id":"t"}}"""))
        assertNull(resume.cursor("env"))
        started().record(item("""{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}}"""))
        assertNull(resume.cursor("env"))
        started().record(item("""{"kind":"snapshot-required"}"""))
        assertNull(resume.cursor("env"))
        resume.begin("env", null, rows, setOf("env")); resume.record(snapshot(10))
        assertNull(resume.cursor("env"))
    }

    @Test fun cursorBelongsToTheExactRowsItWasRecordedWith() {
        val resume = ShellResume { 1_000 }
        resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(10))
        val changed = ShellRows(rows.projects, mutableListOf<Any>("enriched"))
        resume.applied(item("""{"kind":"snapshot","resolvedRepositoryIdentityRoots":[],"snapshot":{"snapshotSequence":99,"projects":[],"threads":[]}}"""), changed)
        assertNull("Rows from before the last tracked item", resume.begin("env", origin, rows, setOf("env")))
        resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(10))
        resume.applied(item("""{"kind":"synchronized"}"""), changed)
        assertEquals(10L, resume.begin("env", origin, changed, setOf("env")))
        resume.forget("env")
        assertNull(resume.begin("env", origin, changed, setOf("env")))
    }

    @Test fun resumesOnlyRetainedRowsOfTheSameRecentOrigin() {
        var now = 1_000L
        val resume = ShellResume { now }
        fun cursorAt(sequence: Long) { resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(sequence)) }
        cursorAt(10)
        assertNull(resume.begin("env", origin, ShellRows(mutableListOf<Any>(), mutableListOf<Any>()), retainedEnvironments = setOf("env")))
        cursorAt(10)
        assertNull(resume.begin("env", origin.copy(serverVersion = "1.2.4"), rows, setOf("env")))
        cursorAt(10)
        assertNull(resume.begin("env", origin.copy(databaseMigration = 41), rows, setOf("env")))
        cursorAt(10)
        assertNull(resume.begin("env", null, rows, setOf("env")))
        cursorAt(10)
        now += SHELL_RESUME_MAX_AGE_MS
        assertNull(resume.begin("env", origin, rows, setOf("env")))
        cursorAt(10)
        resume.begin("other", origin.copy(environmentId = "other"), rows, setOf("other"))
        assertNull("Evicted rows take their cursor along", resume.cursor("env"))
    }

    @Test fun snapshotRecordedBeforeTheOriginWaitsForIt() {
        var now = 1_000L
        val resume = ShellResume { now }
        assertFalse(resume.mayResume("env", rows))
        resume.beginBeforeOrigin("env", setOf("env"))
        resume.record(snapshot(10)); resume.record(delta(11))
        assertFalse("No origin yet: never resumes", resume.mayResume("env", rows))
        resume.originKnown(origin)
        assertTrue(resume.mayResume("env", rows))
        assertFalse("Other rows", resume.mayResume("env", ShellRows(mutableListOf<Any>(), mutableListOf<Any>())))
        assertEquals(11L, resume.begin("env", origin, rows, setOf("env")))
        resume.originKnown(null)
        assertEquals("No pending origin: a no-op", 11L, resume.cursor("env")?.sequence)
        resume.beginBeforeOrigin("env", setOf("env"))
        assertNull("A snapshot start drops the old cursor", resume.cursor("env"))
        resume.record(snapshot(12))
        resume.originKnown(null)
        assertNull("An unnamed build carries no cursor", resume.cursor("env"))
        resume.beginBeforeOrigin("env", setOf("env")); resume.record(snapshot(13)); resume.originKnown(origin)
        now += SHELL_RESUME_MAX_AGE_MS
        assertFalse("Too old", resume.mayResume("env", rows))
    }

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var client = T3Client(scope)
            private set
        val server = MockWebServer()
        val shells = Channel<Triple<WebSocket, String, JsonObject>>(Channel.UNLIMITED)
        val acks = Channel<String>(Channel.UNLIMITED)
        val interrupts = Channel<String>(Channel.UNLIMITED)
        @Volatile var environmentId = "env"
        @Volatile var serverVersion: String? = "1.2.3"
        @Volatile var sessionDown = false
        /** When set, the WebSocket ticket request waits for it: the connect is in progress and has not planned the shell. */
        @Volatile var ticketHold: java.util.concurrent.CountDownLatch? = null
        /** When false, first-config requests (`subscribeServerConfig`, or `server.getConfig`) wait in [heldConfigs] for [answerConfig]. */
        @Volatile var answerConfigs = true
        val heldConfigs = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        /** Request ids of `subscribeServerConfig` streams, answered with a snapshot chunk instead of an Exit. */
        val configStreams: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** An older server: `subscribeServerConfig` is rejected, so the first config comes from `server.getConfig`. */
        @Volatile var rejectConfigStream = false
        /** Socket request tags and HTTP paths in arrival order. */
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = log.add(request.path.orEmpty().substringBefore("?")).let { when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"$environmentId","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" && sessionDown -> MockResponse().setResponseCode(503)
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}""")
                    request.path.orEmpty().startsWith("/api/auth/websocket-ticket") -> {
                        ticketHold?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        json("""{"ticket":"fixture"}""")
                    }
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            val id = frame.string("id")
                            when (frame.string("_tag")) {
                                "Ack" -> { acks.trySend(frame.string("requestId")); return }
                                "Interrupt" -> { interrupts.trySend(frame.string("requestId")); return }
                                "Request" -> Unit
                                else -> return
                            }
                            log.add(frame.string("tag"))
                            when (frame.string("tag")) {
                                "subscribeServerConfig" -> if (rejectConfigStream) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Unknown request tag"}]}}""") else { configStreams += id; if (answerConfigs) answerConfig(webSocket to id) else heldConfigs.trySend(webSocket to id) }
                                "server.getConfig" -> if (answerConfigs) answerConfig(webSocket to id) else heldConfigs.trySend(webSocket to id)
                                "orchestration.subscribeShell" -> shells.trySend(Triple(webSocket, id, frame.obj("payload")))
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                } }
            }
            server.start()
        }
        fun answerConfig(request: Pair<WebSocket, String>) {
            val environment = serverVersion?.let { """"environment":{"environmentId":"$environmentId","serverVersion":"$it","databaseMigration":40,"capabilities":{}},""" }.orEmpty()
            val config = """{$environment"providers":[]}"""
            if (request.second in configStreams) request.first.send("""{"_tag":"Chunk","requestId":"${request.second}","values":[{"version":1,"type":"snapshot","config":$config}]}""")
            else request.first.send("""{"_tag":"Exit","requestId":"${request.second}","exit":{"_tag":"Success","value":$config}}""")
        }
        fun environment() = SavedEnvironment(environmentId, "Fixture", server.url("/").toString(), "fixture-token")
        suspend fun connect(): Triple<WebSocket, String, JsonObject> { client.connect(environment()); return nextShell() }
        suspend fun nextShell() = withTimeout(5000) { shells.receive() }
        suspend fun chunk(stream: Triple<WebSocket, String, JsonObject>, values: String) {
            stream.first.send("""{"_tag":"Chunk","requestId":"${stream.second}","values":[$values]}""")
            withTimeout(5000) { while (acks.receive() != stream.second) {} }
        }
        fun rows() = client.state.value.threads.sortedBy { it.id } to client.state.value.projects.sortedBy { it.id }
        /** An app restart: a new client on the same server, with nothing in memory. */
        fun restart() { client.close(); client = T3Client(scope) }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun thread(id: String, title: String = id, status: String = "idle") = """{"id":"$id","projectId":"p","title":"$title","status":"$status"}"""
            fun shell(sequence: Int, projects: String, threads: String) =
                """{"kind":"snapshot","snapshot":{"snapshotSequence":$sequence,"projects":[$projects],"threads":[$threads],"archivedThreads":[]}}"""
            const val project = """{"id":"p","title":"Project","workspaceRoot":"/fixture/p"}"""
        }
    }

    private val Triple<WebSocket, String, JsonObject>.afterSequence get() = third["afterSequence"]?.jsonPrimitive?.longOrNull

    @Test fun reconnectReplaysMissedDeltasIntoTheRowsAFreshSnapshotGives() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            assertNull(first.afterSequence)
            f.chunk(first, """${Fixture.shell(10, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")},${Fixture.thread("c")}")}, {"kind":"synchronized"}""")
            f.chunk(first, """{"kind":"thread.updated","sequence":12,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
            assertTrue(f.client.state.value.canWrite)
            f.client.disconnect()

            val resumed = f.connect()
            assertEquals(12L, resumed.afterSequence)
            assertEquals(3, f.client.state.value.threads.size)
            // The resume answer: identity enrichment, the coalesced replay, then the completion marker.
            f.chunk(resumed, """{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/fixture/p"],"snapshot":{"snapshotSequence":17,"projects":[{"id":"p","title":"Renamed","workspaceRoot":"/fixture/p"}],"threads":[],"archivedThreads":[]}}""")
            f.chunk(resumed, """{"kind":"thread.removed","sequence":13,"location":"active","threadId":"b"},
                {"kind":"thread.updated","sequence":14,"location":"active","thread":${Fixture.thread("c", "C1", "running")}},
                {"kind":"project.updated","sequence":15,"project":{"id":"p","title":"Renamed","workspaceRoot":"/fixture/p"}},
                {"kind":"project.updated","sequence":16,"project":{"id":"q","title":"New","workspaceRoot":"/fixture/q"}},
                {"kind":"thread.updated","sequence":17,"location":"active","thread":${Fixture.thread("d")}}""")
            assertFalse("Writes wait for the completion marker", f.client.state.value.canWrite)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            assertTrue(f.client.state.value.canWrite)
            val afterResume = f.rows()

            // What the server would have answered without a cursor; a new build never resumes.
            f.client.disconnect(); f.serverVersion = "1.2.4"
            val fresh = f.connect()
            assertNull(fresh.afterSequence)
            f.chunk(fresh, """${Fixture.shell(17, """{"id":"p","title":"Renamed","workspaceRoot":"/fixture/p"},{"id":"q","title":"New","workspaceRoot":"/fixture/q"}""",
                "${Fixture.thread("a", "A1")},${Fixture.thread("c", "C1", "running")},${Fixture.thread("d")}")}, {"kind":"synchronized"}""")
            assertEquals(f.rows(), afterResume)
            f.client.disconnect()
            assertEquals(17L, f.connect().afterSequence)
        }
    }

    @Test fun serverThatCannotReplayOrIgnoresTheCursorAnswersAnAuthoritativeSnapshot() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")}")}, {"kind":"synchronized"}""")
            f.client.disconnect()
            val resumed = f.connect()
            assertEquals(10L, resumed.afterSequence)
            f.chunk(resumed, Fixture.shell(2_000, Fixture.project, Fixture.thread("z")))
            assertEquals(listOf("z"), f.client.state.value.threads.map { it.id })
            assertFalse(f.client.state.value.canWrite)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            assertTrue(f.client.state.value.canWrite)
            f.client.disconnect()
            assertEquals(2_000L, f.connect().afterSequence)
        }
    }

    @Test fun userRefreshDropsTheCursorForAFullSnapshotThatSeedsTheNextResume() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            f.chunk(first, """{"kind":"thread.updated","sequence":12,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
            val refresh = async { f.client.refreshThreads() }
            val full = f.nextShell()
            assertNull("A refresh never resumes from the cursor", full.afterSequence)
            assertEquals(first.second, withTimeout(5000) { f.interrupts.receive() })
            f.chunk(full, """${Fixture.shell(20, Fixture.project, Fixture.thread("a", "A2", "running"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { refresh.await() }
            assertEquals("A2", f.client.state.value.threads.single().title)
            // Late frames of the replaced stream move neither the rows nor the cursor.
            first.first.send("""{"_tag":"Chunk","requestId":"${first.second}","values":[{"kind":"thread.updated","sequence":30,"location":"active","thread":${Fixture.thread("a", "Stale")}}]}""")
            f.client.disconnect()
            assertEquals(20L, f.connect().afterSequence)
            assertEquals("A2", f.client.state.value.threads.single().title)
        }
    }

    @Test fun streamFailureWhileTheConfigIsAppliedNeverLeavesAFalseConnected() = runBlocking {
        Fixture().use { f ->
            f.answerConfigs = false
            val connecting = async { runCatching { f.client.connect(f.environment()) } }
            val shell = f.nextShell()
            val config = withTimeout(5000) { f.heldConfigs.receive() }
            // The early shell stream ends after the config answered, before the connection is published.
            f.client.beforeConnectPublished = {
                shell.first.send("""{"_tag":"Exit","requestId":"${shell.second}","exit":{"_tag":"Success","value":null}}""")
                val until = System.currentTimeMillis() + 5_000
                while (f.client.state.value.connection != ConnectionStatus.Error && System.currentTimeMillis() < until) Thread.sleep(5)
            }
            f.answerConfig(config)
            assertTrue("The connect reports the failure", withTimeout(5000) { connecting.await() }.isFailure)
            val state = f.client.state.value
            assertEquals(ConnectionStatus.Error, state.connection)
            assertFalse(state.canWrite || state.canWriteThreads || state.threadActionsConfigured)
        }
    }

    @Test fun pullDuringTheReconnectReplayUpgradesItToAFullSnapshot() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            f.client.disconnect()
            val replay = f.connect()
            assertEquals(10L, replay.afterSequence)
            val pull = async { f.client.refreshThreads() }
            assertEquals("The replay is interrupted", replay.second, withTimeout(5000) { f.interrupts.receive() })
            val full = f.nextShell()
            assertNull("A pull corrects the rows: full snapshot", full.afterSequence)
            f.chunk(full, """${Fixture.shell(20, Fixture.project, Fixture.thread("b"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { pull.await() }
            assertEquals(listOf("b"), f.client.state.value.threads.map { it.id })
            assertTrue(f.client.state.value.canWriteThreads)
        }
    }

    @Test fun pullJoinsAResyncThatHadNoCursorToResumeFrom() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            // No snapshot sequence: no cursor, so the resync below is a full snapshot already.
            f.chunk(first, """{"kind":"snapshot","snapshot":{"projects":[${Fixture.project}],"threads":[${Fixture.thread("a")}],"archivedThreads":[]}}, {"kind":"synchronized"}""")
            val resync = async { f.client.resyncThreads() }
            val full = f.nextShell()
            assertNull(full.afterSequence)
            val pull = async { f.client.refreshThreads() }
            delay(300)
            assertTrue("The pull joins it instead of restarting it", f.shells.tryReceive().isFailure)
            assertEquals(listOf(first.second), generateSequence { f.interrupts.tryReceive().getOrNull() }.toList())
            f.chunk(full, """${Fixture.shell(20, Fixture.project, Fixture.thread("b"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { resync.await(); pull.await() }
            assertEquals(listOf("b"), f.client.state.value.threads.map { it.id })
        }
    }

    @Test fun coldConnectAsksForTheShellWithoutWaitingForTheConfig() = runBlocking {
        Fixture().use { f ->
            f.answerConfigs = false
            val connecting = async { f.client.connect(f.environment()) }
            val shell = f.nextShell()
            assertNull(shell.afterSequence)
            val config = withTimeout(5000) { f.heldConfigs.receive() }
            assertTrue("The config is asked first", f.log.indexOf("subscribeServerConfig") < f.log.indexOf("orchestration.subscribeShell"))
            assertFalse("The stream snapshot is the first config", "server.getConfig" in f.log)
            f.chunk(shell, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            val early = f.client.state.value
            assertEquals(ConnectionStatus.Connecting, early.connection)
            assertTrue(early.shellLoaded)
            assertFalse("Writes wait for a configured connection", early.canWrite || early.canWriteThreads || early.canRead || early.threadActionsConfigured)
            f.answerConfig(config)
            withTimeout(5000) { connecting.await() }
            val live = withTimeout(5000) { f.client.state.first { it.canWrite } }
            assertTrue(live.canWriteThreads && live.threadActionsConfigured && live.connection == ConnectionStatus.Connected)
            withTimeout(5000) { while ("/api/connect/link-state" !in f.log) delay(10) }
            // The early snapshot's cursor took its origin from the config, so the next connect resumes.
            f.client.disconnect(); f.answerConfigs = true
            assertEquals(10L, f.connect().afterSequence)
        }
    }

    @Test fun aServerRejectingTheConfigStreamGivesTheFirstConfigThroughGetConfig() = runBlocking {
        Fixture().use { f ->
            f.rejectConfigStream = true
            f.chunk(f.connect(), """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { f.client.state.first { it.canWrite } }
            assertTrue("server.getConfig" in f.log)
            assertTrue(f.log.indexOf("subscribeServerConfig") < f.log.indexOf("server.getConfig"))
            // The origin still came from the config, so the next connect resumes.
            f.client.disconnect()
            assertEquals(10L, f.connect().afterSequence)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }

    @Test fun resumableCursorWaitsForTheConfigAndRouteHintsWaitForTheShell() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            // This connection's own hints arrive first, so none of them count against the next one.
            withTimeout(5000) { while ("/api/connect/link-state" !in f.log) delay(10) }
            f.client.disconnect(); f.log.clear(); f.answerConfigs = false
            val connecting = async { f.client.connect(f.environment()) }
            val config = withTimeout(5000) { f.heldConfigs.receive() }
            delay(200)
            assertTrue("The origin decides whether the cursor resumes", f.shells.tryReceive().isFailure)
            f.answerConfig(config)
            val resumed = f.nextShell()
            assertEquals(10L, resumed.afterSequence)
            withTimeout(5000) { connecting.await() }
            delay(200)
            assertFalse("No link-state read before the shell is synchronized", "/api/connect/link-state" in f.log)
            assertEquals("One config stream per connection, no second config", 1, f.log.count { it == "subscribeServerConfig" || it == "server.getConfig" })
            f.chunk(resumed, """{"kind":"synchronized"}""")
            withTimeout(5000) { while ("/api/connect/link-state" !in f.log) delay(10) }
            assertEquals(1, f.log.count { it == "subscribeServerConfig" || it == "server.getConfig" })
            assertTrue(f.client.state.value.canWrite)
        }
    }

    @Test fun resyncCatchesUpFromTheCursorOnTheLiveConnection() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")}")}, {"kind":"synchronized"}""")
            f.chunk(first, """{"kind":"thread.updated","sequence":12,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
            val resync = async { f.client.resyncThreads() }
            val resumed = f.nextShell()
            assertEquals("Only the missed changes are asked for", 12L, resumed.afterSequence)
            assertEquals("The old stream is stopped first", first.second, withTimeout(5000) { f.interrupts.receive() })
            // Late frames of the replaced stream are dropped.
            f.chunk(first, """{"kind":"thread.updated","sequence":13,"location":"active","thread":${Fixture.thread("a", "Stale")}}""")
            f.chunk(resumed, """{"kind":"thread.removed","sequence":13,"location":"active","threadId":"b"},{"kind":"thread.updated","sequence":14,"location":"active","thread":${Fixture.thread("a", "A2")}}""")
            assertFalse("Not done before the completion marker", resync.isCompleted)
            assertTrue("A catch-up on the same connection keeps writes", f.client.state.value.canWrite)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            withTimeout(5000) { resync.await() }
            assertEquals(listOf("a" to "A2"), f.client.state.value.threads.map { it.id to it.title })
            assertTrue(f.client.state.value.canWrite)
            f.client.disconnect()
            assertEquals("The catch-up moved the cursor", 14L, f.connect().afterSequence)
        }
    }

    @Test fun resyncWithoutAUsableCursorLoadsAFullSnapshot() = runBlocking {
        Fixture().use { f ->
            f.serverVersion = null
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            val resync = async { f.client.resyncThreads() }
            val full = f.nextShell()
            assertNull("No origin, no cursor: a snapshot", full.afterSequence)
            f.chunk(full, """${Fixture.shell(11, Fixture.project, Fixture.thread("b"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { resync.await() }
            assertEquals(listOf("b"), f.client.state.value.threads.map { it.id })
        }
    }

    @Test fun resyncAndPullShareOnePendingRefreshAndAPullStaysFull() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            // A resync during a pending pull joins it.
            val pull = async { f.client.refreshThreads() }
            val full = f.nextShell()
            assertNull(full.afterSequence)
            assertEquals(first.second, withTimeout(5000) { f.interrupts.receive() })
            val joined = async { f.client.resyncThreads() }
            delay(200)
            assertTrue("No second stream", f.shells.tryReceive().isFailure)
            f.chunk(full, """${Fixture.shell(20, Fixture.project, Fixture.thread("a", "A1"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { pull.await(); joined.await() }

            // A pull during a pending resync upgrades it to a full snapshot on the same completion.
            val resync = async { f.client.resyncThreads() }
            val resumed = f.nextShell()
            assertEquals(20L, resumed.afterSequence)
            val upgrade = async { f.client.refreshThreads() }
            val upgraded = f.nextShell()
            assertNull("A pull never resumes", upgraded.afterSequence)
            assertEquals(full.second, withTimeout(5000) { f.interrupts.receive() })
            assertEquals(resumed.second, withTimeout(5000) { f.interrupts.receive() })
            f.chunk(resumed, """{"kind":"thread.updated","sequence":21,"location":"active","thread":${Fixture.thread("a", "Stale")}}, {"kind":"synchronized"}""")
            assertFalse("The replaced resync stream completes nothing", resync.isCompleted)
            f.chunk(upgraded, """${Fixture.shell(30, Fixture.project, Fixture.thread("a", "A3"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { resync.await(); upgrade.await() }
            assertEquals("A3", f.client.state.value.threads.single().title)
            delay(100)
            assertTrue(f.shells.tryReceive().isFailure)
        }
    }

    @Test fun lostConnectionMidReplayResumesFromTheLastAppliedDelta() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            f.client.disconnect()
            val resumed = f.connect()
            f.chunk(resumed, """{"kind":"thread.updated","sequence":11,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
            resumed.first.close(1011, null)
            withTimeout(5000) { while (f.client.state.value.connection != ConnectionStatus.Error) yield() }
            assertFalse(f.client.state.value.canWrite)
            f.client.reconnect()
            val again = f.nextShell()
            assertEquals(11L, again.afterSequence)
            assertEquals("A1", f.client.state.value.threads.single().title)
        }
    }

    @Test fun otherEnvironmentsAndServersWithoutABuildNeverResume() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), Fixture.shell(10, Fixture.project, Fixture.thread("a")))
            f.client.disconnect(); f.environmentId = "another"
            val other = f.connect()
            assertNull(other.afterSequence)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.chunk(other, Fixture.shell(50, Fixture.project, Fixture.thread("x")))
            f.client.disconnect(); f.environmentId = "env"
            assertEquals("Each environment keeps its own cursor", 10L, f.connect().afterSequence)
            assertEquals(listOf("a"), f.client.state.value.threads.map { it.id })
            f.client.forgetEnvironment("another")
            f.client.disconnect(); f.environmentId = "another"
            assertNull("Forgotten rows cannot resume", f.connect().afterSequence)

            f.client.disconnect(); f.environmentId = "env"; f.serverVersion = null
            val legacy = f.connect()
            assertNull(legacy.afterSequence)
            f.chunk(legacy, Fixture.shell(60, Fixture.project, Fixture.thread("a")))
            f.client.disconnect(); f.serverVersion = "1.2.3"
            assertNull("Rows from an unnamed build carry no cursor", f.connect().afterSequence)
        }
    }

    @Test fun emptyRowsAfterForgettingNeverResumeTheOldCursor() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), """${Fixture.shell(100, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")}")}, {"kind":"synchronized"}""")
            f.client.disconnect()
            // Forget and re-pair the same environment id; the first attempt fails before the shell subscription.
            f.client.forgetEnvironment("env")
            f.sessionDown = true
            assertTrue(runCatching { f.client.connect(f.environment()) }.isFailure)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.sessionDown = false
            val retry = f.connect()
            assertNull("Empty rows must load a snapshot", retry.afterSequence)
            assertFalse(f.client.state.value.canWrite)
            f.chunk(retry, """${Fixture.shell(120, Fixture.project, Fixture.thread("c"))}, {"kind":"synchronized"}""")
            assertEquals(listOf("c"), f.client.state.value.threads.map { it.id })
            assertTrue(f.client.state.value.canWrite)
        }
    }

    @Test fun evictedEnvironmentStartsFromASnapshot() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), """${Fixture.shell(100, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            for (id in listOf("e1", "e2", "e3", "e4")) {
                f.client.disconnect(); f.environmentId = id
                f.chunk(f.connect(), """${Fixture.shell(5, Fixture.project, Fixture.thread(id))}, {"kind":"synchronized"}""")
            }
            f.client.disconnect(); f.environmentId = "env"
            assertNull(f.connect().afterSequence)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.client.disconnect(); f.environmentId = "e4"
            assertEquals(5L, f.connect().afterSequence)
        }
    }

    @Test fun shellLoadedTellsAnEmptyListFromOneStillLoading() = runBlocking {
        Fixture().use { f ->
            assertFalse(f.client.state.value.shellLoaded)
            val first = f.connect()
            assertFalse("A fresh environment has no snapshot yet", f.client.state.value.shellLoaded)
            f.chunk(first, """{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/fixture/p"],"snapshot":{"snapshotSequence":3,"projects":[${Fixture.project}],"threads":[],"archivedThreads":[]}}""")
            assertFalse("Project enrichment is not a thread snapshot", f.client.state.value.shellLoaded)
            f.chunk(first, Fixture.shell(10, Fixture.project, ""))
            assertTrue("An empty snapshot is loaded, not loading", f.client.state.value.shellLoaded)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.client.disconnect()
            assertTrue("Retained rows stay loaded while offline", f.client.state.value.shellLoaded)
            f.connect()
            assertTrue("Reconnecting restores the retained rows", f.client.state.value.shellLoaded)
            assertFalse(f.client.state.value.canWrite)

            f.client.disconnect()
            f.client.forgetEnvironment("env")
            assertFalse(f.client.state.value.shellLoaded)
            f.environmentId = "another"
            f.connect()
            assertFalse("Another environment's rows are not this one's", f.client.state.value.shellLoaded)
            f.client.disconnect(); f.environmentId = "env"
            f.connect()
            assertFalse("A forgotten environment starts over", f.client.state.value.shellLoaded)
            assertTrue(f.client.state.value.threads.isEmpty())
        }
    }

    @Test fun snapshotRequiredResubscribesForASocketSnapshot() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            f.client.disconnect()
            val resumed = f.connect()
            assertEquals(10L, resumed.afterSequence)
            resumed.first.send("""{"_tag":"Chunk","requestId":"${resumed.second}","values":[{"kind":"snapshot-required"}]}""")
            val fresh = f.nextShell()
            assertNull(fresh.afterSequence)
            assertEquals(resumed.second, withTimeout(5000) { f.interrupts.receive() })
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertFalse(f.client.state.value.canWrite)
            f.chunk(fresh, """${Fixture.shell(30, Fixture.project, Fixture.thread("b"))}, {"kind":"synchronized"}""")
            assertEquals(listOf("b"), f.client.state.value.threads.map { it.id })
            assertTrue(f.client.state.value.canWrite)
        }
    }

    /** An app restart that reads [entry] back through the cache file format and restores it into the new client. */
    private fun Fixture.restartFrom(entry: CachedShell?): CachedShell {
        val bytes = ShellCacheCodec.encode(entry ?: error("No cache entry"))!!
        restart()
        val cached = ShellCacheCodec.decode(bytes, environmentId)!!
        assertTrue(client.restoreShell(cached))
        return cached
    }

    private suspend fun Fixture.loadAB(): Triple<WebSocket, String, JsonObject> = connect().also {
        chunk(it, """${Fixture.shell(10, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")}")}, {"kind":"synchronized"}""")
        chunk(it, """{"kind":"thread.updated","sequence":12,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
    }

    private fun assertNotLive(state: ClientState) {
        assertFalse(state.shellLive); assertFalse(state.canWrite); assertFalse(state.canWriteThreads)
    }

    @Test fun cachedRowsShowAtOnceAfterARestartAndResumeFromTheirCursor() = runBlocking {
        Fixture().use { f ->
            f.loadAB()
            assertTrue(f.client.state.value.shellLive)
            f.client.disconnect()
            val entry = f.client.shellCacheEntry("env", nowMillis = 77)
            assertEquals(CachedShellCursor("1.2.3", 40, 12, entry!!.cursor!!.snapshotAtMillis), entry.cursor)
            assertEquals(77L, entry.savedAtMillis)
            val before = f.rows()

            f.restartFrom(entry)
            val resumed = f.connect()
            assertEquals(12L, resumed.afterSequence)
            assertEquals("The cached rows show while the connection resumes", before, f.rows())
            assertTrue(f.client.state.value.shellLoaded)
            assertNotLive(f.client.state.value)
            f.chunk(resumed, """{"kind":"thread.removed","sequence":13,"location":"active","threadId":"b"}""")
            assertNotLive(f.client.state.value)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            assertEquals(listOf("a" to "A1"), f.client.state.value.threads.map { it.id to it.title })
            assertTrue(f.client.state.value.shellLive)
            assertTrue(f.client.state.value.canWrite)
            assertEquals(13L, f.client.shellCacheEntry("env")?.cursor?.sequence)
        }
    }

    @Test fun cachedRowsOfAnotherServerBuildLoadAFullSnapshot() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            f.restartFrom(f.client.shellCacheEntry("env"))
            f.serverVersion = "1.2.4"
            val fresh = f.connect()
            assertNull(fresh.afterSequence)
            assertEquals(listOf("a", "b"), f.client.state.value.threads.map { it.id }.sorted())
            assertNotLive(f.client.state.value)
            f.chunk(fresh, """${Fixture.shell(3, Fixture.project, Fixture.thread("z"))}, {"kind":"synchronized"}""")
            assertEquals(listOf("z"), f.client.state.value.threads.map { it.id })
            assertTrue(f.client.state.value.canWrite)
        }
    }

    @Test fun aCachedCursorOlderThanTheResumeWindowLoadsAFullSnapshot() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            val entry = f.client.shellCacheEntry("env")!!
            val stale = entry.copy(cursor = entry.cursor!!.copy(snapshotAtMillis = System.currentTimeMillis() - SHELL_RESUME_MAX_AGE_MS - 60_000))
            f.restartFrom(stale)
            val fresh = f.connect()
            assertNull(fresh.afterSequence)
            assertTrue("Old rows still show until the snapshot", f.client.state.value.shellLoaded)
            f.chunk(fresh, """${Fixture.shell(20, Fixture.project, Fixture.thread("c"))}, {"kind":"synchronized"}""")
            assertEquals(listOf("c"), f.client.state.value.threads.map { it.id })
            f.client.disconnect()
            assertEquals(20L, f.connect().afterSequence)
        }
    }

    @Test fun cachedRowsWithoutACursorLoadAFullSnapshot() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            f.restartFrom(f.client.shellCacheEntry("env")!!.copy(cursor = null))
            val fresh = f.connect()
            assertNull(fresh.afterSequence)
            assertEquals(2, f.client.state.value.threads.size)
        }
    }

    @Test fun aCorruptOrUnknownCacheFileRestoresNothingAndLoadsAFullSnapshot() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            val bytes = ShellCacheCodec.encode(f.client.shellCacheEntry("env")!!)!!
            f.restart()
            assertNull(ShellCacheCodec.decode(bytes.copyOf(bytes.size / 2), "env"))
            assertNull(ShellCacheCodec.decode(bytes.decodeToString().replace("\"version\":${ShellCacheCodec.VERSION}", "\"version\":${ShellCacheCodec.VERSION + 1}").encodeToByteArray(), "env"))
            assertNull("Another environment's file", ShellCacheCodec.decode(bytes, "other"))
            val fresh = f.connect()
            assertNull(fresh.afterSequence)
            assertFalse(f.client.state.value.shellLoaded)
            assertTrue(f.client.state.value.threads.isEmpty())
        }
    }

    @Test fun nothingCanBeWrittenFromCachedRows() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            f.restartFrom(f.client.shellCacheEntry("env"))
            val rename = runCatching { f.client.threadAction(ThreadTarget("env", "a"), ThreadAction.Rename, "x") }
            assertTrue(rename.exceptionOrNull() is IllegalStateException)
            f.answerConfigs = false
            val connecting = async { f.client.connect(f.environment()) }
            val held = withTimeout(5000) { f.heldConfigs.receive() }
            assertNotLive(f.client.state.value)
            assertFalse(f.client.state.value.canRead)
            assertEquals(2, f.client.state.value.threads.size)
            assertTrue(runCatching { f.client.threadAction(ThreadTarget("env", "a"), ThreadAction.Rename, "x") }.isFailure)
            f.answerConfig(held)
            val resumed = f.nextShell()
            withTimeout(5000) { connecting.await() }
            assertEquals(12L, resumed.afterSequence)
            assertNotLive(f.client.state.value)
            assertTrue(runCatching { f.client.threadAction(ThreadTarget("env", "a"), ThreadAction.Settle) }.isFailure)
            assertFalse("Nothing was dispatched", "orchestration.dispatchCommand" in f.log)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            assertTrue(f.client.state.value.canWriteThreads)
        }
    }

    @Test fun restoringNeverReplacesRowsThisProcessAlreadyHolds() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            val older = f.client.shellCacheEntry("env")!!.copy(threads = emptyList(), cursor = null)
            assertFalse("Retained rows are newer", f.client.restoreShell(older))
            assertEquals(2, f.client.state.value.threads.size)
            assertEquals(12L, f.connect().afterSequence)
            assertFalse("A connection in progress wins", f.client.restoreShell(older))
        }
    }

    @Test fun deselectingKeepsTheRowsAndCursorButForgettingDropsThem() = runBlocking {
        Fixture().use { f ->
            f.loadAB()
            f.client.deselectEnvironment("env")
            assertNull(f.client.state.value.environment)
            assertTrue(f.client.state.value.threads.isEmpty())
            assertEquals(ConnectionStatus.Disconnected, f.client.state.value.connection)
            assertEquals("Deselecting keeps what the cache stores", 12L, f.client.shellCacheEntry("env")?.cursor?.sequence)
            val resumed = f.connect()
            assertEquals(12L, resumed.afterSequence)
            assertEquals(2, f.client.state.value.threads.size)
            f.client.deselectEnvironment("other")
            assertEquals("Another machine's deselect changes nothing", "env", f.client.state.value.environment?.id)
            f.client.disconnect()
            f.client.forgetEnvironment("env")
            assertNull("Forgetting leaves nothing to cache", f.client.shellCacheEntry("env"))
            assertNull(f.connect().afterSequence)
        }
    }

    @Test fun aCatchUpOrRefreshThatFailsBeforeItsMarkerKeepsTheLastCheckpoint() = runBlocking {
        Fixture().use { f ->
            val events = Channel<String>(Channel.UNLIMITED)
            f.scope.launch(start = CoroutineStart.UNDISPATCHED) { f.client.shellCheckpointed.collect { events.trySend(it) } }
            f.loadAB()
            val checkpoint = f.client.shellCacheEntry("env", nowMillis = 1)!!
            assertEquals(12L, checkpoint.cursor?.sequence)
            f.client.disconnect()
            while (events.tryReceive().isSuccess) {}

            val resumed = f.connect()
            assertEquals(12L, resumed.afterSequence)
            f.chunk(resumed, """{"kind":"thread.removed","sequence":13,"location":"active","threadId":"b"}""")
            assertEquals("The delta shows", 1, f.client.state.value.threads.size)
            resumed.first.close(1011, null)
            withTimeout(5000) { while (f.client.state.value.connection != ConnectionStatus.Error) yield() }
            assertEquals("Rows applied before the marker are not what the cache stores", checkpoint, f.client.shellCacheEntry("env", nowMillis = 1))
            assertTrue("No checkpoint without a marker", events.tryReceive().isFailure)

            f.client.reconnect()
            val again = f.nextShell()
            assertEquals("Memory still resumes from the applied delta", 13L, again.afterSequence)
            val unchanged = f.client.state.value.threads
            f.chunk(again, """{"kind":"synchronized"}""")
            assertSame(unchanged, f.client.state.value.threads)
            assertEquals("The marker checkpoints even with unchanged rows", "env", withTimeout(5000) { events.receive() })
            assertEquals(13L, f.client.shellCacheEntry("env")?.cursor?.sequence)
            assertEquals(listOf("a"), f.client.shellCacheEntry("env")?.threads?.map { it.id })

            val refresh = async { runCatching { f.client.refreshThreads() } }
            val full = f.nextShell()
            f.chunk(full, Fixture.shell(20, Fixture.project, Fixture.thread("z")))
            assertEquals(listOf("z"), f.client.state.value.threads.map { it.id })
            full.first.close(1011, null)
            withTimeout(5000) { refresh.await() }
            assertEquals("A full refresh that never synchronized replaces nothing", listOf("a"), f.client.shellCacheEntry("env")?.threads?.map { it.id })
            assertEquals(13L, f.client.shellCacheEntry("env")?.cursor?.sequence)
        }
    }

    @Test fun aThreadOpenedBeforeTheShellIsSynchronizedIsSubscribedAfterTheMarkerOnlyIfItStillExists() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            f.restartFrom(f.client.shellCacheEntry("env"))
            f.answerConfigs = false
            val connecting = async { f.client.connect(f.environment()) }
            val held = withTimeout(5000) { f.heldConfigs.receive() }
            f.client.selectThread("b")
            assertEquals("A cached row opens while connecting", "b", f.client.state.value.selectedThreadId)
            f.answerConfig(held)
            val resumed = f.nextShell()
            withTimeout(5000) { connecting.await() }
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertFalse("Nothing subscribed before the marker", "orchestration.subscribeThread" in f.log)
            f.chunk(resumed, """{"kind":"thread.removed","sequence":13,"location":"active","threadId":"b"},{"kind":"synchronized"}""")
            assertNull("A thread deleted while offline is closed", f.client.state.value.selectedThreadId)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.canWriteThreads)
            delay(100)
            assertFalse("orchestration.subscribeThread" in f.log)

            f.client.disconnect(); f.answerConfigs = true
            val again = f.connect()
            f.client.selectThread("a")
            assertEquals("a", f.client.state.value.selectedThreadId)
            delay(100)
            assertFalse("Still waiting for the marker", "orchestration.subscribeThread" in f.log)
            f.chunk(again, """{"kind":"synchronized"}""")
            withTimeout(5000) { while ("orchestration.subscribeThread" !in f.log) delay(5) }
            assertEquals("a", f.client.state.value.selectedThreadId)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }

    @Test fun aCacheReadThatFinishesWhileConnectingIsUsedOnlyUntilTheShellIsPlanned() = runBlocking {
        Fixture().use { f ->
            f.loadAB(); f.client.disconnect()
            val bytes = ShellCacheCodec.encode(f.client.shellCacheEntry("env")!!)!!
            f.restart()
            val cached = ShellCacheCodec.decode(bytes, "env")!!
            f.log.clear()
            f.ticketHold = java.util.concurrent.CountDownLatch(1)
            val connecting = async { f.client.connect(f.environment()) }
            withTimeout(5000) { while (f.log.none { it.startsWith("/api/auth/websocket-ticket") }) delay(5) }
            assertEquals(ConnectionStatus.Connecting, f.client.state.value.connection)
            assertTrue("The connect did not wait for the read, and still takes its rows", f.client.restoreShell(cached))
            assertEquals(2, f.client.state.value.threads.size)
            assertNotLive(f.client.state.value)
            f.ticketHold!!.countDown()
            val resumed = f.nextShell()
            withTimeout(5000) { connecting.await() }
            assertEquals(12L, resumed.afterSequence)
            assertFalse("A read finishing after the shell was planned is dropped", f.client.restoreShell(cached.copy(threads = emptyList(), cursor = null)))
            assertEquals(2, f.client.state.value.threads.size)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            assertTrue(f.client.state.value.shellLive)
        }
    }
}
