package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.PendingPrAction
import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrActionKind
import com.tajchert.trefoil.core.model.PrActionOutcome
import com.tajchert.trefoil.core.model.PrDetail
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PrState
import com.tajchert.trefoil.core.model.PrUnavailableReason
import com.tajchert.trefoil.core.model.PullRequestKey
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pull request mutations (`pullRequests.runAction`, `pullRequests.comment`). They carry no `commandId` and are not
 * idempotent, so each is sent exactly once and never retried or replayed. Progress shows in the PR's panel as
 * [PendingPrAction]:
 * - Sending, then Done (followed by an `allowStale:false` revalidation) on success;
 * - Rejected on a typed `Fail` (a definite rejection);
 * - Unknown on a timeout, disconnect, oversized reply or Interrupt-only Exit. Unknown blocks that PR's actions until
 *   [reconcile] (called by [T3Client] on the next live session) reads `detail{allowStale:false}` + `checks` and settles
 *   it from the observed state. A comment's outcome cannot be read from `detail`, so it settles at once to a warning.
 *
 * Refusals before sending (not live, a blocking pending action, the PR does not allow it) throw and send nothing.
 */
class PullRequestActions(
    private val transport: PullRequestTransport,
    private val reads: PullRequestReads,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Sent(val pending: PendingPrAction, val headSha: String?)

    private val lock = Any()
    private val sent = HashMap<PullRequestKey, Sent>()

    /** True while any mutation is on the wire; route switches wait for it. */
    fun hasSendingMutation(): Boolean = synchronized(lock) { sent.values.any { it.pending.outcome == PrActionOutcome.Sending } }

    /**
     * Sends [action] once. Merge needs a [mergeMethod] the PR allows and always sends `expectedHeadSha` from the last
     * detail; update-branch sends it when known. Returns the settled outcome (also published in the panel).
     */
    suspend fun run(key: PullRequestKey, action: PrAction, mergeMethod: PrMergeMethod? = null): PrActionOutcome {
        val detail = reads.current(key)?.detail
        val kind = PrActionKind.of(action)
        val payload = buildJsonObject {
            putRef(key)
            put("action", action.wire)
            when (action) {
                PrAction.Merge -> {
                    val method = requireNotNull(mergeMethod) { "Choose a merge method" }
                    require(detail != null && method in detail.mergeMethods) { "This merge method is not allowed for this pull request" }
                    put("mergeMethod", method.wire)
                    put("expectedHeadSha", detail.headSha ?: throw IllegalStateException("Refresh the pull request before merging"))
                }
                PrAction.UpdateBranch -> detail?.headSha?.let { put("expectedHeadSha", it) }
                PrAction.Ready, PrAction.Draft -> Unit
            }
        }
        check(detail != null && detail.canRun(action)) { "This pull request does not allow that action now" }
        return send(key, kind, "pullRequests.runAction", payload, detail.headSha)
    }

    /** Posts [body] once. An uncertain outcome settles to [COMMENT_MAY_HAVE_POSTED]; it is never resent. */
    suspend fun comment(key: PullRequestKey, body: String): PrActionOutcome {
        require(body.isNotBlank()) { "Write a comment first" }
        require(body.length <= MAX_COMMENT_CHARS) { "The comment is too long" }
        val detail = reads.current(key)?.detail
        check(detail?.canComment == true) { "This pull request does not accept comments from here" }
        val payload = buildJsonObject { putRef(key); put("body", body) }
        return send(key, PrActionKind.Comment, "pullRequests.comment", payload, detail?.headSha)
    }

    /**
     * Settles every Unknown action from a fresh `detail{allowStale:false}` (+ `checks`). Stays Unknown when the read
     * fails or the session is not live; never resends.
     */
    suspend fun reconcile() {
        val unknown = synchronized(lock) { sent.filterValues { it.pending.outcome == PrActionOutcome.Unknown }.toList() }
        for ((key, entry) in unknown) {
            val detail = reads.reconcileRead(key) ?: continue
            val outcome = observedOutcome(entry, detail)
            publish(key, entry.pending, entry.copy(pending = entry.pending.copy(outcome = outcome)))
        }
    }

    /** Drops all action state (environment forgotten). In-flight sends finish without publishing. */
    fun forget() = synchronized(lock) { sent.clear() }

    private suspend fun send(key: PullRequestKey, kind: PrActionKind, tag: String, payload: JsonObject, headSha: String?): PrActionOutcome {
        val pending = PendingPrAction(kind, now(), PrActionOutcome.Sending)
        val entry = Sent(pending, headSha)
        synchronized(lock) {
            if (!transport.isLive()) throw PrRequestNotSent("Reconnect before changing this pull request")
            val blocking = sent[key]?.pending ?: reads.current(key)?.pendingAction
            check(blocking?.blocksActions != true) { "Another change to this pull request is still in progress" }
            sent[key] = entry
            reads.updatePanel(key) { it.copy(pendingAction = pending) }
        }
        val outcome = try {
            transport.request(tag, payload)
            PrActionOutcome.Done
        } catch (notSent: PrRequestNotSent) {
            synchronized(lock) {
                if (sent[key] === entry) {
                    sent.remove(key)
                    reads.updatePanel(key) { if (it.pendingAction === pending) it.copy(pendingAction = null) else it }
                }
            }
            throw notSent
        } catch (failure: PrRpcFailure) {
            PrActionOutcome.Rejected(rejectionMessage(kind, failure))
        } catch (cancelled: CancellationException) {
            publish(key, pending, entry.copy(pending = pending.copy(outcome = uncertain(kind))))
            throw cancelled
        } catch (_: Exception) {
            uncertain(kind)
        }
        publish(key, pending, entry.copy(pending = pending.copy(outcome = outcome)))
        if (outcome == PrActionOutcome.Done && kind != PrActionKind.Comment) reads.refresh(key, allowStale = false)
        return outcome
    }

    private fun uncertain(kind: PrActionKind): PrActionOutcome =
        if (kind == PrActionKind.Comment) PrActionOutcome.Rejected(COMMENT_MAY_HAVE_POSTED) else PrActionOutcome.Unknown

    /** Replaces [previous] with [next] unless another action or [forget] superseded it. */
    private fun publish(key: PullRequestKey, previous: PendingPrAction, next: Sent) {
        synchronized(lock) {
            if (sent[key]?.pending !== previous) return
            sent[key] = next
            reads.updatePanel(key) { if (it.pendingAction === previous) it.copy(pendingAction = next.pending) else it }
        }
    }

    private fun observedOutcome(entry: Sent, detail: PrDetail): PrActionOutcome = when (entry.pending.kind) {
        PrActionKind.Merge -> if (detail.state == PrState.Merged) PrActionOutcome.Done else PrActionOutcome.Rejected("Not merged – try again")
        PrActionKind.Ready -> if (!detail.isDraft) PrActionOutcome.Done else PrActionOutcome.Rejected("Still a draft – try again")
        PrActionKind.Draft -> if (detail.isDraft) PrActionOutcome.Done else PrActionOutcome.Rejected("Not converted to draft – try again")
        PrActionKind.UpdateBranch ->
            if ((entry.headSha != null && detail.headSha != null && detail.headSha != entry.headSha) || detail.behindBy == 0) PrActionOutcome.Done
            else PrActionOutcome.Rejected("Branch not updated – try again")
        PrActionKind.Comment -> PrActionOutcome.Rejected(COMMENT_MAY_HAVE_POSTED)
    }

    companion object {
        const val COMMENT_MAY_HAVE_POSTED = "Comment may have posted – check the PR before sending again"
        const val MAX_COMMENT_CHARS = 65_536
        private const val MAX_DETAIL_CHARS = 300

        /** "T3 rejected …" with the host's operation detail (shown, never logged), trimmed. */
        internal fun rejectionMessage(kind: PrActionKind, failure: PrRpcFailure): String {
            val what = when (kind) {
                PrActionKind.Merge -> "the merge"
                PrActionKind.UpdateBranch -> "the branch update"
                PrActionKind.Ready -> "marking ready for review"
                PrActionKind.Draft -> "converting to draft"
                PrActionKind.Comment -> "the comment"
            }
            val reason = when (failure.errorTag) {
                PullRequestReads.UNAVAILABLE_TAG -> PullRequestReads.unavailableMessage(PrUnavailableReason.fromWire(failure.body.str("reason")))
                PullRequestReads.OPERATION_TAG -> failure.body.str("detail")?.trim()?.take(MAX_DETAIL_CHARS)?.takeIf { it.isNotEmpty() }
                else -> null
            }
            return if (reason == null) "T3 rejected $what" else "T3 rejected $what: $reason"
        }
    }
}
