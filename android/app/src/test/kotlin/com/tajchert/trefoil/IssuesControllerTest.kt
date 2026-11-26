package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IssuesControllerTest {
    private class Fake : TrefoilClient {
        val environment = SavedEnvironment("e1", "Machine", "http://10.0.0.2:3773", "token")
        override val state = MutableStateFlow(ClientState(environment = environment, connection = ConnectionStatus.Connected, issuesSupported = true))
        class Call(val query: IssueQuery, val cursors: Map<String, String>?, val refresh: Boolean)
        val calls = mutableListOf<Call>()
        var answer: suspend (Call) -> IssueListPage = { IssueListPage(emptyList(), emptyList(), emptyList(), emptyMap(), emptyList()) }
        override suspend fun issuesList(query: IssueQuery, cursors: Map<String, String>?, limit: Int, refresh: Boolean): IssueListPage {
            val call = Call(query, cursors, refresh)
            calls += call
            return answer(call)
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

    companion object {
        private fun summary(n: Int, updated: String = "2026-01-02T00:00:00Z") = IssueSummary(
            IssueRef("p1", "github", "github.com", "o/r", n.toString()), "github.com", "#$n", "Title $n", "https://github.com/o/r/issues/$n", IssueActor("a"),
            IssueState(true, "Open"), "2026-01-01T00:00:00Z", updated, null, emptyList(), emptyList(), 0, "Project",
        )
        private fun page(rows: List<IssueSummary>, cursors: Map<String, String> = emptyMap(), trackers: List<IssueTrackerStatus> = emptyList(),
            scopes: List<IssueScope> = emptyList()) = IssueListPage(rows, trackers, emptyList(), cursors, scopes)
        /** Distinct, strictly decreasing update times so ordering is predictable. */
        private fun rows(range: IntRange) = range.map { n -> summary(n, java.time.Instant.parse("2026-02-01T00:00:00Z").minusSeconds(n.toLong()).toString()) }
    }

    private fun TestScope.controller(fake: Fake, clock: () -> Long = { testScheduler.currentTime }) = IssuesController(this, { fake }, { fake.state.value }, clock)

    @Test fun opensWithTheDefaultQueryAndShowsRows() = runTest {
        val fake = Fake(); fake.answer = { page(listOf(summary(1), summary(2))) }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        assertEquals(IssueQuery(), fake.calls.single().query)
        assertEquals(IssueListState.Open, c.state.value.query.state)
        assertEquals(IssueInvolvement.All, c.state.value.query.involvement)
        assertEquals(2, c.state.value.entries.size)
        assertFalse(c.state.value.loading)
        assertNull(fake.calls.single().cursors)
        assertFalse(fake.calls.single().refresh)
    }

    @Test fun searchTextIsDebouncedAndOnlyTheLastTextLoads() = runTest {
        val fake = Fake(); val c = controller(fake)
        c.open(); advanceUntilIdle(); fake.calls.clear()
        c.setQuery(IssueQuery(text = "b")); advanceTimeBy(100)
        c.setQuery(IssueQuery(text = "bu")); advanceTimeBy(100)
        c.setQuery(IssueQuery(text = "bug")); advanceTimeBy(299)
        runCurrent()
        assertTrue(fake.calls.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("bug"), fake.calls.map { it.query.text })
        advanceUntilIdle()
    }

    @Test fun filterChangeCancelsTheRunningLoadAndStartsPageOne() = runTest {
        val fake = Fake(); val gate = CompletableDeferred<IssueListPage>()
        val c = controller(fake)
        c.open(); advanceUntilIdle(); fake.calls.clear()
        fake.answer = { call -> if (call.query.state == IssueListState.Open) gate.await() else page(listOf(summary(9))) }
        c.setQuery(IssueQuery(sort = IssueSort.Created)); runCurrent()
        // The open-state query is superseded before it answers.
        c.setQuery(IssueQuery(state = IssueListState.Closed)); advanceUntilIdle()
        gate.complete(page(listOf(summary(1))))
        advanceUntilIdle()
        assertEquals(IssueListState.Closed, c.state.value.query.state)
        assertEquals(listOf("9"), c.state.value.entries.map { it.ref.key })
        assertEquals(2, fake.calls.size)
        assertNull(fake.calls.last().cursors)
    }

    @Test fun loadMoreStopsWithoutCursors() = runTest {
        val fake = Fake(); fake.answer = { page(rows(1..3)) }
        val c = controller(fake)
        c.open(); advanceUntilIdle(); fake.calls.clear()
        c.loadMore(); advanceUntilIdle()
        assertTrue(fake.calls.isEmpty())
        assertFalse(c.state.value.canLoadMore)
    }

    @Test fun loadMoreFollowsCursorsAndDedupesRows() = runTest {
        val fake = Fake()
        fake.answer = { call -> if (call.cursors == null) page(rows(1..3), mapOf("p1" to "c1")) else page(rows(3..5)) }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals(mapOf("p1" to "c1"), fake.calls.last().cursors)
        assertEquals(setOf("1", "2", "3", "4", "5"), c.state.value.entries.map { it.ref.key }.toSet())
        assertEquals(5, c.state.value.entries.size)
        assertTrue(c.state.value.nextCursors.isEmpty())
        assertFalse(fake.calls.last().refresh)
    }

    @Test fun failedMoreLoadStopsPaginationUntilRetry() = runTest {
        val fake = Fake(); var fail = true
        fake.answer = { call ->
            when {
                call.cursors == null -> page(rows(1..3), mapOf("p1" to "c1"))
                fail -> throw IssueException(IssueFailure.TimedOut, "slow")
                else -> page(rows(4..5))
            }
        }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals("The request timed out. Try again.", c.state.value.moreFailure)
        assertEquals(3, c.state.value.entries.size)
        val calls = fake.calls.size
        c.loadMore(); c.loadMore(); advanceUntilIdle()
        assertEquals("No automatic retry", calls, fake.calls.size)
        fail = false
        c.retry(); advanceUntilIdle()
        assertEquals(5, c.state.value.entries.size)
        assertNull(c.state.value.moreFailure)
    }

    @Test fun rowCapReplacesLoadMore() = runTest {
        val fake = Fake()
        fake.answer = { call ->
            when (call.cursors?.get("p1")) {
                null -> page(rows(1..400), mapOf("p1" to "a"))
                "a" -> page(rows(401..800), mapOf("p1" to "b"))
                else -> page(rows(801..1200), mapOf("p1" to "c"))
            }
        }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals(IssuesController.MAX_ROWS, c.state.value.entries.size)
        assertTrue(c.state.value.capped)
        assertFalse(c.state.value.canLoadMore)
        val calls = fake.calls.size
        c.loadMore(); advanceUntilIdle()
        assertEquals(calls, fake.calls.size)
    }

    @Test fun reusesAFreshAnswerAndReadsAgainWhenOld() = runTest {
        val fake = Fake(); var clock = 0L; fake.answer = { page(listOf(summary(1))) }
        val c = controller(fake) { clock }
        c.open(); advanceUntilIdle()
        c.setQuery(IssueQuery(state = IssueListState.Closed)); advanceUntilIdle()
        assertEquals(2, fake.calls.size)
        clock = 10_000
        c.setQuery(IssueQuery()); advanceUntilIdle()
        assertEquals("fresh answer is reused", 2, fake.calls.size)
        assertEquals(1, c.state.value.entries.size)
        clock = 40_000
        c.open(); advanceUntilIdle()
        assertEquals("answer older than 30 s is read again", 3, fake.calls.size)
    }

    @Test fun pullToRefreshSendsRefreshOnTheFirstPageOnly() = runTest {
        val fake = Fake()
        fake.answer = { call -> if (call.cursors == null) page(rows(1..3), mapOf("p1" to "c1")) else page(rows(4..5)) }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        c.refresh(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals(listOf(false, true, false), fake.calls.map { it.refresh })
    }

    @Test fun resetDropsStateAndIgnoresALateReply() = runTest {
        val fake = Fake(); val gate = CompletableDeferred<IssueListPage>(); fake.answer = { gate.await() }
        val c = controller(fake)
        c.open(); runCurrent()
        assertTrue(c.state.value.loading)
        c.reset()
        gate.complete(page(listOf(summary(1)))); advanceUntilIdle()
        assertEquals(IssueListUiState(), c.state.value)
        assertTrue(c.scopesFor("e1").isEmpty())
    }

    @Test fun remembersScopesAndTrackersPerEnvironment() = runTest {
        val fake = Fake()
        val scopes = listOf(IssueScope("p1", "github", "github.com", "o/r"))
        val trackers = listOf(IssueTrackerStatus("github", "github.com", 1, true, null))
        fake.answer = { page(listOf(summary(1)), scopes = scopes, trackers = trackers) }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        assertEquals(scopes, c.scopesFor("e1"))
        assertEquals(trackers, c.trackersFor("e1"))
        assertTrue(c.scopesFor("other").isEmpty())
    }

    @Test fun failedFirstPageKeepsHeldRowsAndReportsTheFailure() = runTest {
        val fake = Fake(); var fail = false
        fake.answer = { if (fail) throw IssueException(IssueFailure.Unavailable, "down") else page(listOf(summary(1))) }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        fail = true
        c.refresh(); advanceUntilIdle()
        assertEquals(1, c.state.value.entries.size)
        assertEquals(IssueFailure.Unavailable, c.state.value.failure)
        assertFalse(c.state.value.refreshing)
    }

    @Test fun disconnectedOpenShowsNotConnectedAndReadsNothing() = runTest {
        val fake = Fake(); fake.state.value = fake.state.value.copy(connection = ConnectionStatus.Reconnecting)
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        assertTrue(fake.calls.isEmpty())
        assertEquals(IssueFailure.NotConnected, c.state.value.failure)
        assertFalse(c.state.value.live)
    }

    @Test fun heldRowsStayVisibleAndReadsStopWhileNotLive() = runTest {
        val fake = Fake(); fake.answer = { page(rows(1..3), mapOf("p1" to "c1")) }
        val c = controller(fake)
        c.open(); advanceUntilIdle()
        fake.state.value = fake.state.value.copy(connection = ConnectionStatus.Reconnecting)
        c.syncLive(); fake.calls.clear()
        c.refresh(); c.loadMore(); advanceUntilIdle()
        assertTrue(fake.calls.isEmpty())
        assertTrue(c.state.value.stale)
        assertEquals(3, c.state.value.entries.size)
    }
}
