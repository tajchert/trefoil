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
import java.util.concurrent.TimeUnit

class T3ClientTest {
 private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{}}"""
 private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
 @Test fun exchangesOnlyBootstrapAndRejectsRedirect() = runBlocking {
   val server = MockWebServer(); server.start()
   val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = T3Client(scope)
   try {
     server.enqueue(json(descriptor)); server.enqueue(json("""{"access_token":"bearer-secret","token_type":"Bearer"}"""))
     val saved = client.pair(server.url("/pair#token=bootstrap-secret").toString(), "Phone")
     assertEquals("bearer-secret", saved.accessToken); assertFalse(saved.baseUrl.contains("secret"))
     assertEquals("/.well-known/t3/environment", server.takeRequest().path)
     val exchange = server.takeRequest(); assertEquals("/oauth/token", exchange.path)
     assertTrue(exchange.body.readUtf8().contains("subject_token=bootstrap-secret"))
     server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/"))
     try { client.pair(server.url("/pair#token=secret").toString(), "Phone"); fail("Redirect must fail") } catch (_: IllegalStateException) { }
     assertEquals(3, server.requestCount)
   } finally { client.disconnect(); scope.cancel(); runCatching { server.shutdown() } }
 }
 @Test fun rejectsRemoteHttpAndIncompatibleProtocolBeforeTokenExchange() = runBlocking {
   val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = T3Client(scope)
   val server = MockWebServer(); server.start()
   try {
     try { client.pair("http://remote.example/pair#token=secret", "Phone"); fail("TLS required") } catch (_: IllegalArgumentException) { }
     server.enqueue(json("""{"environmentId":"env","orchestrationProtocolVersion":1}"""))
     try { client.pair(server.url("/pair#token=secret").toString(), "Phone"); fail("Protocol must fail") } catch (_: IllegalArgumentException) { }
     assertEquals(1, server.requestCount)
   } finally { client.disconnect(); scope.cancel(); server.shutdown() }
 }
 @Test fun newerEventKeepsWritesDisabledUntilItsRefreshCompletes() = runBlocking {
   val server = MockWebServer(); server.start()
   val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = T3Client(scope)
   val websocket = CompletableDeferred<WebSocket>(); val threadStream = CompletableDeferred<String>()
   val reads = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
   val projection = """{"thread":{"id":"t"},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[]}"""
   val listener = object : WebSocketListener() {
     override fun onOpen(webSocket: WebSocket, response: Response) { websocket.complete(webSocket) }
     override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
     override fun onMessage(webSocket: WebSocket, text: String) {
       val frame = EffectRpc.decode(text).single(); if (frame.string("_tag") != "Request") return
       val id = frame.string("id")
       when (frame.string("tag")) {
         "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"providers":[]}}}""")
         "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
         "orchestration.subscribeThread" -> { threadStream.complete(id); webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":$projection},{"kind":"event","sequence":0,"event":{"type":"future.additive"}},{"kind":"synchronized"}]}""") }
         "orchestration.getThreadProjection" -> reads.trySend(id)
       }
     }
   }
   try {
     server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}""")); server.enqueue(json("""{"ticket":"ticket"}""")); server.enqueue(MockResponse().withWebSocketUpgrade(listener))
     client.connect(SavedEnvironment("env-1", "Test", server.url("/").toString(), "secret"))
     withTimeout(5000) { client.state.first { it.canWrite } }; client.selectThread("t")
     val socket = websocket.await(); val stream = threadStream.await()
     val replayRead = withTimeout(5000) { reads.receive() }; assertFalse(client.state.value.canWrite)
     socket.send("""{"_tag":"Exit","requestId":"$replayRead","exit":{"_tag":"Success","value":$projection}}""")
     withTimeout(5000) { client.state.first { it.canWrite } }
     fun event(sequence: Int) { socket.send("""{"_tag":"Chunk","requestId":"$stream","values":[{"kind":"event","sequence":$sequence,"event":{"type":"future.additive"}}]}""") }
     fun complete(id: String) { socket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$projection}}""") }
     event(1); val first = withTimeout(5000) { reads.receive() }; assertFalse(client.state.value.canWrite)
     event(2)
     // The next ping is processed after event 2 on the ordered WebSocket input.
     socket.send("""{"_tag":"Pong"}""")
     delay(100)
     complete(first)
     val second = withTimeout(5000) { reads.receive() }; assertFalse(client.state.value.canWrite)
     complete(second); withTimeout(5000) { client.state.first { it.canWrite } }; Unit
   } finally { client.disconnect(); scope.cancel(); server.shutdown() }
 }
 @Test fun streamsFullProjectionAcknowledgesAndDoesNotReplayMutation() = runBlocking {
   val server = MockWebServer(); server.start()
   val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = T3Client(scope)
   val mutations = mutableListOf<JsonObject>(); val acks = mutableListOf<String>()
   var ws: WebSocket? = null
   val listener = object : WebSocketListener() {
     override fun onOpen(webSocket: WebSocket, response: Response) { ws = webSocket }
     override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
     override fun onMessage(webSocket: WebSocket, text: String) {
       val request = EffectRpc.decode(text).single()
       if (request.string("_tag") == "Ack") { synchronized(acks) { acks.add(request.string("requestId")) }; return }
       if (request.string("_tag") != "Request") return
       val id = request.string("id")
       fun chunk(value: String) { webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
       when (request.string("tag")) {
         "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"providers":[{"instanceId":"codex","enabled":true,"models":[{"slug":"test","name":"Test"}]}]}}}""")
         "orchestration.subscribeShell" -> chunk("""{"kind":"snapshot","snapshot":{"snapshotSequence":1,"projects":[{"id":"p","title":"Project","workspaceRoot":"/tmp/p"}],"threads":[{"id":"t","projectId":"p","title":"Thread","status":"idle"}],"archivedThreads":[]}}, {"kind":"synchronized"}""")
         "orchestration.subscribeThread" -> chunk("""{"kind":"snapshot","snapshotSequence":2,"projection":{"thread":{"id":"t"},"runs":[{"id":"run","status":"waiting"}],"messages":[{"id":"m","role":"assistant","text":"Hello","streaming":true}],"turnItems":[{"id":"item","type":"user_input_request","requestId":"optional","questions":[{"id":"q","question":"Optional?","options":[],"required":false}]}],"runtimeRequests":[{"id":"optional","kind":"user_input","status":"pending","responseCapability":{"type":"live"}},{"id":"unsupported","kind":"auth_refresh","status":"pending","responseCapability":{"type":"not_resumable"}}]}}, {"kind":"synchronized"}""")
         "orchestration.dispatchCommand" -> { synchronized(mutations) { mutations.add(request.obj("payload")) }
           if (request.obj("payload").string("type") == "runtime-request.respond") webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"sequence":3}}}""")
           else webSocket.close(1000, "disconnect before receipt") }
       }
     }
   }
   fun enqueueConnect() {
     server.enqueue(json(descriptor))
     server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}"""))
     server.enqueue(json("""{"ticket":"ticket-secret"}""")); server.enqueue(MockResponse().withWebSocketUpgrade(listener))
   }
   try {
     enqueueConnect()
     val env = SavedEnvironment("env-1", "Test", server.url("/").toString(), "bearer-secret")
     client.connect(env)
     withTimeout(5_000) { client.state.first { it.canWrite } }
     client.selectThread("t")
     val ready = withTimeout(5_000) { client.state.first { it.canWrite && it.messages.isNotEmpty() } }
     assertEquals("Hello", ready.messages.single().text); assertEquals("run", ready.activeRunId)
     assertFalse(ready.requests.first { it.id == "optional" }.questions.single().required)
     assertTrue(ready.requests.first { it.id == "optional" }.canApprove)
     assertFalse(ready.requests.first { it.id == "unsupported" }.canApprove)
     try { client.respond("unsupported", "accept"); fail("Unsupported request must fail") } catch (_: IllegalStateException) { }
     client.respond("optional", "", emptyMap())
     assertEquals("", mutations.single().obj("answers").string("q"))
     try { client.sendMessage("next"); fail("Uncertain mutation must fail explicitly") } catch (_: IllegalStateException) { }
     assertFalse(client.state.value.canWrite)
     assertEquals("queue_after_active", mutations.last().obj("dispatchMode").string("type"))
     assertTrue(mutations.last().string("commandId").isNotBlank())
     enqueueConnect(); client.reconnect(); withTimeout(5_000) { client.state.first { it.canWrite } }
     assertEquals(2, mutations.size)
     assertTrue(synchronized(acks) { acks.size >= 2 })
     val requests = (1..8).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS) }
     assertTrue(requests.any { it.path?.startsWith("/ws?wsTicket=ticket-secret&orchestrationProtocol=2") == true })
     assertTrue(requests.filter { it.path?.contains("websocket-ticket") == true }.all { it.getHeader("Authorization") == "Bearer bearer-secret" })
   } finally { client.disconnect(); scope.cancel(); runCatching { server.shutdown() } }
 }
}
