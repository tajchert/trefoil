package com.tajchert.trefoil

import androidx.compose.material3.SnackbarHostState
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.core.session.IssueLinkNotice
import com.tajchert.trefoil.ui.issues.showIssueLinkNotice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IssueStartControllerTest {
    private val key = ComposerDraftKey.NewThread("e1", "p1")
    private fun state(canWrite: Boolean = true, connection: ConnectionStatus = ConnectionStatus.Connected) = ClientState(
        connection = connection, issuesSupported = true, canWriteThreads = canWrite,
        environment = SavedEnvironment("e1", "Mac", "http://10.0.0.2:3773", "token"), projects = listOf(Project("p1", "Project", "/p")),
    )
    private fun summary(title: String = "Fix the crash") = IssueSummary(
        IssueRef("p1", "github", "github.com", "o/r", "7"), "github.com", "#7", title, "https://github.com/o/r/issues/7",
        IssueActor("a"), IssueState(true, "Open"), "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, emptyList(), emptyList(), 0, "Project",
    )

    private class Drafts(var text: String? = null) {
        val writes = mutableListOf<String>()
        fun write(key: ComposerDraftKey, value: String) { writes += value; text = value }
    }

    private fun controller(scope: CoroutineScope, drafts: Drafts, state: ClientState = state(), awaitLoaded: suspend () -> Unit = {}) =
        IssueStartController(scope, { state }, { drafts.text }, drafts::write, awaitLoaded)

    private suspend fun settle() = repeat(6) { yield() }

    @Test fun templateIsKeyTitleAndUrlWithoutTheBody() {
        assertEquals("#7: Fix the crash\nhttps://github.com/o/r/issues/7\n\n", issueStartTemplate(summary()))
    }

    @Test fun anEmptyDraftIsPrefilledAndTheSheetOpensOnTheIssuesProject() = runBlocking {
        val drafts = Drafts(text = "  ")
        val c = controller(this, drafts)
        c.start("e1", summary()); settle()
        assertEquals(listOf("#7: Fix the crash\nhttps://github.com/o/r/issues/7\n\n"), drafts.writes)
        val request = c.request.value!!
        assertFalse(request.askReplace)
        assertEquals("p1", request.projectId)
        assertEquals("e1", request.environmentId)
    }

    @Test fun aDraftWithTextIsNeverOverwrittenWithoutAskingAndKeepKeepsIt() = runBlocking {
        val drafts = Drafts(text = "my own prompt")
        val c = controller(this, drafts)
        c.start("e1", summary()); settle()
        assertTrue(c.request.value!!.askReplace)
        assertTrue(drafts.writes.isEmpty())
        c.keep()
        assertFalse(c.request.value!!.askReplace)
        assertTrue(drafts.writes.isEmpty())
        assertEquals("my own prompt", drafts.text)
        // The kept draft still carries the link.
        assertEquals(issueLinkFor(summary()), c.take("e1"))
        assertNull(c.request.value)
    }

    @Test fun replaceWritesTheTemplateOnlyAfterTheUserChoosesIt() = runBlocking {
        val drafts = Drafts(text = "my own prompt")
        val c = controller(this, drafts)
        c.start("e1", summary()); settle()
        assertNull("nothing opens before the answer", c.request.value?.takeUnless { it.askReplace })
        c.replace()
        assertEquals(listOf(issueStartTemplate(summary())), drafts.writes)
        assertFalse(c.request.value!!.askReplace)
    }

    @Test fun theStartedDraftCarriesTheIssueLinkOnce() = runBlocking {
        val c = controller(this, Drafts())
        c.start("e1", summary()); settle()
        val link = c.take("e1")!!
        assertEquals(IssueKey("github", "github.com", "o/r", "7"), link.key)
        assertEquals("manual", link.source)
        assertEquals("p1", link.projectId)
        assertEquals("#7", link.displayKey)
        assertNull("a second draft must not carry it", c.take("e1"))
    }

    @Test fun aRequestOfAnotherMachineOrAPendingQuestionDoesNotLink() = runBlocking {
        val drafts = Drafts(text = "x")
        val c = controller(this, drafts)
        c.start("e1", summary()); settle()
        assertNull("the question is still open", c.take("e1"))
        val other = controller(this, Drafts())
        other.start("e1", summary()); settle()
        assertNull(other.take("e2"))
    }

    @Test fun cancelDropsTheRequestAndTheLink() = runBlocking {
        val c = controller(this, Drafts())
        c.start("e1", summary()); settle()
        c.cancel()
        assertNull(c.request.value)
        assertNull(c.take("e1"))
    }

    @Test fun repeatTapsWhileOneIsPendingStartOnlyOnce() = runBlocking {
        val drafts = Drafts()
        val gate = CompletableDeferred<Unit>()
        val c = controller(this, drafts, awaitLoaded = { gate.await() })
        c.start("e1", summary()); c.start("e1", summary()); settle()
        assertNull("drafts have not loaded: nothing may be judged empty", c.request.value)
        gate.complete(Unit); settle()
        c.start("e1", summary()); settle()
        assertEquals(1, drafts.writes.size)
    }

    @Test fun startIsOfferedOnlyWhenEveryRuleHolds() {
        val s = state()
        assertTrue(canStartThreadFromIssue("e1", summary(), s))
        assertFalse(canStartThreadFromIssue("e1", null, s))
        assertFalse(canStartThreadFromIssue(null, summary(), s))
        assertFalse(canStartThreadFromIssue("e2", summary(), s))
        assertFalse(canStartThreadFromIssue("e1", summary(), state(canWrite = false)))
        assertFalse(canStartThreadFromIssue("e1", summary(), state(connection = ConnectionStatus.Reconnecting)))
        assertFalse(canStartThreadFromIssue("e1", summary(), s.copy(issuesSupported = false)))
        assertFalse(canStartThreadFromIssue("e1", summary(), s.copy(projects = emptyList())))
        assertFalse(canStartThreadFromIssue("e1", summary(title = " "), s))
    }

    @Test fun nothingStartsWhenTheStateChangedWhileDraftsLoaded() = runBlocking {
        val drafts = Drafts()
        var current = state()
        val c = IssueStartController(this, { current }, { drafts.text }, drafts::write, { current = state(canWrite = false) })
        c.start("e1", summary()); settle()
        assertNull(c.request.value)
        assertTrue(drafts.writes.isEmpty())
    }

    @Test fun theNoticeActionRetriesOnceAndDismissingClearsIt() = runBlocking {
        val notice = IssueLinkNotice("t1", "e1", issueLinkFor(summary())!!)
        val snackbar = SnackbarHostState()
        var retries = 0; var dismissals = 0
        val job = launch { showIssueLinkNotice(snackbar, notice, { retries++ }, { dismissals++ }) }
        settle()
        assertEquals(notice.message, snackbar.currentSnackbarData!!.visuals.message)
        assertEquals("Link issue", snackbar.currentSnackbarData!!.visuals.actionLabel)
        snackbar.currentSnackbarData!!.performAction(); job.join()
        assertEquals(1, retries); assertEquals(0, dismissals)

        val second = launch { showIssueLinkNotice(snackbar, notice, { retries++ }, { dismissals++ }) }
        settle()
        snackbar.currentSnackbarData!!.dismiss(); second.join()
        assertEquals(1, retries); assertEquals(1, dismissals)

        // The notice going away by itself cancels the snackbar without counting as either answer.
        val third = launch { showIssueLinkNotice(snackbar, notice, { retries++ }, { dismissals++ }) }
        settle(); third.cancelAndJoin()
        assertEquals(1, retries); assertEquals(1, dismissals)
    }
}
