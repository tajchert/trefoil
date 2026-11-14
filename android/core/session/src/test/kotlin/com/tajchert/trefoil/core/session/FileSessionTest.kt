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
import java.io.ByteArrayOutputStream

class FileSessionTest {
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope)
        val server = MockWebServer()
        val calls = Channel<JsonObject>(Channel.UNLIMITED)
        val held = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
        var holdAsset = false
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"e","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","filesystem:read"]}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/api/assets/") -> MockResponse().setBody("apk-bytes")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") != "Request") return
                            calls.trySend(frame)
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, """{"providers":[]}""")
                                "orchestration.subscribeShell" -> chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}""")
                                "orchestration.subscribeThread" -> chunk(webSocket, id, """{"kind":"snapshot","projection":{"thread":{"id":"${frame.obj("payload").string("threadId")}"},"messages":[],"turnItems":[],"runs":[],"runtimeRequests":[]}},{"kind":"synchronized"}""")
                                "assets.createUrl" -> if (holdAsset) held.trySend(webSocket to id) else complete(webSocket, id, """{"relativeUrl":"/api/assets/signed/app.apk","expiresAt":4102444800000}""")
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        suspend fun connect() {
            client.connect(SavedEnvironment("e", "Fixture", server.url("/").toString(), "secret"))
            withTimeout(5000) { client.state.first { it.canRead } }
            client.selectThread("t")
            withTimeout(5000) { client.state.first { it.selectedThreadId == "t" && it.canRead } }
        }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }
    @Test fun readOnlyClientUsesDownloadDispositionWithoutMutatingThread() = runBlocking {
        Fixture().use { f ->
            f.connect()
            assertFalse(f.client.state.value.canWrite)
            val output = ByteArrayOutputStream()
            f.client.downloadFile(RemoteFileReference(path = "/Users/me/otherproject/app.apk", fileName = "app.apk"), output, "e", "t")
            assertEquals("apk-bytes", output.toString())
            val frames = mutableListOf<JsonObject>(); while (true) frames += f.calls.tryReceive().getOrNull() ?: break
            val resource = frames.single { it.string("tag") == "assets.createUrl" }.obj("payload").obj("resource")
            assertEquals("media-file", resource.string("_tag")); assertEquals("attachment", resource.string("disposition"))
            assertEquals("t", resource.string("threadId")); assertEquals("/Users/me/otherproject/app.apk", resource.string("path"))
            assertFalse(frames.any { it.string("tag") == "orchestration.dispatchCommand" })
        }
    }
    @Test fun threadSwitchRejectsLateSignedUrlAndDoesNotStartHttpDownload() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.holdAsset = true
            val download = async { runCatching { f.client.downloadFile(RemoteFileReference(path = "/tmp/app.apk", fileName = "app.apk"), ByteArrayOutputStream(), "e", "t") } }
            val pending = withTimeout(5000) { f.held.receive() }
            f.client.selectThread("other")
            Fixture.complete(pending.first, pending.second, """{"relativeUrl":"/api/assets/signed/app.apk","expiresAt":4102444800000}""")
            assertTrue(download.await().isFailure)
            assertEquals(4, f.server.requestCount)
        }
    }
    @Test fun inheritedImagesMintSourceResourcesThroughExistingDownloadPolicy() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val projection = EffectRpc.json.parseToJsonElement("""{"thread":{"id":"t"},"visibleTurnItems":[{"position":1,"sourceThreadId":"parent","sourceItemId":"source","item":{"id":"visible","type":"dynamic_tool","viewedImagePath":"file:///tmp/a%20b.png","output":[{"type":"image","mimeType":"image/png"}]}}]}""").jsonObject
            val images = toolActivities(projection).single().images
            assertEquals(2, images.size)
            images.forEach { f.client.downloadFile(it, ByteArrayOutputStream(), "e", "t") }
            val frames = mutableListOf<JsonObject>(); while (true) frames += f.calls.tryReceive().getOrNull() ?: break
            val resources = frames.filter { it.string("tag") == "assets.createUrl" }.map { it.obj("payload").obj("resource") }
            assertEquals("media-file", resources[0].string("_tag"))
            assertEquals("parent", resources[0].string("threadId"))
            assertEquals("/tmp/a b.png", resources[0].string("path"))
            assertEquals("tool-output-image", resources[1].string("_tag"))
            assertEquals("parent", resources[1].string("threadId"))
            assertEquals("source", resources[1].string("itemId"))
            assertEquals(0, resources[1]["index"]?.jsonPrimitive?.int)
            assertFalse("disposition" in resources[1])
        }
    }
    @Test fun toolImageThreadSwitchRejectsLateSignedUrl() = runBlocking {
        Fixture().use { f ->
            f.connect(); f.holdAsset = true
            val reference = RemoteFileReference(fileName = "image.png", toolOutputItemId = "native", toolOutputIndex = 0, sourceThreadId = "parent")
            val download = async { runCatching { f.client.downloadFile(reference, ByteArrayOutputStream(), "e", "t") } }
            val pending = withTimeout(5000) { f.held.receive() }
            f.client.selectThread("other")
            Fixture.complete(pending.first, pending.second, """{"relativeUrl":"/api/assets/signed/image.png","expiresAt":4102444800000}""")
            assertTrue(download.await().isFailure)
            assertEquals(4, f.server.requestCount)
        }
    }

    @Test fun invalidToolImageReferencesNeverMintAnAsset() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val base = RemoteFileReference(fileName = "image.png", toolOutputItemId = "native", toolOutputIndex = 0)
            val invalid = listOf(base.copy(toolOutputIndex = -1), base.copy(toolOutputIndex = 8), base.copy(toolOutputIndex = null),
                base.copy(toolOutputItemId = ""), base.copy(path = "/tmp/a.png"), base.copy(attachmentId = "a"), base.copy(sourceThreadId = ""))
            invalid.forEach { reference ->
                assertTrue(runCatching { f.client.downloadFile(reference, ByteArrayOutputStream(), "e", "t") }.isFailure)
            }
            val frames = mutableListOf<JsonObject>(); while (true) frames += f.calls.tryReceive().getOrNull() ?: break
            assertFalse(frames.any { it.string("tag") == "assets.createUrl" })
        }
    }
    @Test fun messageMappingPreservesAttachmentMetadataAndInheritedMessageItems() {
        val projection = EffectRpc.json.parseToJsonElement("""{"messages":[{"id":"m","role":"user","text":"","attachments":[{"type":"file","id":"a.apk","name":"app.apk","mimeType":"application/vnd.android.package-archive","sizeBytes":42}]}],"turnItems":[]}""").jsonObject
        val mapped = conversationMessages(projection).single().attachments.single()
        assertEquals("a.apk", mapped.attachmentId); assertEquals("app.apk", mapped.fileName); assertEquals(42L, mapped.sizeBytes)
        val inherited = EffectRpc.json.parseToJsonElement("""{"visibleTurnItems":[{"position":1,"item":{"id":"i","type":"assistant_message","messageId":"m","text":"","attachments":[{"type":"file","id":"b.zip","name":"build.zip","mimeType":"application/zip","sizeBytes":12}]}}]}""").jsonObject
        assertEquals("b.zip", conversationMessages(inherited).single().attachments.single().attachmentId)
    }

    @Test fun originatingScopeMismatchNeverSendsAssetRequest() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val reference = RemoteFileReference(path = "/fixture/private.apk", fileName = "private.apk")
            for ((environment, thread) in listOf("previous-environment" to "t", "e" to "previous-thread")) {
                assertTrue(runCatching { f.client.downloadFile(reference, ByteArrayOutputStream(), environment, thread) }.isFailure)
            }
            val frames = mutableListOf<JsonObject>(); while (true) frames += f.calls.tryReceive().getOrNull() ?: break
            assertFalse(frames.any { it.string("tag") == "assets.createUrl" })
        }
    }

    @Test fun cancelledStorageHandoffNeverSendsAssetRequest() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val attempt = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                f.client.downloadFile(RemoteFileReference(path = "/fixture/private.apk", fileName = "private.apk"), ByteArrayOutputStream(), "e", "t")
            }
            attempt.join()
            val frames = mutableListOf<JsonObject>(); while (true) frames += f.calls.tryReceive().getOrNull() ?: break
            assertFalse(frames.any { it.string("tag") == "assets.createUrl" })
        }
    }
}
