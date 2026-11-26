package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.issues.IssueActivityItem
import com.tajchert.trefoil.ui.issues.issueActivity
import com.tajchert.trefoil.ui.issues.issueEventText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class IssueDetailControllerTest {
    private class Fake : TrefoilClient {
        override val state = MutableStateFlow(ClientState(connection = ConnectionStatus.Connected, issuesSupported = true, issueLinkedThreadsSupported = true))
        val details = mutableListOf<IssueRef>()
        val pages = mutableListOf<String>()
        val linked = mutableListOf<IssueKey>()
        var detailAnswer: suspend (IssueRef) -> IssueDetail = { detail(it) }
        var commentsAnswer: suspend (String) -> IssueCommentsPage = { IssueCommentsPage(emptyList(), null) }
        var linkedAnswer: suspend (IssueKey) -> List<IssueLinkedThread> = { listOf(IssueLinkedThread("t1", "p1", "Thread", archived = false, settled = false)) }
        override suspend fun issueDetail(ref: IssueRef): IssueDetail { details += ref; return detailAnswer(ref) }
        override suspend fun issueComments(ref: IssueRef, cursor: String): IssueCommentsPage { pages += cursor; return commentsAnswer(cursor) }
        override suspend fun issueLinkedThreads(key: IssueKey): List<IssueLinkedThread> { linked += key; return linkedAnswer(key) }
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = error("unused")
        override suspend fun connect(environment: SavedEnvironment) {}
        override suspend fun disconnect() {}
        override suspend fun selectThread(threadId: String) {}
        override suspend fun sendMessage(text: String) {}
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) {}
        override suspend fun reconnect() {}
        override fun close() {}
    }

    companion object {
        private fun ref(n: Int = 1) = IssueRef("p1", "github", "github.com", "o/r", n.toString())
        private fun key(n: Int = 1) = IssueKey("github", "github.com", "o/r", n.toString())
        private fun comment(i: Int) = IssueComment("c$i", IssueActor("a"), "body $i", "2026-01-01T00:%02d:00Z".format(i % 60), null)
        private fun detail(ref: IssueRef, comments: List<IssueComment> = emptyList(), cursor: String? = null, events: List<IssueEvent> = emptyList()) = IssueDetail(
            IssueSummary(ref, "github.com", "#${ref.key}", "Title", "https://github.com/o/r/issues/${ref.key}", IssueActor("a"), IssueState(true, "Open"),
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, emptyList(), emptyList(), comments.size, "Project"),
            true, IssueCapabilities(), IssueViewerPermissions(), emptyList(), null, "body", comments, cursor, events, emptyList(),
        )
    }

    private fun CoroutineScope.controller(fake: Fake, clock: () -> Long = { 0L }) = IssueDetailController(this, { fake }, { fake.state.value }, clock)

    @Test fun reusesFreshDetailAndRefreshesExpiredOne() = runBlocking {
        val fake = Fake(); var clock = 0L
        val controller = controller(fake) { clock }
        controller.open("e1", ref(), key()); yield()
        assertEquals(1, fake.details.size)
        assertEquals(listOf(key()), fake.linked)
        assertEquals(1, controller.state.value.linkedThreads.size)
        controller.open("e1", ref(), key()); yield()
        clock = 29_999
        controller.open("e1", ref(), key()); yield()
        assertEquals(1, fake.details.size)
        assertFalse(controller.state.value.loading || controller.state.value.refreshing)
        assertNotNull(controller.state.value.detail)
        clock = 30_000
        controller.open("e1", ref(), key())
        assertTrue(controller.state.value.refreshing); assertNotNull(controller.state.value.detail)
        yield()
        assertEquals(2, fake.details.size)
        assertFalse(controller.state.value.refreshing)
    }

    @Test fun cacheIsPerEnvironmentAndEvictsLeastRecentlyUsed() = runBlocking {
        val fake = Fake()
        val controller = controller(fake)
        controller.open("e1", ref(1), key(1)); yield()
        controller.open("e2", ref(1), key(1)); yield()
        assertEquals(2, fake.details.size)
        for (n in 2..8) { controller.open("e1", ref(n), key(n)); yield() }
        // e1/1 is still the oldest of nine: opening it again must reach the server, then 8 later ones stay cached.
        assertEquals(9, fake.details.size)
        controller.open("e1", ref(1), key(1)); yield()
        assertEquals(10, fake.details.size)
        controller.open("e1", ref(8), key(8)); yield()
        assertEquals(10, fake.details.size)
    }

    @Test fun olderCommentsPrependDedupedAndPageThroughCursor() = runBlocking {
        val fake = Fake()
        fake.detailAnswer = { detail(it, listOf(comment(5), comment(6)), "c1") }
        fake.commentsAnswer = { IssueCommentsPage(listOf(comment(3), comment(4), comment(5)), if (it == "c1") "c2" else null) }
        val controller = controller(fake)
        controller.open("e1", ref(), key()); yield()
        controller.loadOlderComments(); yield()
        assertEquals(listOf("c3", "c4", "c5", "c6"), controller.state.value.detail!!.comments.map { it.id })
        assertEquals("c2", controller.state.value.detail!!.commentsCursor)
        controller.loadOlderComments(); yield()
        assertEquals(listOf("c1"), listOf(fake.pages.first()))
        assertEquals(listOf("c1", "c2"), fake.pages)
        assertEquals(listOf("c3", "c4", "c5", "c6"), controller.state.value.detail!!.comments.map { it.id })
        assertNull(controller.state.value.detail!!.commentsCursor)
        controller.loadOlderComments(); yield()
        assertEquals(2, fake.pages.size)
        // The merged comments survive a cached reopen.
        controller.open("e1", ref(), key()); yield()
        assertEquals(4, controller.state.value.detail!!.comments.size)
    }

    @Test fun olderCommentFailureKeepsCommentsAndCursor() = runBlocking {
        val fake = Fake()
        fake.detailAnswer = { detail(it, listOf(comment(5)), "c1") }
        fake.commentsAnswer = { throw IssueException(IssueFailure.TimedOut, "secret detail") }
        val controller = controller(fake)
        controller.open("e1", ref(), key()); yield()
        controller.loadOlderComments(); yield()
        val state = controller.state.value
        assertFalse(state.loadingOlder)
        assertEquals("The request timed out. Try again.", state.olderFailure)
        assertEquals("c1", state.detail!!.commentsCursor)
        assertEquals(1, state.detail!!.comments.size)
        assertNull(state.failure)
    }

    @Test fun commentsCapAtFiveHundredAndStopPaging() = runBlocking {
        val fake = Fake()
        fake.detailAnswer = { detail(it, (300 until 500).map(::comment), "c1") }
        fake.commentsAnswer = { IssueCommentsPage((100 until 300).map(::comment), "c2") }
        val controller = controller(fake)
        controller.open("e1", ref(), key()); yield()
        assertFalse(controller.state.value.commentsCapped)
        controller.loadOlderComments(); yield()
        assertEquals(400, controller.state.value.detail!!.comments.size)
        assertFalse(controller.state.value.commentsCapped)
        fake.commentsAnswer = { IssueCommentsPage((0 until 100).map(::comment) + (100 until 300).map(::comment), "c3") }
        controller.loadOlderComments(); yield()
        assertEquals(500, controller.state.value.detail!!.comments.size)
        assertTrue(controller.state.value.commentsCapped)
        assertEquals("c499", controller.state.value.detail!!.comments.last().id)
        assertEquals("c0", controller.state.value.detail!!.comments.first().id)
        controller.loadOlderComments(); yield()
        assertEquals(2, fake.pages.size)
        // No older page left means full is not capped.
        assertEquals(Pair(500, false), IssueDetailController.capComments((0 until 500).map(::comment), null).let { it.first.size to it.second })
        assertTrue(IssueDetailController.capComments((0 until 501).map(::comment), null).second)
    }

    @Test fun linkedThreadsOnlyWhenSupportedAndRefreshable() = runBlocking {
        val fake = Fake()
        fake.state.value = fake.state.value.copy(issueLinkedThreadsSupported = false)
        val controller = controller(fake)
        controller.open("e1", ref(), key()); yield()
        assertTrue(fake.linked.isEmpty())
        assertFalse(controller.state.value.linkedThreadsSupported)
        controller.refreshLinkedThreads(); yield()
        assertTrue(fake.linked.isEmpty())
        fake.state.value = fake.state.value.copy(issueLinkedThreadsSupported = true)
        controller.refreshLinkedThreads(); yield()
        assertEquals(1, fake.linked.size)
        assertEquals(1, controller.state.value.linkedThreads.size)
        assertTrue(controller.state.value.linkedThreadsSupported)
        fake.linkedAnswer = { throw IssueException(IssueFailure.Failed, "x") }
        controller.refreshLinkedThreads(); yield()
        assertEquals(1, controller.state.value.linkedThreads.size)
        assertNull(controller.state.value.failure)
    }

    @Test fun anOlderLinkedReadNeverOverwritesANewerRefresh() = runBlocking {
        val fake = Fake()
        val gate = CompletableDeferred<Unit>()
        val old = listOf(IssueLinkedThread("old", "p1", "Old", archived = false, settled = false))
        val fresh = listOf(IssueLinkedThread("new", "p1", "New", archived = false, settled = false))
        var calls = 0
        fake.linkedAnswer = { if (++calls == 1) { gate.await(); old } else fresh }
        val controller = controller(fake)
        controller.open("e1", ref(), key()); yield()
        // The load's own read is still waiting when a link command settles and asks again.
        controller.refreshLinkedThreads(); yield()
        assertEquals(listOf("new"), controller.state.value.linkedThreads.map { it.id })
        gate.complete(Unit); yield(); yield()
        assertEquals(listOf("new"), controller.state.value.linkedThreads.map { it.id })
        assertEquals(2, fake.linked.size)
    }

    @Test fun failuresMapToTypedMessagesAndNeverRetry() = runBlocking {
        val fake = Fake(); var clock = 1_000L
        val controller = controller(fake) { clock }
        fake.detailAnswer = { throw IssueException(IssueFailure.RateLimited, "private title", retryAtMillis = 1_000L + 150_000) }
        controller.open("e1", ref(), key()); yield()
        var s = controller.state.value
        assertEquals(IssueFailure.RateLimited, s.failure)
        assertEquals("The tracker is rate limiting requests. Try again in 3 min.", s.failureMessage)
        assertNull(s.detail); assertFalse(s.loading)
        assertEquals(1, fake.details.size)
        yield(); assertEquals(1, fake.details.size)
        fake.detailAnswer = { throw IssueException(IssueFailure.NotFound, "private title") }
        controller.retry(); yield()
        s = controller.state.value
        assertEquals(IssueFailure.NotFound, s.failure)
        assertFalse(s.failureMessage!!.contains("private"))
        fake.detailAnswer = { throw IllegalStateException("boom") }
        controller.retry(); yield()
        assertEquals(IssueFailure.Failed, controller.state.value.failure)
        fake.detailAnswer = { detail(it) }
        controller.retry(); yield()
        assertNull(controller.state.value.failure); assertNotNull(controller.state.value.detail)
        // A failed refresh keeps the held detail.
        fake.detailAnswer = { throw IssueException(IssueFailure.Unavailable, "x") }
        controller.refresh(); yield()
        s = controller.state.value
        assertNotNull(s.detail); assertEquals(IssueFailure.Unavailable, s.failure)
        assertEquals("The tracker is rate limiting requests. You can try again now.",
            IssueDetailController.issueFailureMessage(IssueFailure.RateLimited, 500L, 1_000L))
        assertEquals("The tracker is rate limiting requests. Try again later.",
            IssueDetailController.issueFailureMessage(IssueFailure.RateLimited, null, 1_000L))
    }

    @Test fun disconnectedBlocksReadsAndNeverLoadsAnUncachedDetail() = runBlocking {
        val fake = Fake()
        val controller = controller(fake)
        controller.open("e1", ref(1), key(1)); yield()
        fake.state.value = fake.state.value.copy(connection = ConnectionStatus.Disconnected)
        controller.syncLive()
        assertFalse(controller.state.value.live)
        controller.refresh(); controller.retry(); controller.loadOlderComments(); controller.refreshLinkedThreads(); yield()
        assertEquals(1, fake.details.size); assertTrue(fake.pages.isEmpty()); assertEquals(1, fake.linked.size)
        assertNotNull(controller.state.value.detail)
        controller.open("e1", ref(2), key(2)); yield()
        val s = controller.state.value
        assertEquals(1, fake.details.size)
        assertNull(s.detail); assertEquals(IssueFailure.NotConnected, s.failure); assertEquals("Connect to view this issue", s.failureMessage)
        assertFalse(s.live); assertFalse(s.loading)
        // A cached detail opens without reads, even when expired.
        controller.open("e1", ref(1), key(1)); yield()
        assertNotNull(controller.state.value.detail); assertFalse(controller.state.value.live); assertEquals(1, fake.details.size)
        // Back online: retry reads again.
        fake.state.value = fake.state.value.copy(connection = ConnectionStatus.Connected)
        controller.refresh(); yield()
        assertEquals(2, fake.details.size); assertTrue(controller.state.value.live)
    }

    @Test fun resetDropsEverythingAndIgnoresStaleReply() = runBlocking {
        val fake = Fake()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.detailAnswer = { started.complete(Unit); withContext(NonCancellable) { release.await() }; detail(it, listOf(comment(1))) }
        val controller = controller(fake)
        controller.open("e1", ref(), key())
        started.await()
        assertTrue(controller.state.value.loading)
        controller.reset()
        assertEquals(IssueDetailUiState(), controller.state.value)
        release.complete(Unit); yield(); yield()
        assertEquals(IssueDetailUiState(), controller.state.value)
        assertTrue(fake.linked.isEmpty())
        // The cache went with the reset.
        fake.detailAnswer = { detail(it) }
        controller.open("e1", ref(), key()); yield()
        assertEquals(2, fake.details.size)
    }

    @Test fun staleOlderReplyAfterResetIsIgnored() = runBlocking {
        val fake = Fake()
        fake.detailAnswer = { detail(it, listOf(comment(5)), "c1") }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.commentsAnswer = { started.complete(Unit); withContext(NonCancellable) { release.await() }; IssueCommentsPage(listOf(comment(1)), null) }
        val controller = controller(fake)
        controller.open("e1", ref(), key()); yield()
        controller.loadOlderComments(); started.await()
        assertTrue(controller.state.value.loadingOlder)
        controller.reset()
        release.complete(Unit); yield(); yield()
        assertEquals(IssueDetailUiState(), controller.state.value)
    }

    @Test fun openingAnotherIssueIgnoresTheOldReply() = runBlocking {
        val fake = Fake()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.detailAnswer = { r -> if (r.key == "1") { started.complete(Unit); withContext(NonCancellable) { release.await() } }; detail(r) }
        val controller = controller(fake)
        controller.open("e1", ref(1), key(1)); started.await()
        controller.open("e1", ref(2), key(2)); yield()
        release.complete(Unit); yield(); yield()
        assertEquals("2", controller.state.value.detail!!.summary.ref.key)
        assertEquals(ref(2), controller.state.value.ref)
    }

    @Test fun activityMergesCommentsAndEventsOldestFirst() {
        val events = listOf(
            IssueEvent("e1", IssueEventKind.Labeled, IssueActor("bob"), "2026-01-01T00:03:30Z", "bug", null),
            IssueEvent("e2", IssueEventKind.Closed, null, "2026-01-01T00:09:00Z", null, null),
        )
        val d = detail(ref(), listOf(comment(1), comment(2), comment(5)), events = events)
        assertEquals(listOf("c:c1", "c:c2", "e:e1", "c:c5", "e:e2"), issueActivity(d).map { it.id })
        assertTrue(issueActivity(d)[2] is IssueActivityItem.Event)
        assertEquals("bob added the label bug", issueEventText(events[0]))
        assertEquals("Someone closed this", issueEventText(events[1]))
    }
}
