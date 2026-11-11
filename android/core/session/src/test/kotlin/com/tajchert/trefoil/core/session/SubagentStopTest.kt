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

/** T3 `interruptThreadTurn({threadId, subagentId})` over the fixture socket; never real threads. */
class SubagentStopTest {
  private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{}}"""
  private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
  private val subagents = """[
    {"id":"native","threadId":"parent","runId":"run-1","origin":"provider_native","driver":"claudeAgent","childThreadId":"child-native",
     "nativeTaskRef":{"driver":"claudeAgent","nativeId":"toolu_1","strength":"strong"},"prompt":"p","title":"Explore","model":null,"status":"running","result":null,"updatedAt":"2026-11-12T10:00:00.000Z"},
    {"id":"task","threadId":"parent","runId":"run-1","origin":"app_owned","driver":"codex","childThreadId":"child",
     "nativeTaskRef":null,"prompt":"p","title":"Tests","model":null,"status":"running","result":null,"updatedAt":"2026-11-12T10:00:00.000Z"},
    {"id":"done","threadId":"parent","runId":"run-1","origin":"app_owned","driver":"codex","childThreadId":"child-done",
     "nativeTaskRef":null,"prompt":"p","title":"Done","model":null,"status":"completed","result":"ok","updatedAt":"2026-11-12T10:00:00.000Z"}]"""
  private val childProjection = """{"thread":{"id":"child"},"runs":[{"id":"child-run-1","status":"completed"},{"id":"child-run-2","status":"running"},{"id":"child-run-3","status":"cancelled"}]}"""

  private class Harness(val client: T3Client, val scope: CoroutineScope, val socket: WebSocket, val threadStream: String,
    val dispatched: Channel<JsonObject>, val reads: Channel<String>) {
    fun sent(): List<JsonObject> = generateSequence { dispatched.tryReceive().getOrNull() }.toList()
  }

  /** [threadStop] null omits the capability; [reject] answers those command types with a typed failure; [drop] closes the socket instead. */
  private fun session(threadStop: Boolean?, reject: Set<String> = emptySet(), drop: Set<String> = emptySet(), block: suspend Harness.() -> Unit) = runBlocking {
    val server = MockWebServer(); server.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = T3Client(scope)
    val websocket = CompletableDeferred<WebSocket>(); val threadStream = CompletableDeferred<String>()
    val dispatched = Channel<JsonObject>(Channel.UNLIMITED); val reads = Channel<String>(Channel.UNLIMITED)
    val projection = """{"thread":{"id":"parent"},"messages":[],"runs":[{"id":"run-1","status":"running"}],"turnItems":[],"runtimeRequests":[],"subagents":$subagents}"""
    val capabilities = threadStop?.let { """{"threadStop":$it}""" } ?: "{}"
    val listener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) { websocket.complete(webSocket) }
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = EffectRpc.decode(text).single(); if (frame.string("_tag") != "Request") return
        val id = frame.string("id")
        fun success(value: String) = webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""")
        when (frame.string("tag")) {
          "server.getConfig" -> success("""{"providers":[],"environment":{"capabilities":$capabilities}}""")
          "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
          "orchestration.subscribeThread" -> { threadStream.complete(id); webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":$projection},{"kind":"synchronized"}]}""") }
          // Parent refreshes stay unanswered so a stale projection stays stale.
          "orchestration.getThreadProjection" -> { val threadId = frame.obj("payload").string("threadId"); reads.trySend(threadId); if (threadId == "child") success(childProjection) }
          "orchestration.dispatchCommand" -> {
            val payload = frame.obj("payload"); dispatched.trySend(payload)
            when (payload.string("type")) {
              in drop -> webSocket.close(1000, "fixture drop before receipt")
              in reject -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationCommandRejected"}}]}}""")
              else -> success("""{"sequence":9}""")
            }
          }
        }
      }
    }
    try {
      server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}""")); server.enqueue(json("""{"ticket":"ticket"}""")); server.enqueue(MockResponse().withWebSocketUpgrade(listener))
      client.connect(SavedEnvironment("env-1", "Test", server.url("/").toString(), "secret"))
      withTimeout(5000) { client.state.first { it.canWrite } }; client.selectThread("parent")
      withTimeout(5000) { client.state.first { it.canWrite && it.selectedThreadId == "parent" && it.subagents.size == 3 } }
      assertEquals(threadStop == true, client.state.value.threadStopSupported)
      Harness(client, scope, websocket.await(), threadStream.await(), dispatched, reads).block()
    } finally { client.disconnect(); scope.cancel(); runCatching { server.shutdown() } }
  }

  private fun assertSubagentStop(payload: JsonObject, subagentId: String) {
    assertEquals("subagent.stop", payload.string("type")); assertEquals("parent", payload.string("threadId"))
    assertEquals("run-1", payload.string("runId")); assertEquals(subagentId, payload.string("subagentId"))
    assertTrue(payload.string("commandId").isNotBlank())
    assertEquals(setOf("type", "commandId", "threadId", "runId", "subagentId"), payload.keys)
  }

  @Test fun providerNativeSendsOneSubagentStopOnTheParent() = session(threadStop = true) {
    client.stopSubagent("native")
    delay(100)
    val sent = sent(); assertEquals(1, sent.size); assertSubagentStop(sent.single(), "native")
    assertTrue(client.state.value.canWrite)
  }

  @Test fun appOwnedWithThreadStopStopsTheTaskThenTheChildThread() = session(threadStop = true) {
    client.stopSubagent("task")
    delay(100)
    val sent = sent(); assertEquals(listOf("subagent.stop", "thread.stop"), sent.map { it.string("type") })
    assertSubagentStop(sent[0], "task")
    assertEquals("child", sent[1].string("threadId")); assertEquals(setOf("type", "commandId", "threadId"), sent[1].keys)
    assertNotEquals(sent[0].string("commandId"), sent[1].string("commandId"))
    assertTrue("thread.stop needs no child read", reads.tryReceive().isFailure)
  }

  @Test fun appOwnedWithoutThreadStopInterruptsTheChildsActiveRunHoldingItsQueue() = session(threadStop = null) {
    client.stopSubagent("task")
    delay(100)
    val sent = sent(); assertEquals(listOf("subagent.stop", "run.interrupt"), sent.map { it.string("type") })
    assertSubagentStop(sent[0], "task")
    assertEquals("child", reads.tryReceive().getOrNull())
    val interrupt = sent[1]
    assertEquals("child", interrupt.string("threadId")); assertEquals("child-run-2", interrupt.string("runId"))
    assertEquals(true, interrupt["holdQueue"]?.jsonPrimitive?.booleanOrNull)
  }

  @Test fun rejectedSubagentStopStillStopsTheChild() = session(threadStop = true, reject = setOf("subagent.stop")) {
    client.stopSubagent("task")
    delay(100)
    assertEquals(listOf("subagent.stop", "thread.stop"), sent().map { it.string("type") })
    assertTrue("a definite rejection leaves the session usable", client.state.value.canWrite)
  }

  @Test fun rejectedProviderNativeStopFailsWithoutFailingTheSession() = session(threadStop = true, reject = setOf("subagent.stop")) {
    assertTrue(runCatching { client.stopSubagent("native") }.exceptionOrNull() is IllegalStateException)
    delay(100)
    assertEquals(listOf("subagent.stop"), sent().map { it.string("type") })
    assertTrue(client.state.value.canWrite)
  }

  @Test fun uncertainSubagentStopFailsTheSessionAndSendsNothingMore() = session(threadStop = true, drop = setOf("subagent.stop")) {
    assertTrue(runCatching { client.stopSubagent("task") }.exceptionOrNull() is UncertainOutcomeException)
    delay(100)
    assertEquals(listOf("subagent.stop"), sent().map { it.string("type") })
    assertFalse(client.state.value.canWrite)
  }

  @Test fun notStoppableOrUnknownSubagentSendsNothing() = session(threadStop = true) {
    assertTrue(runCatching { client.stopSubagent("done") }.isFailure)
    assertTrue(runCatching { client.stopSubagent("missing") }.isFailure)
    delay(100)
    assertTrue(sent().isEmpty())
    assertTrue(client.state.value.canWrite)
  }

  @Test fun staleProjectionSendsNothing() = session(threadStop = true) {
    socket.send("""{"_tag":"Chunk","requestId":"$threadStream","values":[{"kind":"event","sequence":1,"event":{"type":"subagent.updated"}}]}""")
    assertEquals("parent", withTimeout(5000) { reads.receive() })
    assertFalse(client.state.value.canWrite)
    assertTrue(runCatching { client.stopSubagent("native") }.isFailure)
    assertTrue(sent().isEmpty())
  }

  @Test fun disconnectedSendsNothing() = session(threadStop = true) {
    client.disconnect()
    assertTrue(runCatching { client.stopSubagent("task") }.isFailure)
    delay(100)
    assertTrue(sent().isEmpty())
  }
}
