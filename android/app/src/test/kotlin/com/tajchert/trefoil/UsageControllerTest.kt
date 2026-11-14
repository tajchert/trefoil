package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class UsageControllerTest {
    private class Fake : TrefoilClient {
        override val state = MutableStateFlow(ClientState())
        val calls = mutableListOf<Pair<UsageRange, Boolean>>()
        val limits = mutableListOf<Boolean>()
        var answer: suspend (UsageRange, Boolean) -> UsageReport = { range, await -> report(range, !await) }
        override suspend fun usageReport(range: UsageRange, timeZone: String, awaitRefresh: Boolean): UsageReport { calls += range to awaitRefresh; return answer(range, awaitRefresh) }
        override suspend fun refreshUsageLimits(automatic: Boolean): Boolean { limits += automatic; return true }
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = error("unused")
        override suspend fun connect(environment: SavedEnvironment) {}
        override suspend fun disconnect() {}
        override suspend fun selectThread(threadId: String) {}
        override suspend fun sendMessage(text: String) {}
        override suspend fun interruptRun() {}
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) {}
        override suspend fun reconnect() {}
        override fun close() {}
    }
    companion object {
        private fun report(range: UsageRange, refreshing: Boolean = false) = UsageReport("fixture", range, "2026-01-01", "2026-01-01", "UTC", "now", refreshing = refreshing)
    }
    @Test fun provisionalAndCacheAndManualRefresh() = runBlocking {
        val fake = Fake(); val controller = UsageController(this, { fake }, { "UTC" })
        controller.open(); yield()
        assertEquals(listOf(false, true), fake.calls.map { it.second })
        val first = controller.state.value.range
        val other = UsageRange.entries.first { it != first }
        controller.selectRange(other); yield(); controller.selectRange(first); yield()
        assertEquals(4, fake.calls.size)
        controller.refresh(); yield()
        assertEquals(listOf(true, false), fake.limits)
        assertTrue(fake.calls.last().second)
    }
    @Test fun failureKeepsReportAndResetCancelsLoad() = runBlocking {
        val fake = Fake(); fake.answer = { range, _ -> report(range) }
        val controller = UsageController(this, { fake })
        controller.open(); yield()
        val previous = controller.state.value.report
        fake.answer = { _, _ -> throw UsageException(UsageFailure.TimedOut, "timeout") }
        controller.retry(); yield()
        assertEquals(previous, controller.state.value.report)
        assertEquals(UsageFailure.TimedOut, controller.state.value.failure)
        val started = CompletableDeferred<Unit>(); var cancelled = false
        fake.answer = { _, _ -> try { started.complete(Unit); awaitCancellation() } finally { cancelled = true } }
        controller.retry(); started.await(); controller.reset(); yield()
        assertTrue(cancelled); assertEquals(com.tajchert.trefoil.ui.UsageUiState(), controller.state.value)
    }
}
