package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadDraftControllerTest {
    private val environment = SavedEnvironment("env-1", "Fixture", "https://fixture.invalid", "fixture-token")
    private val connected = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true,
        threads = listOf(ThreadSummary("t", "p", "Existing")))

    private class FakeClient(initial: ClientState) : TrefoilClient {
        val mutable = MutableStateFlow(initial)
        override val state: StateFlow<ClientState> = mutable
        val launches = mutableListOf<ThreadDraft>()
        val selected = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val modes = mutableListOf<RuntimeMode>()
        var launch: suspend (ThreadDraft) -> String = { it.threadId }
        var send: suspend (String) -> Unit = {}
        var onMode: suspend (RuntimeMode) -> Unit = {}
        override suspend fun launchThread(draft: ThreadDraft, firstMessage: String): String { launches += draft; return launch(draft) }
        override suspend fun selectThread(threadId: String) { selected += threadId }
        override suspend fun sendMessage(text: String) { sent += text; send(text) }
        override suspend fun setRuntimeMode(mode: RuntimeMode) { modes += mode; onMode(mode) }
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean) = error("unused")
        override suspend fun connect(environment: SavedEnvironment) = error("unused")
        override suspend fun disconnect() = Unit
        override suspend fun interruptRun() = Unit
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) = Unit
        override suspend fun reconnect() = Unit
        override fun close() = Unit
    }

    private class Harness(val client: FakeClient, val controller: ThreadDraftController, val notices: MutableList<String?>, val failures: MutableList<Unit>)

    private fun TestScope.harness(initial: ClientState = connected): Harness {
        val client = FakeClient(initial)
        val notices = mutableListOf<String?>()
        val controller = ThreadDraftController(backgroundScope, client) { notices += it }
        val failures = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.sendFailed.toList(failures) }
        runCurrent()
        return Harness(client, controller, notices, failures)
    }

    private val draft = ThreadDraft("p", ModelOption("codex", "gpt-5"))

    @Test fun successfulLaunchClearsDraftAndOpensThread() = runTest {
        val h = harness()
        h.controller.start(draft)
        h.controller.send("hello")
        runCurrent()
        assertNull(h.controller.draft.value)
        assertEquals(listOf(draft.threadId), h.client.selected)
        assertTrue(h.failures.isEmpty())
    }

    @Test fun uncertainLaunchKeepsDraftAndReconcilesAfterReconnect() = runTest {
        val h = harness()
        h.client.launch = { throw UncertainOutcomeException("lost") }
        h.controller.start(draft)
        h.controller.send("hello")
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Error, canWrite = false) }
        runCurrent()
        assertEquals("The draft and its ids survive an uncertain launch", draft, h.controller.draft.value)
        assertEquals(1, h.failures.size)
        assertTrue(h.notices.last().orEmpty().contains("uncertain"))
        // Reconnected, but the shell does not list the thread yet: keep waiting, never resend.
        h.client.mutable.value = connected
        runCurrent()
        assertEquals(draft, h.controller.draft.value)
        assertEquals(1, h.client.launches.size)
        // The shell lists the draft's thread: it was created, so open it.
        h.client.mutable.update { it.copy(threads = it.threads + ThreadSummary(draft.threadId, "p", "Hello")) }
        runCurrent()
        assertNull(h.controller.draft.value)
        assertEquals(listOf(draft.threadId), h.client.selected)
        assertEquals("Nothing is retried automatically", 1, h.client.launches.size)
    }

    @Test fun explicitResendAfterUncertainLaunchReusesIds() = runTest {
        val h = harness()
        h.client.launch = { throw UncertainOutcomeException("lost") }
        h.controller.start(draft)
        h.controller.send("hello")
        runCurrent()
        h.controller.setRuntimeMode(RuntimeMode.FullAccess)
        h.client.launch = { it.threadId }
        h.controller.send("hello")
        runCurrent()
        val (first, second) = h.client.launches
        assertEquals(first.threadId, second.threadId)
        assertEquals(first.commandId, second.commandId)
        assertNull(h.controller.draft.value)
        assertEquals(listOf(draft.threadId), h.client.selected)
    }

    @Test fun cancelledLaunchKeepsDraftAndReportsFailure() = runTest {
        val h = harness()
        h.client.launch = { throw CancellationException("inner timeout") }
        h.controller.start(draft)
        h.controller.send("hello")
        runCurrent()
        assertEquals(draft, h.controller.draft.value)
        assertEquals(1, h.failures.size)
    }

    @Test fun rejectedLaunchKeepsDraftAndShowsServerMessage() = runTest {
        val h = harness()
        h.client.launch = { throw IllegalStateException("T3 rejected the new conversation. Check the conversation and try again.") }
        h.controller.start(draft)
        h.controller.send("hello")
        runCurrent()
        assertEquals(draft, h.controller.draft.value)
        assertEquals(1, h.failures.size)
        assertTrue(h.notices.last().orEmpty().contains("rejected"))
    }

    @Test fun rejectedLaunchOfAThreadThatExistsOpensIt() = runTest {
        val h = harness(connected.copy(threads = connected.threads + ThreadSummary(draft.threadId, "p", "Created")))
        h.client.launch = { throw IllegalStateException("rejected") }
        h.controller.start(draft)
        h.controller.send("hello")
        runCurrent()
        assertNull(h.controller.draft.value)
        assertEquals(listOf(draft.threadId), h.client.selected)
    }

    @Test fun everyFailedThreadSendEmitsSendFailed() = runTest {
        val h = harness()
        val failures = listOf<Throwable>(UncertainOutcomeException("lost"), IllegalStateException("T3 rejected the message."),
            IllegalArgumentException("Message is empty"), CancellationException("timeout"))
        for (failure in failures) {
            h.client.send = { throw failure }
            h.controller.send("hello")
            runCurrent()
        }
        assertEquals(failures.size, h.failures.size)
        h.client.send = {}
        h.controller.send("hello")
        runCurrent()
        assertEquals("A delivered message is not a failure", failures.size, h.failures.size)
        assertEquals(5, h.client.sent.size)
    }

    @Test fun localDraftValidationFailureEmitsSendFailed() = runTest {
        val h = harness()
        h.client.launch = { throw IllegalStateException("Choose a server-provided model") }
        h.controller.start(draft)
        h.controller.send("hello")
        runCurrent()
        assertEquals(1, h.failures.size)
        assertEquals("Choose a server-provided model", h.notices.last())
    }

    @Test fun secondTapWhileLaunchingDoesNotLaunchTwice() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.client.launch = { gate.await(); it.threadId }
        h.controller.start(draft)
        h.controller.send("hello"); runCurrent()
        h.controller.send("hello"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(1, h.client.launches.size)
        assertNull(h.controller.draft.value)
    }

    @Test fun draftModelChangeKeepsIdsAndASupportedMode() = runTest {
        val h = harness()
        h.controller.start(draft.copy(runtimeMode = RuntimeMode.Auto))
        val limited = ModelOption("claude", "sonnet", supportedRuntimeModes = listOf(RuntimeMode.ApprovalRequired, RuntimeMode.FullAccess))
        h.controller.setModel(limited)
        val updated = h.controller.draft.value!!
        assertEquals(limited, updated.model)
        assertEquals(RuntimeMode.ApprovalRequired, updated.runtimeMode)
        assertEquals(draft.threadId, updated.threadId)
        assertEquals(draft.commandId, updated.commandId)
    }

    @Test fun threadSettingRejectionShowsTheServerMessage() = runTest {
        val h = harness()
        h.client.onMode = { throw IllegalStateException("T3 rejected the mode change. Check the conversation and try again.") }
        h.controller.setRuntimeMode(RuntimeMode.FullAccess)
        runCurrent()
        assertEquals("T3 rejected the mode change. Check the conversation and try again.", h.notices.last())
        h.client.onMode = { throw UncertainOutcomeException("lost") }
        h.controller.setRuntimeMode(RuntimeMode.FullAccess)
        runCurrent()
        assertTrue(h.notices.last().orEmpty().contains("uncertain"))
    }

    @Test fun draftFromAnotherEnvironmentIsNotReconciled() = runTest {
        val h = harness()
        h.client.launch = { throw UncertainOutcomeException("lost") }
        h.controller.start(draft)
        h.controller.send("hello"); runCurrent()
        val other = SavedEnvironment("env-2", "Other", "https://other.invalid", "fixture-token")
        h.client.mutable.value = connected.copy(environment = other, threads = listOf(ThreadSummary(draft.threadId, "p", "Same id elsewhere")))
        runCurrent()
        assertEquals(draft, h.controller.draft.value)
        assertTrue(h.client.selected.isEmpty())
    }
}
