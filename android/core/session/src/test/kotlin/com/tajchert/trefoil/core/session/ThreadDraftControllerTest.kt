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
    private val connected = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true, shellLive = true,
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
        val optionChanges = mutableListOf<Pair<String, Any>>()
        override suspend fun setModelOption(id: String, value: Any) { optionChanges += id to value }
        val links = mutableListOf<Pair<ThreadTarget, ThreadIssueLink>>()
        var onLink: suspend (ThreadTarget, ThreadIssueLink) -> Unit = { _, _ -> }
        override suspend fun linkIssue(target: ThreadTarget, link: ThreadIssueLink) { links += target to link; onLink(target, link) }
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean) = error("unused")
        override suspend fun connect(environment: SavedEnvironment) = error("unused")
        override suspend fun disconnect() = Unit
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

    @Test(timeout = 30_000) fun cachedRowsListingTheLaunchedThreadNeverClearTheDraft() = runTest {
        val h = harness()
        h.client.launch = { throw UncertainOutcomeException("lost") }
        h.controller.start(draft)
        h.controller.send("hello")
        runCurrent()
        // Connected, but the rows are still the cached ones (no synchronized marker yet): they may list a thread the server
        // has since deleted, so they are no evidence.
        val stale = connected.copy(shellLive = false, threads = connected.threads + ThreadSummary(draft.threadId, "p", "Hello"))
        h.client.mutable.value = stale
        runCurrent()
        assertEquals("Stale rows never settle the launch", draft, h.controller.draft.value)
        assertTrue(h.client.selected.isEmpty())
        // The catch-up removed the deleted thread: the draft is still there to resend.
        h.client.mutable.value = connected
        runCurrent()
        assertEquals(draft, h.controller.draft.value)
        // A live shell listing it settles it.
        h.client.mutable.value = stale.copy(shellLive = true)
        runCurrent()
        assertNull(h.controller.draft.value)
        assertEquals(listOf(draft.threadId), h.client.selected)
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

    @Test fun draftModelOptionsAreLocalAndValidatedAgainstTheCatalog() = runTest {
        val effort = ModelOptionDescriptor.Select("effort", "Effort", choices = listOf(ModelOptionChoice("low", "Low"), ModelOptionChoice("high", "High")), isReasoning = true)
        val opus = ModelOption("claude", "opus", "Opus", descriptors = listOf(effort, ModelOptionDescriptor.Toggle("fastMode", "Fast mode")))
        val sonnet = ModelOption("claude", "sonnet", "Sonnet", descriptors = listOf(effort))
        val h = harness(connected.copy(models = listOf(opus, sonnet)))
        // The draft's model may be a bare copy; the catalog entry's descriptors decide.
        h.controller.start(draft.copy(model = ModelOption("claude", "opus")))
        h.controller.setModelOption("effort", "high")
        h.controller.setModelOption("fastMode", true)
        assertEquals(mapOf("effort" to "high", "fastMode" to true), h.controller.draft.value!!.modelOptions)
        h.controller.setModelOption("effort", "extreme")
        assertNotNull("An unoffered value is refused with a message", h.notices.lastOrNull())
        assertEquals("high", h.controller.draft.value!!.modelOptions["effort"])
        h.controller.setModel(sonnet)
        assertEquals("Options the new model does not offer are dropped", mapOf("effort" to "high"), h.controller.draft.value!!.modelOptions)
        runCurrent()
        assertTrue("Drafts never mutate the server", h.client.optionChanges.isEmpty())
    }

    @Test fun threadModelOptionDispatchesToTheClient() = runTest {
        val h = harness()
        h.controller.setModelOption("effort", "high")
        runCurrent()
        assertEquals(listOf<Pair<String, Any>>("effort" to "high"), h.client.optionChanges)
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

    private val issue = ThreadIssueLink(IssueKey("github", "github.com", "acme/app", "42"), "p", "https://github.com/acme/app/issues/42",
        displayKey = "#42", title = "Crash")
    private val issueDraft = draft.copy(issueLink = issue)
    private val target = ThreadTarget("env-1", issueDraft.threadId)
    private fun ClientState.withThread(issues: List<ThreadIssueLink> = emptyList()) =
        copy(threads = threads.filter { it.id != issueDraft.threadId } + ThreadSummary(issueDraft.threadId, "p", "Crash", issues = issues))

    @Test fun issueLinkIsSentOnceAfterASuccessfulLaunch() = runTest {
        val h = harness()
        h.controller.start(issueDraft)
        h.controller.send("#42: Crash"); runCurrent()
        assertTrue("The link waits for the live shell row", h.client.links.isEmpty())
        h.client.mutable.update { it.withThread() }; runCurrent()
        assertEquals(listOf(target to issue), h.client.links)
        // Later shell updates (the row showing the link, other changes) never send it again.
        h.client.mutable.update { it.withThread(listOf(issue)) }; runCurrent()
        h.client.mutable.update { it.copy(threads = it.threads + ThreadSummary("t2", "p", "Other")) }; runCurrent()
        assertEquals(1, h.client.links.size)
        assertNull(h.controller.issueLinkNotice.value)
    }

    @Test fun launchWithTheRowAlreadyListedLinksRightAway() = runTest {
        val h = harness(connected.withThread())
        h.client.launch = { throw IllegalStateException("rejected") }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        assertEquals(listOf(target to issue), h.client.links)
    }

    @Test fun draftWithoutAnIssueNeverLinks() = runTest {
        val h = harness()
        h.controller.start(draft)
        h.controller.send("hello"); runCurrent()
        h.client.mutable.update { it.copy(threads = it.threads + ThreadSummary(draft.threadId, "p", "Hello")) }; runCurrent()
        assertTrue(h.client.links.isEmpty())
    }

    @Test fun uncertainLaunchThenReconcileHitSendsOneLink() = runTest {
        val h = harness()
        h.client.launch = { throw UncertainOutcomeException("lost") }
        h.controller.start(issueDraft)
        h.controller.send("hello")
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Error, canWrite = false) }; runCurrent()
        assertTrue(h.client.links.isEmpty())
        h.client.mutable.value = connected; runCurrent()
        assertTrue("No row yet: nothing to link", h.client.links.isEmpty())
        h.client.mutable.update { it.withThread() }; runCurrent()
        h.client.mutable.update { it.copy(threads = it.threads + ThreadSummary("t2", "p", "Other")) }; runCurrent()
        assertEquals(listOf(target to issue), h.client.links)
        assertEquals(1, h.client.launches.size)
    }

    @Test fun reconcileHitWhoseRowAlreadyShowsTheIssueSendsNothing() = runTest {
        val h = harness()
        h.client.launch = { throw UncertainOutcomeException("lost") }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        h.client.mutable.update { it.withThread(listOf(issue.copy(source = "agent"))) }; runCurrent()
        assertNull(h.controller.draft.value)
        assertTrue(h.client.links.isEmpty())
        assertNull(h.controller.issueLinkNotice.value)
    }

    @Test fun definiteLinkRejectionShowsTheNoticeWithoutRetrying() = runTest {
        val h = harness()
        h.client.onLink = { _, _ -> throw IllegalStateException("T3 rejected the issue link. Check the thread and try again.") }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        h.client.mutable.update { it.withThread() }; runCurrent()
        val notice = h.controller.issueLinkNotice.value
        assertEquals(IssueLinkNotice(issueDraft.threadId, "env-1", issue), notice)
        assertEquals(ISSUE_NOT_LINKED_MESSAGE, notice!!.message)
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Error) }; runCurrent()
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Connected, threads = it.threads + ThreadSummary("t2", "p", "Other")) }; runCurrent()
        assertEquals("Never retried on its own", 1, h.client.links.size)
        assertEquals(notice, h.controller.issueLinkNotice.value)
        h.controller.dismissIssueLinkNotice()
        assertNull(h.controller.issueLinkNotice.value)
    }

    @Test fun uncertainLinkIsNeverResentAndTheRowDecidesTheNotice() = runTest {
        val h = harness()
        h.client.onLink = { _, _ -> throw UncertainOutcomeException("lost") }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        h.client.mutable.update { it.withThread() }; runCurrent()
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Error, canWrite = false, shellLive = false) }; runCurrent()
        assertNull(h.controller.issueLinkNotice.value)
        // Reconnected; the live row lacks the key: offer "Link issue", never send it.
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Connected, canWrite = true, shellLive = true) }; runCurrent()
        assertEquals(1, h.client.links.size)
        assertEquals(IssueLinkNotice(issueDraft.threadId, "env-1", issue), h.controller.issueLinkNotice.value)
    }

    @Test fun uncertainLinkThatLandedShowsNoNotice() = runTest {
        val h = harness()
        h.client.onLink = { _, _ -> throw UncertainOutcomeException("lost") }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        h.client.mutable.update { it.withThread() }; runCurrent()
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Error, shellLive = false) }; runCurrent()
        h.client.mutable.update { it.copy(connection = ConnectionStatus.Connected, shellLive = true).withThread(listOf(issue)) }; runCurrent()
        assertEquals(1, h.client.links.size)
        assertNull(h.controller.issueLinkNotice.value)
    }

    @Test fun environmentSwitchBetweenLaunchAndLinkSendsNoLink() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.client.launch = { gate.await(); it.threadId }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        val other = SavedEnvironment("env-2", "Other", "https://other.invalid", "fixture-token")
        h.client.mutable.value = connected.copy(environment = other).withThread()
        runCurrent()
        gate.complete(Unit); runCurrent()
        h.client.mutable.value = connected.withThread(); runCurrent()
        assertTrue(h.client.links.isEmpty())
    }

    @Test fun environmentSwitchWhileWaitingForTheRowDropsTheLink() = runTest {
        val h = harness()
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        val other = SavedEnvironment("env-2", "Other", "https://other.invalid", "fixture-token")
        h.client.mutable.value = connected.copy(environment = other).withThread(); runCurrent()
        h.client.mutable.value = connected.withThread(); runCurrent()
        assertTrue(h.client.links.isEmpty())
    }

    @Test fun retryIssueLinkSendsExactlyOneNewCommandPerCall() = runTest {
        val h = harness()
        h.client.onLink = { _, _ -> throw IllegalStateException("T3 rejected the issue link.") }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        h.client.mutable.update { it.withThread() }; runCurrent()
        assertEquals(1, h.client.links.size)
        h.controller.retryIssueLink(); runCurrent()
        assertEquals(2, h.client.links.size)
        assertNotNull("Still rejected: the notice stays", h.controller.issueLinkNotice.value)
        // Repeat taps while one is in flight do nothing.
        val gate = CompletableDeferred<Unit>()
        h.client.onLink = { _, _ -> gate.await() }
        h.controller.retryIssueLink(); h.controller.retryIssueLink(); runCurrent()
        assertEquals(3, h.client.links.size)
        gate.complete(Unit); runCurrent()
        assertNull(h.controller.issueLinkNotice.value)
        h.controller.retryIssueLink(); runCurrent()
        assertEquals("No notice, nothing to retry", 3, h.client.links.size)
        assertTrue(h.client.links.all { it == target to issue })
    }

    @Test fun rejectionBecauseTheRowAlreadyShowsTheIssueIsNotANotice() = runTest {
        val h = harness()
        h.client.onLink = { _, _ -> throw IllegalStateException("T3 rejected the issue link.") }
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        h.client.mutable.update { it.withThread() }; runCurrent()
        assertNotNull(h.controller.issueLinkNotice.value)
        // An agent links it meanwhile: the notice no longer applies.
        h.client.mutable.update { it.withThread(listOf(issue.copy(source = "agent"))) }; runCurrent()
        assertNull(h.controller.issueLinkNotice.value)
    }

    @Test fun environmentSwitchWhileTheLinkIsInFlightShowsNoStaleNotice() = runTest {
        val other = SavedEnvironment("env-2", "Other", "https://other.invalid", "fixture-token")
        for (failure in listOf<Throwable>(IllegalStateException("T3 rejected the issue link."), UncertainOutcomeException("lost"))) {
            val h = harness()
            val gate = CompletableDeferred<Unit>()
            h.client.onLink = { _, _ -> gate.await(); throw failure }
            h.controller.start(issueDraft)
            h.controller.send("hello"); runCurrent()
            h.client.mutable.update { it.withThread() }; runCurrent()
            assertEquals(1, h.client.links.size)
            h.client.mutable.value = connected.copy(environment = other); runCurrent()
            gate.complete(Unit); runCurrent()
            assertNull(h.controller.issueLinkNotice.value)
            // Back in env-1 with the row lacking the key: an uncertain outcome was dropped, so no notice either.
            h.client.mutable.value = connected.withThread(); runCurrent()
            assertNull(h.controller.issueLinkNotice.value)
            assertEquals(1, h.client.links.size)
        }
    }

    @Test fun rowArrivingWhileAnotherLinkIsInFlightStillLinksOnce() = runTest {
        val h = harness()
        // An earlier draft's link is rejected and the user retries it; meanwhile a second draft starts.
        val first = ThreadDraft("p", ModelOption("codex", "gpt-5"), issueLink = issue.copy(key = issue.key.copy(key = "7")))
        h.client.onLink = { _, _ -> throw IllegalStateException("rejected") }
        h.controller.start(first)
        h.controller.send("first"); runCurrent()
        h.client.mutable.update { it.copy(threads = it.threads + ThreadSummary(first.threadId, "p", "First")) }; runCurrent()
        assertNotNull(h.controller.issueLinkNotice.value)
        h.controller.start(issueDraft)
        h.controller.send("hello"); runCurrent()
        val gate = CompletableDeferred<Unit>()
        h.client.onLink = { _, _ -> gate.await() }
        h.controller.retryIssueLink(); runCurrent()
        assertEquals(2, h.client.links.size)
        h.client.mutable.update { it.withThread() }; runCurrent()
        assertEquals("Waits while the retry is in flight", 2, h.client.links.size)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(target to issue), h.client.links.drop(2))
        h.client.mutable.update { it.copy(threads = it.threads + ThreadSummary("t2", "p", "Other")) }; runCurrent()
        assertEquals(3, h.client.links.size)
    }
}
