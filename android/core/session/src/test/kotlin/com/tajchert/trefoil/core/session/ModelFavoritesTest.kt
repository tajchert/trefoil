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

class ModelFavoritesTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private class Request(val ws: WebSocket, val id: String, val payload: JsonObject)

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope)
        val server = MockWebServer()
        @Volatile var permissions = """["orchestration:read","orchestration:operate","settings:write"]"""
        /** The first config snapshot's `settings`. */
        @Volatile var settings = """{"favorites":[{"provider":"codex","model":"sol"}],"providerModelPreferences":{"codex":{"hiddenModels":["luna"],"modelOrder":["sol"]}}}"""
        val configStream = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val configPayloads = Channel<JsonObject>(Channel.UNLIMITED)
        private val configStreams = java.util.concurrent.ConcurrentHashMap<String, WebSocket>()
        /** Config stream events sent in the first snapshot's chunk, each with a leading comma. */
        @Volatile var afterSnapshot: String? = null
        val updates = Channel<Request>(Channel.UNLIMITED)
        val acks = Channel<String>(Channel.UNLIMITED)
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":$permissions}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            // The first snapshot's ack hands the stream to the test, so its own acks count only its events.
                            if (frame.string("_tag") == "Ack") { val acked = frame.string("requestId"); configStreams.remove(acked)?.let { configStream.trySend(it to acked) } ?: acks.trySend(acked); return }
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, config())
                                // The first snapshot is the connection's config; the test sends later events on the stream.
                                "subscribeServerConfig" -> {
                                    configPayloads.trySend(frame.obj("payload"))
                                    configStreams[id] = webSocket
                                    chunk(webSocket, id, """{"version":1,"type":"snapshot","config":${config()}}${afterSnapshot.orEmpty()}""")
                                }
                                "server.updateSettings" -> updates.trySend(Request(webSocket, id, frame.obj("payload")))
                                "orchestration.subscribeShell" -> chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}""")
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        fun config() = """{"providers":[{"instanceId":"codex","driver":"codex","enabled":true,"models":[
            {"slug":"sol","name":"GPT-6.2-Sol","shortName":"Sol","isDefault":true,"badge":"new"},{"slug":"luna","name":"GPT-6-Luna","isLegacy":true}]}],"settings":$settings}""".replace("\n", "")
        suspend fun connect() {
            client.connect(SavedEnvironment("env", "Fixture", server.url("/").toString(), "bearer-secret"))
            withTimeout(5000) { client.state.first { it.canWriteThreads } }
        }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun reject(ws: WebSocket, id: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"ServerSettingsError"}}]}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }

    private val sol = ModelKey("codex", "sol")
    private val luna = ModelKey("codex", "luna")
    private fun favorites(f: Fixture) = f.client.state.value.modelPreferences.favorites

    @Test fun parsesCatalogMetadataAndPreferencesAndTheConfigStreamKeepsThemCurrent() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val state = f.client.state.value
            val model = state.models.first { it.model == "sol" }
            assertEquals("Sol", model.shortName); assertTrue(model.isDefault); assertEquals("new", model.badge)
            assertTrue(state.models.first { it.model == "luna" }.isLegacy)
            assertEquals(ModelPreferences(listOf(sol), mapOf("codex" to setOf("luna")), mapOf("codex" to listOf("sol")), synced = true), state.modelPreferences)
            assertTrue(state.canWriteSettings)
            val (ws, id) = withTimeout(5000) { f.configStream.receive() }
            suspend fun event(json: String) { Fixture.chunk(ws, id, json); withTimeout(5000) { while (f.acks.receive() != id) {} } }
            // A settings update with favourites replaces them and keeps the unreported model preferences.
            event("""{"version":1,"type":"settingsUpdated","payload":{"settings":{"favorites":[{"provider":"codex","model":"luna"}]}}}""")
            assertEquals(listOf(luna), favorites(f))
            assertEquals(mapOf("codex" to setOf("luna")), f.client.state.value.modelPreferences.hiddenModels)
            // An update without the key keeps them; a snapshot carries the whole settings.
            event("""{"version":1,"type":"settingsUpdated","payload":{"settings":{"enablePreviewTunnel":true}}}""")
            assertEquals(listOf(luna), favorites(f))
            event("""{"version":1,"type":"snapshot","config":{"settings":{"favorites":[],"providerModelPreferences":{}}}}""")
            assertEquals(ModelPreferences(synced = true), f.client.state.value.modelPreferences)
            f.client.disconnect()
            assertEquals(ModelPreferences(), f.client.state.value.modelPreferences)
            assertFalse(f.client.state.value.canWriteSettings)
        }
    }

    @Test fun olderServersAndSessionsWithoutTheScopeCannotChangeFavourites() = runBlocking {
        Fixture().use { f ->
            f.permissions = """["orchestration:read","orchestration:operate"]"""
            f.connect()
            assertFalse(f.client.state.value.canWriteSettings)
            assertTrue(runCatching { f.client.setModelFavorite(luna, true) }.isFailure)
            assertEquals(listOf(sol), favorites(f))
            f.client.disconnect()
            f.permissions = """["orchestration:read","orchestration:operate","settings:write"]"""
            f.settings = """{"defaultRuntimeMode":"auto"}"""
            f.connect()
            assertTrue(f.client.state.value.canWriteSettings)
            assertEquals(ModelPreferences(), f.client.state.value.modelPreferences)
            assertTrue(runCatching { f.client.setModelFavorite(luna, true) }.isFailure)
            assertTrue(f.updates.tryReceive().isFailure)
        }
    }

    @Test fun showsAChangeAtOnceConfirmsItsKeyAndRevertsOnlyAFailedChange() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val star = async { f.client.setModelFavorite(luna, true) }
            val request = withTimeout(5000) { f.updates.receive() }
            assertEquals(Json.parseToJsonElement("""{"patch":{"setModelFavorites":[{"provider":"codex","model":"luna","favorite":true}]}}"""), request.payload)
            assertEquals(listOf(sol, luna), favorites(f))
            // An unstar while the star is in flight shows on top of it; its rejection reverts only itself.
            val unstar = async { runCatching { f.client.setModelFavorite(sol, false) } }
            val second = withTimeout(5000) { f.updates.receive() }
            assertEquals(listOf(luna), favorites(f))
            Fixture.reject(second.ws, second.id)
            assertTrue(unstar.await().isFailure)
            assertEquals(listOf(sol, luna), favorites(f))
            assertTrue(f.client.state.value.canWriteSettings)
            // The reply confirms its own key only: its whole-settings snapshot may be older than the config stream.
            Fixture.complete(request.ws, request.id, """{"favorites":[{"provider":"codex","model":"luna"}],"enablePreviewTunnel":true}""")
            star.await()
            assertEquals(listOf(sol, luna), favorites(f))
            // A disconnect before the reply drops the change; nothing is resent on the next connection.
            val lost = async { runCatching { f.client.setModelFavorite(sol, false) } }
            withTimeout(5000) { f.updates.receive() }
            assertEquals(listOf(luna), favorites(f))
            f.client.disconnect()
            assertTrue(lost.await().isFailure)
            f.connect()
            assertEquals(listOf(sol), favorites(f))
            delay(100); assertTrue(f.updates.tryReceive().isFailure)
        }
    }

    @Test fun theFirstConfigIsTheSlimStreamSnapshotAndNotAFullGetConfig() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val payload = withTimeout(5000) { f.configPayloads.receive() }
            assertEquals(JsonObject(listOf("workspaceSnapshotsOnDemand", "providerCatalogsOnDemand", "providerCatalogDigests", "providerStatusDeltas").associateWith { JsonPrimitive(true) }), payload)
            assertFalse("omitProviders is never sent", "omitProviders" in payload)
            assertEquals(listOf("sol", "luna"), f.client.state.value.models.map { it.model })
        }
    }

    @Test fun eventsRightAfterTheFirstSnapshotApplyOnTopOfItOnceConnected() = runBlocking {
        Fixture().use { f ->
            f.afterSnapshot = """,{"version":1,"type":"settingsUpdated","payload":{"settings":{"favorites":[{"provider":"codex","model":"luna"}]}}}"""
            f.connect()
            withTimeout(5000) { f.client.state.first { it.modelPreferences.favorites == listOf(luna) } }
            assertEquals(mapOf("codex" to setOf("luna")), f.client.state.value.modelPreferences.hiddenModels)
        }
    }

    @Test fun providerStatusesAndDeltasKeepModelsAndUsageLimitsCurrent() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val (ws, id) = withTimeout(5000) { f.configStream.receive() }
            suspend fun event(json: String) { Fixture.chunk(ws, id, json); withTimeout(5000) { while (f.acks.receive() != id) {} } }
            // A delta without `models` keeps the held ones; the provider's other fields are new.
            event("""{"version":1,"type":"providerStatusDeltas","payload":{"providers":[{"instanceId":"codex","driver":"codex","enabled":true,"displayName":"Codex Pro"}]}}""")
            assertEquals(listOf("sol", "luna"), f.client.state.value.models.map { it.model })
            assertEquals("Codex Pro", f.client.state.value.providers.single().displayName)
            // A delta with `models` replaces them; a provider never held without them is dropped.
            event("""{"version":1,"type":"providerStatusDeltas","payload":{"providers":[{"instanceId":"codex","driver":"codex","enabled":true,"models":[{"slug":"sol","name":"GPT-6.2-Sol (new)"}]},{"instanceId":"claude","driver":"claudeAgent","enabled":true}]}}""")
            assertEquals(listOf("GPT-6.2-Sol (new)"), f.client.state.value.models.map { it.label })
            assertEquals(listOf("codex"), f.client.state.value.providers.map { it.instanceId })
            // An older server's full list replaces everything, and is the base of the next delta.
            event("""{"version":1,"type":"providerStatuses","payload":{"providers":[{"instanceId":"claude","driver":"claudeAgent","enabled":true,"models":[{"slug":"opus","name":"Opus"}]}]}}""")
            event("""{"version":1,"type":"providerStatusDeltas","payload":{"providers":[{"instanceId":"claude","driver":"claudeAgent","enabled":false}]}}""")
            assertTrue("A disabled provider offers no models", f.client.state.value.models.isEmpty())
            assertEquals(listOf(false), f.client.state.value.providers.map { it.enabled })
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }
}
