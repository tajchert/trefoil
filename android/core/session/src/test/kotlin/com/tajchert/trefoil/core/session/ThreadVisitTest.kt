package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

private object Actions {
  val None = ThreadVisitTracker.Action.None
  fun Send(opened: Boolean) = ThreadVisitTracker.Action.Send(opened)
  fun Wait(millis: Long) = ThreadVisitTracker.Action.Wait(millis)
}

class ThreadVisitTrackerTest {
  private val a = Actions
  @Test fun opensOnceThenThrottlesActivityVisits() {
    val t = ThreadVisitTracker(10_000); t.reset("t1")
    assertEquals(a.None, t.evaluate("t1", eligible = false, revision = 1, nowMillis = 0))
    assertEquals(a.Send(true), t.evaluate("t1", true, 1, 0))
    assertEquals("no new activity", a.None, t.evaluate("t1", true, 1, 50_000))
    assertEquals(a.Wait(7_000), t.evaluate("t1", true, 2, 3_000))
    assertEquals(a.Send(false), t.evaluate("t1", true, 2, 10_000))
    assertEquals(a.None, t.evaluate("t1", true, 2, 30_000))
  }
  @Test fun otherThreadsAndResetsAreHandled() {
    val t = ThreadVisitTracker(10_000); t.reset("t1")
    assertEquals(a.None, t.evaluate("t2", true, 1, 0))
    assertEquals(a.Send(true), t.evaluate("t1", true, 1, 0))
    t.reset("t1")
    assertEquals("a new subscription opens again", a.Send(true), t.evaluate("t1", true, 1, 1))
    t.reset(null)
    assertEquals(a.None, t.evaluate(null, true, 1, 2))
  }
  @Test fun commandUsesThreadUpdatedAtAndOmitsOpenedWhenFalse() {
    val projection = Json.parseToJsonElement("""{"thread":{"updatedAt":"2026-11-12T10:00:00.000Z"}}""").jsonObject
    val open = ThreadVisitTracker.command("t1", projection, true)!!
    assertEquals(setOf("type", "commandId", "threadId", "visitedAt", "opened"), open.keys)
    assertEquals("2026-11-12T10:00:00.000Z", open.string("visitedAt"))
    val activity = ThreadVisitTracker.command("t1", projection, false)!!
    assertEquals(setOf("type", "commandId", "threadId", "visitedAt"), activity.keys)
    assertNull("no updatedAt, no visit", ThreadVisitTracker.command("t1", JsonObject(emptyMap()), false))
    assertNull(ThreadVisitTracker.command("t1", Json.parseToJsonElement("""{"thread":{"updatedAt":"soon"}}""").jsonObject, true))
  }
}

/** `thread.visit` over the fixture socket; never real threads. */
class ThreadVisitTest {
  private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
  private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{}}"""

  private fun session(rejectVisit: Boolean = false, viewing: Boolean = true, block: suspend (T3Client, Channel<JsonObject>) -> Unit) = runBlocking {
    val server = MockWebServer(); server.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = T3Client(scope)
    val dispatched = Channel<JsonObject>(Channel.UNLIMITED)
    val projection = """{"thread":{"id":"t1","updatedAt":"2026-11-12T10:00:00.000Z"},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[],"subagents":[]}"""
    val listener = object : WebSocketListener() {
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = EffectRpc.decode(text).single(); if (frame.string("_tag") != "Request") return
        val id = frame.string("id")
        fun success(value: String) = webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""")
        when (frame.string("tag")) {
          "server.getConfig" -> success("""{"providers":[],"environment":{"capabilities":{}}}""")
          "subscribeServerConfig" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"version":1,"type":"snapshot","config":{"providers":[],"environment":{"capabilities":{}}}}]}""")
          "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
          "orchestration.subscribeThread" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":$projection},{"kind":"synchronized"}]}""")
          "orchestration.dispatchCommand" -> {
            dispatched.trySend(frame.obj("payload"))
            if (rejectVisit) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationCommandRejected"}}]}}""")
            else success("""{"sequence":9}""")
          }
        }
      }
    }
    try {
      server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}""")); server.enqueue(json("""{"ticket":"ticket"}""")); server.enqueue(MockResponse().withWebSocketUpgrade(listener))
      client.connect(SavedEnvironment("env-1", "Test", server.url("/").toString(), "secret"))
      withTimeout(5000) { client.state.first { it.canWrite } }
      client.setThreadViewing(viewing)
      client.selectThread("t1")
      withTimeout(5000) { client.state.first { it.canWrite && it.selectedThreadId == "t1" } }
      block(client, dispatched)
    } finally { client.disconnect(); scope.cancel(); runCatching { server.shutdown() } }
  }

  @Test fun openingALoadedThreadSendsOneOpenedVisit() = session { client, dispatched ->
    val visit = withTimeout(5000) { dispatched.receive() }
    assertEquals("thread.visit", visit.string("type")); assertEquals("t1", visit.string("threadId"))
    assertEquals("2026-11-12T10:00:00.000Z", visit.string("visitedAt")); assertEquals(true, visit["opened"]?.jsonPrimitive?.booleanOrNull)
    delay(300); assertTrue(dispatched.tryReceive().isFailure); assertTrue(client.state.value.canWrite)
  }

  @Test fun rejectedVisitStaysSilentAndKeepsWrites() = session(rejectVisit = true) { client, dispatched ->
    withTimeout(5000) { dispatched.receive() }
    delay(300)
    assertTrue(client.state.value.canWrite); assertNull(client.state.value.error); assertTrue(dispatched.tryReceive().isFailure)
  }

  @Test fun backgroundedAppSendsNoVisit() = session(viewing = false) { client, dispatched ->
    delay(300); assertTrue(dispatched.tryReceive().isFailure)
    client.setThreadViewing(true)
    assertEquals(true, withTimeout(5000) { dispatched.receive() }["opened"]?.jsonPrimitive?.booleanOrNull)
  }
}
