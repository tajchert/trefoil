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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Fixture T3 server; it validates `message.dispatch` provenance like the pinned T3 decoder. */
class NewThreadFlowTest {
  private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{}}"""
  private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
  private val config = """{"settings":{"defaultRuntimeMode":"auto-accept-edits"},"providers":[{"instanceId":"codex","driver":"codex","enabled":true,"supportedRuntimeModes":["approval-required","full-access","future-mode"],"models":[{"slug":"gpt-5","name":"GPT-5"},{"slug":"gpt-5-mini","name":"Mini"}]},{"instanceId":"claude","driver":"claudeAgent","enabled":true,"models":[{"slug":"sonnet","name":"Sonnet"}]}]}"""
  private val existingThread = """{"thread":{"id":"t","runtimeMode":"full-access","modelSelection":{"instanceId":"codex","model":"gpt-5-mini","options":{"reasoningEffort":"high"}},"futureField":1},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[]}"""

  /** Effect RPC decode failure as T3 v0.1.x sends it for a payload missing a required key. */
  private fun dieExit(id: String, key: String) =
    """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Missing key\n  at [\"$key\"]"}]}}"""
  private fun typedFailure(id: String) =
    """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationDispatchCommandError","message":"Command rejected"}}]}}"""
  private fun success(id: String, value: String) = """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}"""

  private class Fixture(val client: T3Client, val requests: MutableList<JsonObject>, private val enqueueConnect: () -> Unit) {
    fun payloads(tag: String) = synchronized(requests) { requests.filter { it.string("tag") == tag }.map { it.obj("payload") } }
    suspend fun reconnect() { enqueueConnect(); client.reconnect(); withTimeout(5_000) { client.state.first { it.canWrite } } }
  }

  /** [custom] returns true when it handled the request itself. */
  private fun withServer(
    timeoutMillis: Long = 30_000,
    projections: Map<String, String> = emptyMap(),
    custom: (WebSocket, String, String, JsonObject) -> Boolean = { _, _, _, _ -> false },
    block: suspend CoroutineScope.(Fixture) -> Unit,
  ) = runBlocking {
    val server = MockWebServer(); server.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = T3Client(scope, requestTimeoutMillis = timeoutMillis)
    val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    val projections = ConcurrentHashMap(mapOf("t" to existingThread) + projections)
    val launched = ConcurrentHashMap<String, String>()
    val shellThreads = { projections.keys.joinToString(",") { """{"id":"$it","projectId":"p","title":"Thread","status":"idle"}""" } }
    val listener = object : WebSocketListener() {
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = EffectRpc.decode(text).single(); if (frame.string("_tag") != "Request") return
        requests.add(frame)
        val id = frame.string("id"); val tag = frame.string("tag"); val payload = frame.obj("payload")
        if (custom(webSocket, id, tag, payload)) return
        when (tag) {
          "server.getConfig" -> webSocket.send(success(id, config))
          "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[{"id":"p","title":"Project","workspaceRoot":"/fixture/p"}],"threads":[${shellThreads()}],"archivedThreads":[]}},{"kind":"synchronized"}]}""")
          "orchestration.subscribeThread" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":${projections[payload.string("threadId")]}},{"kind":"synchronized"}]}""")
          "orchestration.getThreadProjection" -> webSocket.send(success(id, projections.getValue(payload.string("threadId"))))
          "orchestration.dispatchCommand" -> {
            val missing = if (payload.string("type") == "message.dispatch") listOf("createdBy", "creationSource").firstOrNull { payload[it] == null } else null
            webSocket.send(if (missing != null) dieExit(id, missing) else success(id, """{"sequence":4}"""))
          }
          "orchestration.launchThread" -> {
            // Like T3: the client's threadId is used, and a repeated commandId resumes the first launch.
            val threadId = payload.string("threadId").ifBlank { "t-new" }
            launched[payload.string("commandId")]?.let { webSocket.send(success(id, """{"threadId":"$threadId","projection":${projections.getValue(it)},"resumed":true}""")); return }
            val message = payload.obj("initialMessage")
            val projection = buildJsonObject {
              put("thread", buildJsonObject { put("id", threadId); put("projectId", payload.string("projectId")); put("title", payload.string("title")); put("runtimeMode", payload.string("runtimeMode")); put("modelSelection", payload.obj("modelSelection")); put("creationSource", payload.string("creationSource")) })
              put("messages", JsonArray(listOf(buildJsonObject { put("id", message.string("messageId")); put("role", "user"); put("text", message.string("text")) })))
              put("runs", JsonArray(emptyList())); put("turnItems", JsonArray(emptyList())); put("runtimeRequests", JsonArray(emptyList()))
            }.toString()
            projections[threadId] = projection; launched[payload.string("commandId")] = threadId
            webSocket.send(success(id, """{"threadId":"$threadId","projection":$projection,"resumed":false,"sequence":5}"""))
          }
        }
      }
    }
    fun enqueueConnect() {
      server.enqueue(json(descriptor))
      server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}"""))
      server.enqueue(json("""{"ticket":"fixture-ticket"}"""))
      server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }
    enqueueConnect()
    try {
      client.connect(SavedEnvironment("env-1", "Test", server.url("/").toString(), "fixture-token"))
      withTimeout(5_000) { client.state.first { it.canWrite } }
      block(Fixture(client, requests, ::enqueueConnect))
    } finally { client.close(); scope.cancel(); runCatching { server.shutdown() } }
  }

  private suspend fun T3Client.openExisting() {
    selectThread("t"); withTimeout(5_000) { state.first { it.canWrite && it.selectedThreadId == "t" && it.selectedRuntimeMode != null } }
  }

  /** Regression: every send failed T3 payload decoding because `createdBy`/`creationSource` were missing. */
  @Test fun messageDispatchCarriesCreationProvenanceRequiredByServer() = withServer { f ->
    f.client.openExisting()
    f.client.sendMessage("hello")
    val payload = f.payloads("orchestration.dispatchCommand").single()
    assertEquals("message.dispatch", payload.string("type"))
    assertEquals("user", payload.string("createdBy"))
    assertEquals("mobile", payload.string("creationSource"))
    assertEquals("t", payload.string("threadId"))
    assertEquals("start_immediately", payload.obj("dispatchMode").string("type"))
    assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
    assertTrue(f.client.state.value.canWrite)
  }

  /** A definite rejection changed nothing: report it and keep the session (and writes) alive. */
  @Test fun serverRejectionIsReportedWithoutFailingTheSession() = withServer(custom = { ws, id, tag, _ ->
    if (tag == "orchestration.dispatchCommand") { ws.send(dieExit(id, "createdBy")); true } else false
  }) { f ->
    f.client.openExisting()
    val error = try { f.client.sendMessage("hello"); null } catch (e: IllegalStateException) { e }
    assertNotNull("A rejected send must fail explicitly", error)
    assertFalse("A definite rejection is not an uncertain outcome", error is UncertainOutcomeException)
    assertTrue(error!!.message.orEmpty().contains("rejected the message"))
    assertFalse("Conversation content must not leak into errors", error.message.orEmpty().contains("hello"))
    val state = f.client.state.value
    assertEquals(ConnectionStatus.Connected, state.connection)
    assertTrue(state.canWrite)
    assertNull(state.error)
    assertEquals(1, f.payloads("orchestration.dispatchCommand").size)
  }

  @Test fun typedRejectionOfModeAndModelChangesKeepsTheSession() = withServer(custom = { ws, id, tag, _ ->
    if (tag == "orchestration.dispatchCommand") { ws.send(typedFailure(id)); true } else false
  }) { f ->
    f.client.openExisting()
    val mode = runCatching { f.client.setRuntimeMode(RuntimeMode.ApprovalRequired) }.exceptionOrNull()
    assertTrue(mode is IllegalStateException && mode !is UncertainOutcomeException && mode.message.orEmpty().contains("rejected the mode change"))
    val model = runCatching { f.client.setModel(ModelOption("codex", "gpt-5")) }.exceptionOrNull()
    assertTrue(model is IllegalStateException && model !is UncertainOutcomeException && model.message.orEmpty().contains("rejected the model change"))
    assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
    assertTrue(f.client.state.value.canWrite)
    assertEquals(2, f.payloads("orchestration.dispatchCommand").size)
  }

  /** The server interrupted the handler: it may or may not have applied, so writes stop. */
  @Test fun interruptedExitIsAnUncertainOutcome() = withServer(custom = { ws, id, tag, _ ->
    if (tag == "orchestration.dispatchCommand") { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Interrupt","fiberId":7}]}}"""); true } else false
  }) { f ->
    f.client.openExisting()
    try { f.client.setRuntimeMode(RuntimeMode.FullAccess); fail("Interrupted mutation must fail") } catch (_: UncertainOutcomeException) { }
    assertEquals(ConnectionStatus.Error, f.client.state.value.connection)
    assertFalse(f.client.state.value.canWrite)
  }

  @Test fun unansweredSendTimesOutAsUncertainNotAsCancellation() = withServer(timeoutMillis = 300, custom = { _, _, tag, _ -> tag == "orchestration.dispatchCommand" }) { f ->
    f.client.openExisting()
    try { f.client.sendMessage("hello"); fail("Unanswered send must fail") }
    catch (e: CancellationException) { fail("A timeout must not look like cancellation: $e") }
    catch (_: UncertainOutcomeException) { }
    assertEquals(ConnectionStatus.Error, f.client.state.value.connection)
    assertTrue(f.client.state.value.error.orEmpty().contains("uncertain"))
    assertFalse(f.client.state.value.canWrite)
    delay(400)
    assertEquals("Uncertain mutations are never replayed", 1, f.payloads("orchestration.dispatchCommand").size)
  }

  @Test fun launchThreadCreatesThreadWithItsFirstMessageLikeT3Web() = withServer { f ->
    val text = "  Fix   the\nbuild\tplease  "
    val draft = ThreadDraft("p", ModelOption("codex", "gpt-5"), RuntimeMode.FullAccess, threadId = "t-new", commandId = "launch-1")
    val id = f.client.launchThread(draft, text)
    assertEquals("t-new", id)
    val payload = f.payloads("orchestration.launchThread").single()
    assertEquals("The draft's ids are sent so the launch can be reconciled", "launch-1", payload.string("commandId"))
    assertEquals("t-new", payload.string("threadId"))
    assertEquals("mobile", payload.string("creationSource"))
    assertEquals("p", payload.string("projectId"))
    assertEquals("Fix the build please", payload.string("title"))
    assertEquals(true, payload["generateTitle"]?.jsonPrimitive?.booleanOrNull)
    assertEquals("codex", payload.obj("modelSelection").string("instanceId"))
    assertEquals("gpt-5", payload.obj("modelSelection").string("model"))
    assertEquals("full-access", payload.string("runtimeMode"))
    assertEquals("default", payload.string("interactionMode"))
    assertEquals("root", payload.obj("workspaceStrategy").string("type"))
    val message = payload.obj("initialMessage")
    assertEquals(initialMessageId("launch-1"), message.string("messageId"))
    assertEquals(text, message.string("text"))
    assertEquals(0, (message["attachments"] as JsonArray).size)
    // The first message is part of the launch; nothing is dispatched separately.
    assertTrue(f.payloads("orchestration.dispatchCommand").isEmpty())

    f.client.selectThread(id)
    val opened = withTimeout(5_000) { f.client.state.first { it.selectedThreadId == "t-new" && it.messages.isNotEmpty() } }
    assertEquals(text, opened.messages.single().text)
    assertEquals(RuntimeMode.FullAccess, opened.selectedRuntimeMode)
    assertEquals("GPT-5", opened.selectedModel?.label)
    withTimeout(5_000) { f.client.state.first { it.canWrite && it.selectedThreadId == "t-new" } }
  }

  @Test fun launchThreadUncertainOutcomeDisablesWritesWithoutReplay() = withServer(timeoutMillis = 300, custom = { _, _, tag, _ -> tag == "orchestration.launchThread" }) { f ->
    try { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "gpt-5")), "hello"); fail("Unanswered launch must fail") }
    catch (_: UncertainOutcomeException) { }
    val state = f.client.state.value
    assertEquals(ConnectionStatus.Error, state.connection)
    assertFalse(state.canWrite)
    assertTrue(state.error.orEmpty().contains("uncertain"))
    try { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "gpt-5")), "hello"); fail("Writes stay disabled") } catch (_: IllegalStateException) { }
    delay(400)
    assertEquals(1, f.payloads("orchestration.launchThread").size)
  }

  @Test fun launchThreadRejectsInvalidResultAsUncertain() = withServer(custom = { ws, id, tag, _ ->
    if (tag == "orchestration.launchThread") { ws.send(success(id, """{"threadId":"t-new","projection":{"thread":{"id":"other"}}}""")); true } else false
  }) { f ->
    try { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "gpt-5"), threadId = "t-new"), "hello"); fail("Mismatched projection must fail") }
    catch (_: UncertainOutcomeException) { }
    assertFalse(f.client.state.value.canWrite)
  }

  @Test fun launchThreadResultForAnotherThreadIsUncertain() = withServer(custom = { ws, id, tag, _ ->
    if (tag == "orchestration.launchThread") { ws.send(success(id, """{"threadId":"someone-else","projection":{"thread":{"id":"someone-else"}},"resumed":false}""")); true } else false
  }) { f ->
    try { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "gpt-5"), threadId = "mine"), "hello"); fail("A result for another thread must fail") }
    catch (_: UncertainOutcomeException) { }
    assertFalse(f.client.state.value.canWrite)
  }

  /** After an uncertain launch, an explicit resend of the same draft is deduplicated by the server. */
  @Test fun explicitResendAfterUncertainLaunchReusesTheDraftIds() {
    var answer = false
    withServer(timeoutMillis = 500, custom = { _, _, tag, _ -> tag == "orchestration.launchThread" && !answer }) { f ->
      val draft = ThreadDraft("p", ModelOption("codex", "gpt-5"))
      try { f.client.launchThread(draft, "hello"); fail("Unanswered launch must fail") } catch (_: UncertainOutcomeException) { }
      answer = true
      f.reconnect()
      assertEquals(draft.threadId, f.client.launchThread(draft, "hello"))
      assertEquals("Resending the same draft returns the same thread", draft.threadId, f.client.launchThread(draft, "hello"))
      val (first, second, third) = f.payloads("orchestration.launchThread")
      for (resend in listOf(second, third)) {
        assertEquals(first.string("commandId"), resend.string("commandId"))
        assertEquals(first.string("threadId"), resend.string("threadId"))
        assertEquals(first.obj("initialMessage").string("messageId"), resend.obj("initialMessage").string("messageId"))
      }
      assertEquals(draft.commandId, first.string("commandId"))
      assertEquals(draft.threadId, first.string("threadId"))
    }
  }

  @Test fun launchThreadRejectionKeepsTheSession() = withServer(custom = { ws, id, tag, _ ->
    if (tag == "orchestration.launchThread") { ws.send(typedFailure(id)); true } else false
  }) { f ->
    val error = runCatching { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "gpt-5")), "hello") }.exceptionOrNull()
    assertTrue(error is IllegalStateException && error !is UncertainOutcomeException)
    assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
    assertTrue(f.client.state.value.canWrite)
  }

  @Test fun launchThreadRefusesARuntimeModeTheModelDoesNotSupport() = withServer { f ->
    try { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "gpt-5"), RuntimeMode.Auto), "hello"); fail("Unsupported mode must fail") }
    catch (e: IllegalStateException) { assertFalse(e is UncertainOutcomeException) }
    // A provider that reports no modes allows all of them.
    f.client.launchThread(ThreadDraft("p", ModelOption("claude", "sonnet"), RuntimeMode.Auto), "hello")
    assertEquals(1, f.payloads("orchestration.launchThread").size)
  }

  @Test fun configReportsDefaultRuntimeModeAndProviderModes() = withServer { f ->
    val state = f.client.state.value
    assertEquals(RuntimeMode.AutoAcceptEdits, state.defaultRuntimeMode)
    assertEquals(listOf(RuntimeMode.ApprovalRequired, RuntimeMode.FullAccess), state.models.first { it.model == "gpt-5" }.supportedRuntimeModes)
    assertEquals(listOf(RuntimeMode.ApprovalRequired, RuntimeMode.FullAccess), state.models.first { it.model == "gpt-5-mini" }.supportedRuntimeModes)
    assertTrue(state.models.first { it.model == "sonnet" }.supportedRuntimeModes.isEmpty())
  }

  @Test fun setModelKeepsOptionsForTheSameModelAndDropsThemOtherwise() = withServer { f ->
    f.client.openExisting()
    f.client.setModel(ModelOption("codex", "gpt-5-mini"))
    withTimeout(5_000) { f.client.state.first { it.canWrite } }
    f.client.setModel(ModelOption("codex", "gpt-5"))
    val (same, other) = f.payloads("orchestration.dispatchCommand").map { it.obj("modelSelection") }
    assertEquals("high", same.obj("options").string("reasoningEffort"))
    assertNull("Another model's options are not carried over", other["options"])
  }

  @Test fun startedThreadWithoutHandoffCannotSwitchProviderInstance() = withServer(projections = mapOf("started" to startedThread(handoff = false))) { f ->
    f.client.selectThread("started")
    val state = withTimeout(5_000) { f.client.state.first { it.canWrite && it.selectedThreadId == "started" && it.selectedModel != null } }
    assertTrue(state.selectedProviderLocked)
    try { f.client.setModel(ModelOption("claude", "sonnet")); fail("Cross-provider change must be refused") }
    catch (e: IllegalStateException) { assertFalse(e is UncertainOutcomeException) }
    assertTrue("Local refusal sends nothing", f.payloads("orchestration.dispatchCommand").isEmpty())
    assertTrue(f.client.state.value.canWrite)
    f.client.setModel(ModelOption("codex", "gpt-5-mini"))
    assertEquals(1, f.payloads("orchestration.dispatchCommand").size)
  }

  @Test fun startedThreadWithHandoffMaySwitchProviderInstance() = withServer(projections = mapOf("started" to startedThread(handoff = true))) { f ->
    f.client.selectThread("started")
    val state = withTimeout(5_000) { f.client.state.first { it.canWrite && it.selectedThreadId == "started" && it.selectedModel != null } }
    assertFalse(state.selectedProviderLocked)
    f.client.setModel(ModelOption("claude", "sonnet"))
    assertEquals("claude", f.payloads("orchestration.dispatchCommand").single().obj("modelSelection").string("instanceId"))
  }

  @Test fun messageToThreadStillPreparingIsQueued() = withServer(projections = mapOf("prep" to """{"thread":{"id":"prep","runtimeMode":"full-access","modelSelection":{"instanceId":"codex","model":"gpt-5"}},"messages":[],"runs":[{"id":"r1","status":"preparing"}],"turnItems":[],"runtimeRequests":[]}""")) { f ->
    f.client.selectThread("prep")
    withTimeout(5_000) { f.client.state.first { it.canWrite && it.selectedThreadId == "prep" && it.runs.isNotEmpty() } }
    f.client.sendMessage("next")
    assertEquals("queue_after_active", f.payloads("orchestration.dispatchCommand").single().obj("dispatchMode").string("type"))
  }

  @Test fun launchThreadValidatesDraftBeforeSending() = withServer { f ->
    try { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "not-offered")), "hello"); fail("Unknown model must fail") } catch (_: IllegalStateException) { }
    try { f.client.launchThread(ThreadDraft("missing", ModelOption("codex", "gpt-5")), "hello"); fail("Unknown project must fail") } catch (_: IllegalStateException) { }
    try { f.client.launchThread(ThreadDraft("p", ModelOption("codex", "gpt-5")), "   "); fail("Blank message must fail") } catch (_: IllegalArgumentException) { }
    assertTrue(f.payloads("orchestration.launchThread").isEmpty())
    assertTrue("Local validation must not fail the session", f.client.state.value.canWrite)
  }

  @Test fun projectionMapsRuntimeModeAndModelToCatalogEntries() = withServer { f ->
    f.client.openExisting()
    val state = f.client.state.value
    assertEquals(RuntimeMode.FullAccess, state.selectedRuntimeMode)
    // The catalog entry is reused so the UI shows the server's display name.
    assertEquals(ModelOption("codex", "gpt-5-mini", "Mini", listOf(RuntimeMode.ApprovalRequired, RuntimeMode.FullAccess)), state.selectedModel)
    assertFalse("A thread that never ran may still change provider", state.selectedProviderLocked)
  }

  @Test fun setRuntimeModeAndModelDispatchThreadCommands() = withServer { f ->
    f.client.openExisting()
    f.client.setRuntimeMode(RuntimeMode.AutoAcceptEdits)
    withTimeout(5_000) { f.client.state.first { it.canWrite } }
    f.client.setModel(ModelOption("codex", "gpt-5"))
    val (mode, model) = f.payloads("orchestration.dispatchCommand")
    assertEquals("thread.runtime-mode.set", mode.string("type"))
    assertEquals("t", mode.string("threadId"))
    assertEquals("auto-accept-edits", mode.string("runtimeMode"))
    assertTrue(mode.string("commandId").isNotBlank())
    assertEquals("thread.model-selection.set", model.string("type"))
    assertEquals("t", model.string("threadId"))
    assertEquals("codex", model.obj("modelSelection").string("instanceId"))
    assertEquals("gpt-5", model.obj("modelSelection").string("model"))
    assertTrue(model.string("commandId").isNotBlank())
    try { f.client.setModel(ModelOption("codex", "not-offered")); fail("Unknown model must fail") } catch (_: IllegalStateException) { }
    assertEquals(2, f.payloads("orchestration.dispatchCommand").size)
  }

  private fun startedThread(handoff: Boolean) = """{"thread":{"id":"started","runtimeMode":"full-access","activeProviderThreadId":"pt","modelSelection":{"instanceId":"codex","model":"gpt-5","options":{"reasoningEffort":"high"}}},
    "messages":[],"runs":[{"id":"r1","status":"completed","providerThreadId":"pt"}],"turnItems":[],"runtimeRequests":[],
    "providerThreads":[{"id":"pt","providerInstanceId":"codex","providerSessionId":"s1","appThreadId":"started","nativeThreadRef":null}],
    "providerSessions":[{"id":"s1","status":"ready","capabilities":{"sessions":{"supportsProviderSwitchingViaHandoff":$handoff}}}]}""".replace("\n", "")

  @Test fun titleSeedFollowsT3() {
    assertEquals("New thread", threadTitleSeed("  \n "))
    assertEquals("Short", threadTitleSeed(" Short "))
    val long = "a".repeat(60)
    assertEquals("a".repeat(50) + "...", threadTitleSeed(long))
    val emoji = "😀".repeat(51)
    assertEquals("😀".repeat(50) + "...", threadTitleSeed(emoji))
  }

  @Test fun cancelledConnectionAttemptExplainsTheDisconnect() = runBlocking {
    val server = MockWebServer(); server.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = T3Client(scope)
    val requested = CompletableDeferred<Unit>()
    server.enqueue(json(descriptor))
    server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}"""))
    server.enqueue(json("""{"ticket":"fixture-ticket"}"""))
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) { if (text.contains("server.getConfig")) requested.complete(Unit) }
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
    }))
    try {
      val attempt = launch { client.connect(SavedEnvironment("env-1", "Test", server.url("/").toString(), "fixture-token")) }
      withTimeout(5_000) { requested.await() }
      attempt.cancelAndJoin()
      assertEquals(ConnectionStatus.Disconnected, client.state.value.connection)
      assertTrue(client.state.value.error.orEmpty().contains("cancelled"))
    } finally { client.close(); scope.cancel(); server.shutdown() }
  }
}
