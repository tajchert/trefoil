package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrActionKind
import com.tajchert.trefoil.core.model.PrActionOutcome
import com.tajchert.trefoil.core.model.PrCheckStatus
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PrPanelState
import com.tajchert.trefoil.core.model.PrSnapshot
import com.tajchert.trefoil.core.model.PrState
import com.tajchert.trefoil.core.model.PrUnavailableReason
import com.tajchert.trefoil.core.model.PullRequestKey
import com.tajchert.trefoil.core.model.UncertainOutcomeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestSessionTest {
    private val key = PullRequestKey("project-1", "github.com", "acme/widgets", 7)
    private val otherKey = PullRequestKey("project-1", "github.com", "acme/widgets", 8)
    private val snapshot = PrSnapshot(state = PrState.Open, title = "Snapshot title", headRef = "feature", baseRef = "main")

    private class FakeTransport : PullRequestTransport {
        var live = true
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var handler: suspend (String, JsonObject) -> JsonElement = { tag, _ -> error("unexpected $tag") }
        var diffReply: suspend (JsonObject) -> JsonObject = { error("no diff") }
        val diffBodies = mutableListOf<JsonObject>()
        val ticks = MutableSharedFlow<Long>()
        var opens = 0
        var closes = 0

        override fun isLive() = live
        override suspend fun request(tag: String, payload: JsonObject): JsonElement { calls += tag to payload; return handler(tag, payload) }
        override fun refreshes(): Flow<Long> = ticks.onStart { opens++ }.onCompletion { closes++ }
        override suspend fun postDiff(body: JsonObject): JsonObject { diffBodies += body; return diffReply(body) }

        fun count(tag: String) = calls.count { it.first == tag }
    }

    private fun detailJson(
        title: String = "Fresh title",
        state: String = "open",
        isDraft: Boolean = false,
        headSha: String? = "abc123",
        capActions: List<String> = listOf("merge", "update-branch", "ready", "draft", "close"),
        permActions: List<String>? = listOf("merge", "update-branch", "ready", "draft", "close"),
        mergeMethods: List<String> = listOf("merge", "squash", "rebase", "fast-forward"),
        mergeCaps: Map<String, Boolean>? = mapOf("merge" to true, "squash" to true, "rebase" to false),
        comment: Boolean = true,
        permComment: Boolean? = true,
        behindBy: Int? = null,
    ): JsonObject = buildJsonObject {
        put("provider", "github")
        putJsonObject("capabilities") {
            put("diff", true); put("comment", comment); put("search", true)
            putJsonArray("actions") { capActions.forEach { add(it) } }
            putJsonArray("mergeMethods") { mergeMethods.forEach { add(it) } }
        }
        putJsonObject("viewerPermissions") {
            permActions?.let { list -> putJsonArray("actions") { list.forEach { add(it) } } }
            permComment?.let { put("comment", it) }
            put("resolve", true)
        }
        put("projectId", "project-1"); put("repository", "acme/widgets"); put("number", 7)
        put("title", title); put("body", "synthetic body"); put("url", "https://github.com/acme/widgets/pull/7")
        putJsonObject("author") { put("login", "octo"); put("name", JsonNull); put("avatarUrl", JsonNull) }
        put("state", state); put("isDraft", isDraft); put("mergeability", "mergeable")
        put("additions", 3); put("deletions", 1); put("changedFiles", 2)
        put("headBranch", "feature"); put("baseBranch", "main")
        headSha?.let { put("headSha", it) }
        behindBy?.let { put("behindBy", it) }
        put("updatedAt", "2026-11-26T10:00:00Z")
        putJsonArray("checks") { add(buildJsonObject { put("name", "embedded"); put("status", "pending"); put("description", JsonNull); put("url", JsonNull) }) }
        mergeCaps?.let { caps -> putJsonObject("mergeCapabilities") { caps.forEach { (k, v) -> put(k, v) } } }
    }

    private fun checksJson(vararg statuses: String) = buildJsonObject {
        put("state", "open")
        putJsonArray("checks") {
            statuses.forEachIndexed { i, status -> add(buildJsonObject { put("name", "check-$i"); put("status", status); put("description", JsonNull); put("url", JsonNull); put("required", true) }) }
        }
    }

    private fun unavailable(reason: String) = PrRpcFailure("pullRequests.detail", buildJsonObject { put("_tag", "PullRequestUnavailableError"); put("reason", reason) })
    private fun operation(detail: String) = PrRpcFailure("pullRequests.runAction", buildJsonObject { put("_tag", "PullRequestOperationError"); put("operation", "merge"); put("detail", detail) })

    /** Foreground work: `advanceUntilIdle` does not wait for `backgroundScope`. */
    private fun TestScope.workScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun TestScope.reads(transport: FakeTransport, checks: Boolean = true, maxEntries: Int = 24) =
        PullRequestReads(transport, workScope(), checksSupported = { checks }, now = { 1_000L }, parseDispatcher = UnconfinedTestDispatcher(testScheduler), maxEntries = maxEntries)

    private fun FakeTransport.answerDetail(detail: () -> JsonObject = { detailJson() }) {
        handler = { tag, _ -> when (tag) { "pullRequests.detail" -> detail(); "pullRequests.checks" -> checksJson("success"); else -> JsonNull } }
    }

    // --- reads ---

    @Test fun panelPaintsSnapshotThenCachedThenFreshDetail() = runTest {
        val transport = FakeTransport().apply { live = false }
        val reads = reads(transport)
        val first = reads.panel(key, snapshot)
        assertEquals(PrPanelState(snapshot = snapshot), first.value)
        assertTrue(transport.calls.isEmpty())

        transport.live = true
        transport.answerDetail { detailJson(title = "Cached title") }
        reads.refresh(key); advanceUntilIdle()
        assertEquals("Cached title", first.value.detail?.title)

        val gate = CompletableDeferred<JsonObject>()
        transport.handler = { tag, _ -> if (tag == "pullRequests.detail") gate.await() else checksJson("failure") }
        val seen = mutableListOf<PrPanelState>()
        val newer = snapshot.copy(title = "Newer snapshot")
        val flow = reads.panel(key, newer)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
        runCurrent()
        assertEquals(newer, seen.last().snapshot)
        assertEquals("Cached title", seen.last().detail?.title)
        assertTrue(seen.last().refreshing)

        gate.complete(detailJson(title = "Fresh title")); advanceUntilIdle()
        assertEquals("Fresh title", seen.last().detail?.title)
        assertFalse(seen.last().refreshing)
        assertEquals(PrCheckStatus.Failure, seen.last().checks?.single()?.status)
        assertEquals(1_000L, seen.last().lastUpdatedMillis)
        val titles = seen.map { it.detail?.title }.distinct()
        assertEquals(listOf("Cached title", "Fresh title"), titles)
    }

    @Test fun refreshTickRefetchesDetailAndChecksOncePerWatchedKey() = runTest {
        val transport = FakeTransport().apply { answerDetail() }
        val reads = reads(transport)
        val watched = reads.panel(key, snapshot)
        reads.panel(otherKey, null) // cached but not collected
        backgroundScope.launch { watched.collect {} }
        runCurrent(); advanceUntilIdle()
        transport.calls.clear()

        transport.ticks.emit(1); advanceUntilIdle()
        assertEquals(listOf("pullRequests.detail", "pullRequests.checks"), transport.calls.map { it.first })
        assertEquals(7, (transport.calls.first().second["number"] as JsonPrimitive).content.toInt())
        assertNull(transport.calls.first().second["allowStale"])
    }

    @Test fun atMostOneReadInFlightPerKey() = runTest {
        val transport = FakeTransport()
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        transport.handler = { tag, _ ->
            if (tag == "pullRequests.detail") { inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); gate.await(); inFlight-- }
            if (tag == "pullRequests.detail") detailJson() else JsonNull
        }
        val reads = reads(transport)
        reads.panel(key, snapshot); runCurrent()
        repeat(3) { reads.refresh(key) }; runCurrent()
        assertEquals(1, transport.count("pullRequests.detail"))
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, maxInFlight)
        assertEquals(2, transport.count("pullRequests.detail")) // one follow-up for the joined refreshes
    }

    @Test fun refreshStreamOpenOnlyWhileWatchedAndLive() = runTest {
        val transport = FakeTransport().apply { answerDetail(); live = false }
        val reads = reads(transport)
        val flow = reads.panel(key, snapshot)
        runCurrent()
        assertEquals(0, transport.opens)

        val collector = backgroundScope.launch { flow.collect {} }
        runCurrent()
        assertEquals("not live: no stream", 0, transport.opens)
        transport.live = true
        reads.sessionLive(); runCurrent()
        assertEquals(1, transport.opens)
        assertTrue(reads.streamOpen)

        collector.cancel(); runCurrent()
        assertEquals(1, transport.closes)
        assertFalse(reads.streamOpen)

        val again = backgroundScope.launch { flow.collect {} }
        runCurrent()
        assertEquals(2, transport.opens)
        reads.sessionLost(); runCurrent()
        assertEquals(2, transport.closes)
        assertFalse(reads.streamOpen)
        again.cancel()
    }

    @Test fun unavailableErrorsBecomeReasonsAndOtherFailsAGenericError() = runTest {
        val transport = FakeTransport()
        val reads = reads(transport)
        transport.handler = { _, _ -> throw unavailable("provider-unsupported") }
        val flow = reads.panel(key, snapshot); advanceUntilIdle()
        assertEquals(PrUnavailableReason.ProviderUnsupported, flow.value.unavailableReason)
        assertNull(flow.value.error)
        assertTrue(flow.value.answered)

        transport.handler = { _, _ -> throw unavailable("cli-unauthenticated") }
        reads.refresh(key); advanceUntilIdle()
        assertEquals(PrUnavailableReason.CliUnauthenticated, flow.value.unavailableReason)

        transport.handler = { _, _ -> throw unavailable("something-new") }
        reads.refresh(key); advanceUntilIdle()
        assertEquals(PrUnavailableReason.Other, flow.value.unavailableReason)

        transport.handler = { _, _ -> throw PrRpcFailure("pullRequests.detail", buildJsonObject { put("_tag", "PullRequestOperationError"); put("detail", "secret host text") }) }
        reads.refresh(key); advanceUntilIdle()
        assertEquals("Couldn't load this pull request", flow.value.error)

        transport.answerDetail(); reads.refresh(key); advanceUntilIdle()
        assertNull(flow.value.error)
        assertNull(flow.value.unavailableReason)
    }

    @Test fun detailDecodingIntersectsCapabilitiesPermissionsAndMergeCapabilities() {
        val detail = decodePullRequestDetail(
            detailJson(permActions = listOf("merge", "ready"), mergeCaps = mapOf("merge" to true, "squash" to false, "rebase" to true), permComment = false),
            snapshot.copy(reviewDecision = com.tajchert.trefoil.core.model.PrReviewDecision.Approved),
        )!!
        assertEquals(setOf(PrAction.Merge, PrAction.Ready), detail.actions)
        assertEquals(listOf(PrMergeMethod.Merge, PrMergeMethod.Rebase), detail.mergeMethods) // fast-forward absent = not allowed
        assertFalse(detail.canComment)
        assertEquals("abc123", detail.headSha)
        assertEquals(com.tajchert.trefoil.core.model.PrReviewDecision.Approved, detail.reviewDecision)
        assertEquals("octo", detail.author?.login)
        assertTrue(detail.canViewDiff)

        val unreported = decodePullRequestDetail(detailJson(permActions = null, permComment = null, mergeCaps = null), null)!!
        assertEquals(setOf(PrAction.Merge, PrAction.UpdateBranch, PrAction.Ready, PrAction.Draft), unreported.actions) // close dropped
        assertTrue(unreported.canComment)
        assertTrue(unreported.mergeMethods.isEmpty())

        assertNull(decodePullRequestDetail(buildJsonObject { put("title", "x") }, null))
        assertNull(decodePullRequestChecks(JsonNull))
    }

    @Test fun checksSkippedWhenUnsupported() = runTest {
        val transport = FakeTransport().apply { answerDetail() }
        val flow = reads(transport, checks = false).panel(key, snapshot); advanceUntilIdle()
        assertEquals(0, transport.count("pullRequests.checks"))
        assertNull(flow.value.checks)
        assertEquals("embedded", flow.value.visibleChecks.single().name)
    }

    @Test fun forgetClearsCacheAndStream() = runTest {
        val transport = FakeTransport().apply { answerDetail() }
        val reads = reads(transport)
        val flow = reads.panel(key, snapshot)
        backgroundScope.launch { flow.collect {} }
        runCurrent(); advanceUntilIdle()
        assertTrue(reads.streamOpen)
        reads.forget(); runCurrent()
        assertNull(reads.current(key))
        assertEquals(PrPanelState(), flow.value)
        assertFalse(reads.streamOpen)
    }

    @Test fun cacheEvictsLeastRecentlyUsedUnwatchedEntries() = runTest {
        val transport = FakeTransport().apply { live = false }
        val reads = reads(transport, maxEntries = 2)
        reads.panel(key, snapshot); reads.panel(otherKey, snapshot)
        reads.panel(key.copy(number = 9), snapshot)
        assertNull(reads.current(key))
        assertTrue(reads.current(otherKey) != null)
    }

    @Test fun diffPagesAreParsedAndCappedAtFourMegabytes() = runTest {
        val transport = FakeTransport()
        val reads = reads(transport)
        val patch = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n"
        transport.diffReply = { buildJsonObject { put("patch", patch); put("truncated", false); put("nextCursor", "c2") } }
        val page = reads.loadDiff(key, "c1")
        assertEquals("c2", page.nextCursor)
        assertEquals(1, page.document.files.size)
        assertEquals("c1", (transport.diffBodies.single()["cursor"] as JsonPrimitive).content)
        assertEquals("project-1", (transport.diffBodies.single()["projectId"] as JsonPrimitive).content)

        transport.diffReply = { buildJsonObject { put("patch", "x".repeat(PR_DIFF_MAX_PAGE_CHARS + 1)); put("truncated", false); put("nextCursor", JsonNull) } }
        try { reads.loadDiff(key, null); fail("oversized page accepted") } catch (error: PullRequestReadException) {
            assertEquals("This part of the pull request diff is too large for mobile", error.message)
        }

        transport.live = false
        try { reads.loadDiff(key, null); fail("read while not live") } catch (_: PrRequestNotSent) {}
    }

    // --- actions ---

    private suspend fun TestScope.loaded(transport: FakeTransport, detail: JsonObject = detailJson()): Pair<PullRequestReads, PullRequestActions> {
        val reads = reads(transport)
        transport.answerDetail { detail }
        reads.panel(key, snapshot); advanceUntilIdle()
        transport.calls.clear()
        return reads to PullRequestActions(transport, reads, now = { 5L })
    }

    @Test fun timeoutLeavesUnknownAndNeverResends() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        transport.handler = { tag, _ -> if (tag == "pullRequests.runAction") throw UncertainOutcomeException("timeout") else detailJson() }
        assertEquals(PrActionOutcome.Unknown, actions.run(key, PrAction.Draft))
        advanceUntilIdle()
        assertEquals(1, transport.count("pullRequests.runAction"))
        assertEquals(PrActionOutcome.Unknown, reads.current(key)?.pendingAction?.outcome)
        try { actions.run(key, PrAction.Draft); fail("sent while unknown") } catch (_: IllegalStateException) {}
        transport.live = false
        actions.reconcile(); advanceUntilIdle()
        assertEquals(1, transport.count("pullRequests.runAction"))
        assertEquals(PrActionOutcome.Unknown, reads.current(key)?.pendingAction?.outcome)
    }

    @Test fun unknownReconcilesFromFreshDetail() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        transport.handler = { tag, _ -> if (tag == "pullRequests.runAction") throw UncertainOutcomeException("lost") else detailJson() }
        actions.run(key, PrAction.Merge, PrMergeMethod.Squash)

        transport.answerDetail { detailJson(state = "merged") }
        transport.calls.clear()
        actions.reconcile(); advanceUntilIdle()
        val detailCall = transport.calls.first { it.first == "pullRequests.detail" }.second
        assertEquals(JsonPrimitive(false), detailCall["allowStale"])
        assertEquals(1, transport.count("pullRequests.checks"))
        assertEquals(0, transport.count("pullRequests.runAction"))
        assertEquals(PrActionOutcome.Done, reads.current(key)?.pendingAction?.outcome)
        assertEquals(PrState.Merged, reads.current(key)?.detail?.state)
    }

    @Test fun unknownMergeStillOpenSettlesToNotMerged() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        transport.handler = { tag, _ -> if (tag == "pullRequests.runAction") throw UncertainOutcomeException("lost") else detailJson() }
        actions.run(key, PrAction.Merge, PrMergeMethod.Merge)
        transport.answerDetail()
        actions.reconcile(); advanceUntilIdle()
        assertEquals(PrActionOutcome.Rejected("Not merged – try again"), reads.current(key)?.pendingAction?.outcome)
        assertFalse(reads.current(key)!!.pendingAction!!.blocksActions)
    }

    @Test fun typedFailIsRejected() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        transport.handler = { tag, _ -> if (tag == "pullRequests.runAction") throw operation("Head branch was modified") else detailJson() }
        val outcome = actions.run(key, PrAction.Merge, PrMergeMethod.Merge)
        assertEquals(PrActionOutcome.Rejected("T3 rejected the merge: Head branch was modified"), outcome)
        assertEquals(outcome, reads.current(key)?.pendingAction?.outcome)
        assertEquals(PrActionKind.Merge, reads.current(key)?.pendingAction?.kind)
        assertEquals(1, transport.count("pullRequests.runAction"))
    }

    @Test fun mergeAlwaysSendsExpectedHeadShaAndDoneRevalidatesFresh() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        transport.handler = { tag, _ -> if (tag == "pullRequests.runAction") JsonNull else detailJson(state = "merged") }
        assertEquals(PrActionOutcome.Done, actions.run(key, PrAction.Merge, PrMergeMethod.Squash))
        advanceUntilIdle()
        val payload = transport.calls.first { it.first == "pullRequests.runAction" }.second
        assertEquals(JsonPrimitive("abc123"), payload["expectedHeadSha"])
        assertEquals(JsonPrimitive("squash"), payload["mergeMethod"])
        assertEquals(JsonPrimitive("merge"), payload["action"])
        assertEquals(JsonPrimitive("project-1"), payload["projectId"])
        assertEquals(JsonPrimitive(false), transport.calls.first { it.first == "pullRequests.detail" }.second["allowStale"])
        assertEquals(PrState.Merged, reads.current(key)?.detail?.state)

        val (_, noSha) = loaded(FakeTransport(), detailJson(headSha = null))
        try { noSha.run(key, PrAction.Merge, PrMergeMethod.Merge); fail("merged without head sha") } catch (_: IllegalStateException) {}
        try { actions.run(key, PrAction.Merge, PrMergeMethod.Rebase); fail("disallowed method sent") } catch (_: IllegalArgumentException) {}
    }

    @Test fun actionsRefusedWhileNotLive() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        transport.live = false
        try { actions.run(key, PrAction.Draft); fail("sent while offline") } catch (_: PrRequestNotSent) {}
        try { actions.comment(key, "hello"); fail("sent while offline") } catch (_: PrRequestNotSent) {}
        assertTrue(transport.calls.isEmpty())
        assertNull(reads.current(key)?.pendingAction)
    }

    @Test fun commentUnknownWarnsAndIsNeverResent() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        transport.handler = { tag, _ -> if (tag == "pullRequests.comment") throw UncertainOutcomeException("timeout") else detailJson() }
        val outcome = actions.comment(key, "Looks good")
        assertEquals(PrActionOutcome.Rejected(PullRequestActions.COMMENT_MAY_HAVE_POSTED), outcome)
        assertEquals(outcome, reads.current(key)?.pendingAction?.outcome)
        actions.reconcile(); advanceUntilIdle()
        assertEquals(1, transport.count("pullRequests.comment"))
        assertEquals(JsonPrimitive("Looks good"), transport.calls.single { it.first == "pullRequests.comment" }.second["body"])
    }

    @Test fun sendingMutationIsReportedAndBlocksAnother() = runTest {
        val transport = FakeTransport()
        val (reads, actions) = loaded(transport)
        val gate = CompletableDeferred<JsonElement>()
        transport.handler = { tag, _ -> if (tag == "pullRequests.runAction") gate.await() else detailJson(isDraft = false) }
        val job = launch { actions.run(key, PrAction.Draft) }
        runCurrent()
        assertTrue(actions.hasSendingMutation())
        assertEquals(PrActionOutcome.Sending, reads.current(key)?.pendingAction?.outcome)
        try { actions.run(key, PrAction.Draft); fail("second send while sending") } catch (_: IllegalStateException) {}
        gate.complete(JsonNull); advanceUntilIdle()
        assertFalse(actions.hasSendingMutation())
        assertTrue(job.isCompleted)
        assertEquals(1, transport.count("pullRequests.runAction"))
    }

    @Test fun rejectionMessageOmitsUnknownErrorBodies() {
        val generic = PrRpcFailure("pullRequests.runAction", buildJsonObject { put("_tag", "Weird"); put("detail", "x") })
        assertEquals("T3 rejected the comment", PullRequestActions.rejectionMessage(PrActionKind.Comment, generic))
        val unavailable = PrRpcFailure("pullRequests.runAction", buildJsonObject { put("_tag", "PullRequestUnavailableError"); put("reason", "cli-missing") })
        assertTrue(PullRequestActions.rejectionMessage(PrActionKind.Ready, unavailable).startsWith("T3 rejected marking ready for review: "))
    }
}
