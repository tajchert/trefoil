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

class ScreenSessionTest {
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope)
        val server = MockWebServer()
        val calls = Channel<JsonObject>(Channel.UNLIMITED)
        val mediaRequests = Channel<RecordedRequest>(Channel.UNLIMITED)
        val mediaCount = AtomicInteger()
        val heldLists = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val previewEvents = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val deviceEvents = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val threadEvents = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        val acks = Channel<String>(Channel.UNLIMITED)
        @Volatile var rejectDeviceList = false
        @Volatile var holdList = false
        @Volatile var holdMedia = false
        val releaseMedia = java.util.concurrent.CountDownLatch(1)
        @Volatile var scopes = "\"orchestration:read\",\"orchestration:operate\",\"preview:operate\""
        @Volatile var environmentId = "env"
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"$environmentId","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":[$scopes]}""")
                    request.path == "/api/auth/websocket-ticket/media" -> { mediaRequests.trySend(request); if (holdMedia) releaseMedia.await(5, java.util.concurrent.TimeUnit.SECONDS); json("""{"ticket":"media-${mediaCount.incrementAndGet()}"}""") }
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") == "Ack") { acks.trySend(frame.string("requestId")); return }
                            if (frame.string("_tag") != "Request") return
                            calls.trySend(frame)
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, """{"providers":[],"environment":{"capabilities":{"serverBrowser":true}}}""")
                                "orchestration.subscribeShell" -> chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}""")
                                "orchestration.subscribeThread" -> { threadEvents.trySend(webSocket to id); chunk(webSocket, id, """{"kind":"snapshot","projection":{"thread":{"id":"t"},"messages":[],"turnItems":[],"runs":[],"runtimeRequests":[]}},{"kind":"synchronized"}""") }
                                "preview.list" -> if (holdList) { heldLists.trySend(webSocket to id) } else complete(webSocket, id, browsers)
                                "device.list" -> if (rejectDeviceList) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":{"_tag":"Fail","error":{"_tag":"DeviceError","reason":"fixture"}}}}""") else complete(webSocket, id, devices)
                                "subscribePreviewEvents" -> previewEvents.trySend(webSocket to id)
                                "subscribeDeviceState" -> deviceEvents.trySend(webSocket to id)
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        suspend fun connect() {
            client.connect(SavedEnvironment(environmentId, "Fixture", server.url("/").toString(), "bearer-secret"))
            withTimeout(5000) { client.state.first { it.canWrite || (scopes == "\"orchestration:read\"" && it.connection == ConnectionStatus.Connected) } }
            client.selectThread("t")
            if (scopes.contains("orchestration:operate")) withTimeout(5000) { client.state.first { it.canWrite } }
        }
        override fun close() { releaseMedia.countDown(); client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
            val browsers = """{"serverEpoch":"epoch","revision":1,"sessions":[{"threadId":"t","tabId":"tab","runtime":"server","navStatus":{"_tag":"Success","url":"https://fixture.test","title":"Fixture"},"canGoBack":false,"canGoForward":false,"updatedAt":"now"}]}"""
            val devices = """{"hostStatus":"ready","hostStatuses":{"remote":{"status":"ready"}},"hosts":[{"id":"remote","kind":"ssh","label":"Mac","platforms":[],"hubInstalled":true,"agentDeviceInstalled":true}],"devices":[{"hostId":"remote","id":"emulator-5554","platform":"android","name":"Pixel","version":"16","booted":true,"physical":false}],"sessions":[{"threadId":"t","hostId":"remote","deviceId":"emulator-5554","platform":"android","openedAt":"now"}],"onboardingCompleted":true,"agentAccessEnabled":true,"hubBasePath":"/api/device-hub","revision":3}"""
        }
    }
    @Test fun discoversExistingSessionsAndMintsSingleViewerMediaAccess() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.client.refreshScreens()
            val catalog = f.client.state.value.screens
            assertTrue(catalog.loaded); assertFalse(catalog.loading)
            assertEquals("tab", catalog.browsers.single().tabId)
            assertEquals("remote", catalog.devices.single().hostId)
            val first = f.client.screenAccess(catalog.devices.single())
            val second = f.client.screenAccess(catalog.devices.single())
            assertEquals("media-1", first.query["wsTicket"])
            assertEquals("media-2", second.query["wsTicket"])
            assertEquals("remote", second.query["hostId"])
            assertTrue(first.interactive); assertTrue(f.client.isScreenAccessCurrent(second))
            assertEquals("Bearer bearer-secret", f.mediaRequests.receive().getHeader("Authorization"))
            assertEquals("POST", f.mediaRequests.receive().method)
            val recorded = mutableListOf<JsonObject>(); while (true) recorded += f.calls.tryReceive().getOrNull() ?: break
            assertTrue(recorded.any { it.string("tag") == "device.list" && it.obj("payload")["inspectOnly"] == JsonPrimitive(true) })
            assertFalse(recorded.any { it.string("tag") in setOf("device.open", "device.configure", "preview.open") })
            f.client.disconnect(); assertFalse(f.client.isScreenAccessCurrent(second))
            try { f.client.screenAccess(catalog.devices.single()); fail("Disconnected viewers must not mint tickets") } catch (_: IllegalStateException) { }
            assertEquals(2, f.mediaCount.get())
        }
    }
    @Test fun readOnlyPermissionsDisableAllInputAndBrowserScopeIsIndependent() = runBlocking {
        Fixture().use { f ->
            f.scopes = "\"orchestration:read\""; f.connect(); f.client.refreshScreens()
            val device = f.client.screenAccess(f.client.state.value.screens.devices.single())
            val browser = f.client.screenAccess(f.client.state.value.screens.browsers.single())
            assertFalse(device.interactive); assertFalse(browser.interactive)
            assertFalse((browser.target as ScreenTarget.Browser).interactive)
        }
        Fixture().use { f ->
            f.scopes = "\"orchestration:read\",\"orchestration:operate\""; f.connect(); f.client.refreshScreens()
            assertFalse(f.client.screenAccess(f.client.state.value.screens.browsers.single()).interactive)
            assertTrue(f.client.screenAccess(f.client.state.value.screens.devices.single()).interactive)
        }
    }
    @Test fun liveEventsCannotBeRolledBackByAnOlderListAndClosedTargetsInvalidateAccess() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.client.refreshScreens()
            val access = f.client.screenAccess(f.client.state.value.screens.browsers.single())
            val preview = withTimeout(5000) { f.previewEvents.receive() }
            f.holdList = true
            val refresh = async { f.client.refreshScreens() }
            val held = withTimeout(5000) { f.heldLists.receive() }
            Fixture.chunk(preview.first, preview.second, """{"type":"closed","threadId":"t","tabId":"tab","serverEpoch":"epoch","revision":2,"createdAt":"now"}""")
            withTimeout(5000) { while (f.acks.receive() != preview.second) {} }
            assertFalse(f.client.isScreenAccessCurrent(access))
            Fixture.complete(held.first, held.second, Fixture.browsers)
            refresh.await()
            assertTrue(f.client.state.value.screens.browsers.isEmpty())
            assertFalse(f.client.isScreenAccessCurrent(access))
        }
    }
    @Test fun optionalCatalogFailuresDoNotDisconnectChatOrDiscardOtherScreens() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.rejectDeviceList = true; f.client.refreshScreens()
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.canWrite)
            assertEquals(1, f.client.state.value.screens.browsers.size)
            assertNotNull(f.client.state.value.screens.error)
            assertTrue(f.client.screenAccess(f.client.state.value.screens.browsers.single()).interactive)
            val device = withTimeout(5000) { f.deviceEvents.receive() }
            device.first.send("""{"_tag":"Exit","requestId":"${device.second}","exit":{"_tag":"Failure","cause":{"_tag":"Fail","error":{"_tag":"DeviceError"}}}}""")
            withTimeout(5000) { f.client.state.first { it.screens.error?.contains("updates ended") == true } }
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertTrue(f.client.state.value.canWrite)
        }
    }
    @Test fun ordinaryChatRefreshDoesNotInvalidateAnActiveScreen() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.client.refreshScreens()
            val access = f.client.screenAccess(f.client.state.value.screens.devices.single())
            val thread = withTimeout(5000) { f.threadEvents.receive() }
            Fixture.chunk(thread.first, thread.second, """{"kind":"event","sequence":1,"event":{"type":"message.updated"}}""")
            withTimeout(5000) { f.client.state.first { !it.canWrite } }
            assertTrue(f.client.isScreenAccessCurrent(access))
            f.client.selectThread("other")
            assertFalse(f.client.isScreenAccessCurrent(access))
            assertTrue(f.client.state.value.screens.targets.isEmpty())
        }
    }
    @Test fun returningToTheSameThreadCannotReactivateAnEarlierViewer() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.client.refreshScreens()
            val access = f.client.screenAccess(f.client.state.value.screens.devices.single())
            f.client.selectThread("other"); f.client.selectThread("t"); f.client.refreshScreens()
            assertFalse(f.client.isScreenAccessCurrent(access))
            assertTrue(f.client.isScreenAccessCurrent(f.client.screenAccess(f.client.state.value.screens.devices.single())))
        }
    }
    @Test fun mediaTicketFromPreviousEnvironmentCannotEscapeAfterSwitch() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.client.refreshScreens(); f.holdMedia = true
            val request = async { runCatching { f.client.screenAccess(f.client.state.value.screens.devices.single()) } }
            withTimeout(5000) { f.mediaRequests.receive() }
            f.environmentId = "other"; f.connect(); f.releaseMedia.countDown()
            assertTrue(request.await().isFailure)
            assertEquals("other", f.client.state.value.environment?.id)
            assertTrue(f.client.state.value.screens.targets.isEmpty())
            assertNull(f.client.state.value.screens.error)
        }
    }
    @Test fun lateListCannotPopulateAnotherEnvironmentCatalog() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.holdList = true
            val refresh = async { runCatching { f.client.refreshScreens() } }
            val held = withTimeout(5000) { f.heldLists.receive() }
            f.environmentId = "other"; f.connect()
            Fixture.complete(held.first, held.second, Fixture.browsers)
            refresh.await()
            assertEquals("other", f.client.state.value.environment?.id)
            assertTrue(f.client.state.value.screens.targets.isEmpty())
            assertNull(f.client.state.value.screens.error)
        }
    }
}
