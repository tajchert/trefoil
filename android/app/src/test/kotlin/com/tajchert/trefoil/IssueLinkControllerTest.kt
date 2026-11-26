package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class IssueLinkControllerTest {
    private class Fake(row: ThreadSummary = ThreadSummary("t1", "p1", "Thread")) : TrefoilClient {
        override val state = MutableStateFlow(
            ClientState(
                connection = ConnectionStatus.Connected, issuesSupported = true, canWriteThreads = true,
                environment = SavedEnvironment("e1", "Mac", "http://10.0.0.2:3773", "token"), threads = listOf(row), selectedThreadId = row.id,
            ),
        )
        val sent = mutableListOf<String>()
        var answer: suspend () -> Unit = {}
        override suspend fun linkIssue(target: ThreadTarget, link: ThreadIssueLink) { sent += "link ${target.threadId} ${link.key.key}"; answer() }
        override suspend fun unlinkIssue(target: ThreadTarget, key: IssueKey) { sent += "unlink ${target.threadId} ${key.key}"; answer() }
        fun showIssues(vararg keys: IssueKey) {
            state.value = state.value.copy(threads = state.value.threads.map { it.copy(issues = keys.map(::linkOf)) })
        }
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = error("unused")
        override suspend fun connect(environment: SavedEnvironment) {}
        override suspend fun disconnect() {}
        override suspend fun selectThread(threadId: String) {}
        override suspend fun sendMessage(text: String) {}
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) {}
        override suspend fun reconnect() {}
        override fun close() {}
    }

    private class Harness(val fake: Fake, scope: CoroutineScope, settleMillis: Long = 10_000) {
        val notices = mutableListOf<String?>()
        var settled = 0
        val controller = IssueLinkController(scope, { fake }, { notices += it }, { settled++ }, settleMillis = settleMillis)
    }

    companion object {
        private fun key(n: Int = 1) = IssueKey("github", "github.com", "o/r", n.toString())
        private fun linkOf(key: IssueKey) = ThreadIssueLink(key, "p1", "https://github.com/o/r/issues/${key.key}", displayKey = "#${key.key}", title = "Title ${key.key}")
        private fun summary(n: Int = 1, title: String = "Title $n") = IssueSummary(
            IssueRef("p1", "github", "github.com", "o/r", n.toString()), "github.com", "#$n", title, "https://github.com/o/r/issues/$n",
            IssueActor("a"), IssueState(true, "Open"), "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, emptyList(), emptyList(), 0, "Project",
        )
        private suspend fun settle() = repeat(6) { yield() }
    }

    @Test fun actionFollowsTheSelectedThreadAndTheRow() {
        val fake = Fake()
        val s = fake.state.value
        assertEquals(IssueLinkAction.Link, issueLinkAction("e1", summary(), s, hasDraft = false))
        fake.showIssues(key())
        assertEquals(IssueLinkAction.Unlink, issueLinkAction("e1", summary(), fake.state.value, hasDraft = false))
        // The identity ignores host and scope case, like the session.
        val shouting = summary().copy(hostName = "GitHub.com", ref = summary().ref.copy(scope = "O/R"))
        assertEquals(IssueLinkAction.Unlink, issueLinkAction("e1", shouting, fake.state.value, hasDraft = false))
        assertEquals(IssueLinkAction.Link, issueLinkAction("e1", summary(2), fake.state.value, hasDraft = false))
    }

    @Test fun actionIsHiddenUnlessEveryRuleHolds() {
        val s = Fake().state.value
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", null, s, false))
        assertEquals(IssueLinkAction.None, issueLinkAction(null, summary(), s, false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e2", summary(), s, false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s, hasDraft = true))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s.copy(selectedThreadId = null), false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s.copy(selectedThreadId = "gone"), false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s.copy(canWriteThreads = false), false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s.copy(issuesSupported = false), false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s.copy(connection = ConnectionStatus.Reconnecting), false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s.copy(threads = s.threads.map { it.copy(archived = true) }), false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(), s.copy(threads = s.threads.map { it.copy(archivedAt = "2026-01-01T00:00:00Z") }), false))
        assertEquals(IssueLinkAction.None, issueLinkAction("e1", summary(title = " "), s, false))
    }

    @Test fun linkIsBuiltFromTheDetailAsManual() {
        val link = issueLinkFor(summary(7))!!
        assertEquals(IssueKey("github", "github.com", "o/r", "7"), link.key)
        assertEquals("p1", link.projectId)
        assertEquals("https://github.com/o/r/issues/7", link.url)
        assertEquals("#7", link.displayKey)
        assertEquals("Title 7", link.title)
        assertEquals("manual", link.source)
        assertNull(issueLinkFor(summary(7).copy(url = "")))
    }

    @Test fun repeatTapsWhileInFlightSendNothingAndTheRowSettlesIt() = runBlocking {
        val fake = Fake(); val h = Harness(fake, this)
        val gate = CompletableDeferred<Unit>()
        fake.answer = { gate.await() }
        h.controller.link(linkOf(key()))
        assertNotNull(h.controller.pending.value)
        h.controller.link(linkOf(key()))
        h.controller.unlink(key(2))
        h.controller.link(linkOf(key(3)))
        settle()
        assertEquals(listOf("link t1 1"), fake.sent)
        gate.complete(Unit); settle()
        // Accepted, but the shell row has not shown the key yet: still in flight, linked threads not read yet.
        assertNotNull(h.controller.pending.value)
        assertEquals(0, h.settled)
        fake.showIssues(key()); settle()
        assertNull(h.controller.pending.value)
        assertEquals(1, h.settled)
        assertEquals(listOf("link t1 1"), fake.sent)
        assertEquals(listOf<String?>(null), h.notices)
    }

    @Test fun unlinkWaitsForTheRowToDropTheKey() = runBlocking {
        val fake = Fake(); fake.showIssues(key(), key(2)); val h = Harness(fake, this)
        h.controller.unlink(key())
        settle()
        assertEquals(listOf("unlink t1 1"), fake.sent)
        assertEquals(IssueLinkPending("e1", "t1", key().identity, linking = false), h.controller.pending.value)
        fake.showIssues(key(2)); settle()
        assertNull(h.controller.pending.value)
        assertEquals(1, h.settled)
    }

    @Test fun anAcceptedCommandThatTheRowNeverShowsIsReleasedAfterTheTimeout() = runBlocking {
        val fake = Fake(); val h = Harness(fake, this, settleMillis = 30)
        h.controller.link(linkOf(key()))
        settle()
        assertNotNull(h.controller.pending.value)
        delay(120); settle()
        assertNull(h.controller.pending.value)
        assertEquals(1, h.settled)
        assertEquals(1, fake.sent.size)
    }

    @Test fun rejectionShowsTheNoticeKeepsTheStateAndDoesNotRetry() = runBlocking {
        val fake = Fake(); val h = Harness(fake, this)
        fake.answer = { throw IllegalStateException("T3 rejected the issue link (secret title)") }
        h.controller.link(linkOf(key()))
        settle()
        assertNull(h.controller.pending.value)
        assertEquals(listOf(null, "T3 rejected the issue link."), h.notices)
        assertEquals(0, h.settled)
        delay(50); settle()
        assertEquals(1, fake.sent.size)
        assertTrue(fake.state.value.threads.single().issues.isEmpty())
        // A new tap is a new user action and may send again.
        h.controller.link(linkOf(key())); settle()
        assertEquals(2, fake.sent.size)
    }

    @Test fun otherFailuresUseFixedWording() = runBlocking {
        val fake = Fake(); val h = Harness(fake, this)
        fake.answer = { throw IllegalStateException("Already linked") }
        h.controller.link(linkOf(key())); settle()
        fake.answer = { throw IssueException(IssueFailure.NotSupported, "Issues are unavailable") }
        h.controller.link(linkOf(key())); settle()
        fake.showIssues(key())
        fake.answer = { throw IllegalArgumentException("blank") }
        h.controller.unlink(key()); settle()
        assertEquals(
            listOf(null, "Could not link the issue. Check the thread and try again.", null, "This T3 server does not support issues.",
                null, "Could not unlink the issue. Check the thread and try again."),
            h.notices,
        )
        assertEquals(3, fake.sent.size)
    }

    @Test fun anUncertainOutcomeIsNeverResentAndLeavesTheRowAsTheTruth() = runBlocking {
        val fake = Fake(); val h = Harness(fake, this)
        fake.answer = { throw UncertainOutcomeException("timed out") }
        h.controller.link(linkOf(key()))
        settle()
        assertNull(h.controller.pending.value)
        assertEquals(1, fake.sent.size)
        assertEquals(IssueLinkController.uncertainMessage(true), h.notices.last())
        assertEquals(0, h.settled)
        // The session failed: reconnecting brings the row back, nothing is sent by itself.
        fake.state.value = fake.state.value.copy(connection = ConnectionStatus.Error, canWriteThreads = false)
        fake.state.value = fake.state.value.copy(connection = ConnectionStatus.Connected, canWriteThreads = true)
        delay(50); settle()
        assertEquals(1, fake.sent.size)
        assertEquals(IssueLinkAction.Link, issueLinkAction("e1", summary(), fake.state.value, false))
    }

    @Test fun nothingIsSentWhenTheRowAlreadyMatchesOrNoThreadIsSelected() = runBlocking {
        val fake = Fake(); fake.showIssues(key()); val h = Harness(fake, this)
        h.controller.link(linkOf(key()))
        h.controller.unlink(key(2))
        fake.state.value = fake.state.value.copy(selectedThreadId = null)
        h.controller.unlink(key())
        settle()
        assertTrue(fake.sent.isEmpty())
        assertNull(h.controller.pending.value)
    }
}
