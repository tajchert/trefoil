package com.tajchert.trefoil.ui.pr

import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PullRequestKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestSenderTest {
    private val a = PullRequestKey("p", "github.com", "demo/repo", 1)
    private val b = PullRequestKey("p", "github.com", "demo/repo", 2)

    private class Fixture(val scope: TestScope) {
        val actions = mutableListOf<Triple<PullRequestKey, PrAction, PrMergeMethod?>>()
        val comments = mutableListOf<Pair<PullRequestKey, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        val sender = PullRequestSender(
            scope,
            sendAction = { k, action, method -> actions += Triple(k, action, method); gate?.await(); failure?.let { throw it } },
            sendComment = { k, body -> comments += k to body; gate?.await(); failure?.let { throw it } },
        )
    }

    private fun fixture() = Fixture(TestScope(StandardTestDispatcher()))

    @Test fun `a second tap while the first send is in flight is dropped`() {
        val f = fixture()
        f.gate = CompletableDeferred()
        assertTrue(f.sender.run(a, PrAction.Merge, PrMergeMethod.Squash))
        assertTrue(a in f.sender.busy.value)
        // Double-tapping Merge confirm, Update branch and Comment before the first send has even started.
        assertFalse(f.sender.run(a, PrAction.Merge, PrMergeMethod.Squash))
        assertFalse(f.sender.run(a, PrAction.UpdateBranch, null))
        assertFalse(f.sender.comment(a, "hello"))
        f.scope.runCurrent()
        assertEquals(1, f.actions.size)
        assertTrue(f.comments.isEmpty())
        assertTrue("busy stays set while the first send is in flight", a in f.sender.busy.value)

        f.gate!!.complete(Unit)
        f.scope.advanceUntilIdle()
        assertTrue(f.sender.busy.value.isEmpty())
        assertEquals(1, f.actions.size)
    }

    @Test fun `a dropped second send cannot re-enable the first one's busy flag`() {
        val f = fixture()
        f.gate = CompletableDeferred()
        f.sender.comment(a, "first")
        f.sender.comment(a, "second")
        f.scope.runCurrent()
        assertTrue(a in f.sender.busy.value)
        assertEquals(listOf(a to "first"), f.comments)
    }

    @Test fun `different pull requests send independently and key is free again after completion`() {
        val f = fixture()
        assertTrue(f.sender.run(a, PrAction.Draft, null))
        assertTrue(f.sender.run(b, PrAction.Draft, null))
        f.scope.advanceUntilIdle()
        assertEquals(2, f.actions.size)
        assertTrue(f.sender.run(a, PrAction.Ready, null))
        f.scope.advanceUntilIdle()
        assertEquals(3, f.actions.size)
        assertTrue(f.sender.busy.value.isEmpty())
    }

    @Test fun `a refusal before sending is kept per pull request and cleared by the next send`() {
        val f = fixture()
        f.failure = IllegalStateException("Reconnect before changing this pull request")
        f.sender.run(a, PrAction.Merge, PrMergeMethod.Merge)
        f.scope.advanceUntilIdle()
        assertEquals("Reconnect before changing this pull request", f.sender.errors.value[a])
        assertNull(f.sender.errors.value[b])
        assertTrue(f.sender.busy.value.isEmpty())

        f.failure = null
        f.sender.run(a, PrAction.Merge, PrMergeMethod.Merge)
        assertNull("starting a send clears the earlier refusal", f.sender.errors.value[a])
        f.scope.advanceUntilIdle()
        assertNull(f.sender.errors.value[a])
    }

    @Test fun `a blank failure message falls back to a plain sentence and dismiss clears it`() {
        val f = fixture()
        f.failure = IllegalStateException("")
        f.sender.comment(a, "x")
        f.scope.advanceUntilIdle()
        assertEquals("The comment could not be sent.", f.sender.errors.value[a])
        f.sender.dismissError(a)
        assertTrue(f.sender.errors.value.isEmpty())
    }

    @Test fun `the send runs on the given scope and does not depend on any UI scope`() {
        val f = fixture()
        f.gate = CompletableDeferred()
        f.sender.run(a, PrAction.Merge, PrMergeMethod.Merge)
        f.scope.runCurrent()
        // Nothing about the sender is tied to a caller: dropping every reference to a "panel" leaves the send running to completion.
        f.gate!!.complete(Unit)
        f.scope.advanceUntilIdle()
        assertEquals(1, f.actions.size)
        assertTrue(f.sender.busy.value.isEmpty())
    }

    @Test fun `shouldRefreshOnLive refreshes only on a not-live to live transition`() {
        assertFalse("first entry while live is already covered by the panel read", shouldRefreshOnLive(wasLive = true, live = true))
        assertTrue(shouldRefreshOnLive(wasLive = false, live = true))
        assertFalse(shouldRefreshOnLive(wasLive = true, live = false))
        assertFalse(shouldRefreshOnLive(wasLive = false, live = false))
    }
}
