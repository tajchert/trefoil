// Adapted from T3 Code (MIT), packages/contracts/src/pullRequest.ts and packages/contracts/src/threadPullRequest.ts @ 7deb51b550
package com.tajchert.trefoil.core.model

/**
 * Identifies one pull request for `pullRequests.*` reads and mutations: upstream `PullRequestRef` minus its per-call
 * flags (`allowStale`, `expectedAccountId`). [projectId] comes from the thread, since the thread link carries none.
 */
data class PullRequestKey(val projectId: String, val host: String?, val repository: String, val number: Int)

/** Upstream `PullRequestState`. */
enum class PrState(val wire: String) {
    Open("open"), Closed("closed"), Merged("merged");
    companion object { fun fromWire(value: String?): PrState? = entries.firstOrNull { it.wire == value } }
}

/** Upstream `PullRequestReviewDecision`. */
enum class PrReviewDecision(val wire: String) {
    Approved("approved"), ChangesRequested("changes-requested"), ReviewRequired("review-required");
    companion object { fun fromWire(value: String?): PrReviewDecision? = entries.firstOrNull { it.wire == value } }
}

/** Upstream `PullRequestChecksState`: the roll-up of all checks. */
enum class PrChecksState(val wire: String) {
    Passing("passing"), Failing("failing"), Pending("pending");
    companion object { fun fromWire(value: String?): PrChecksState? = entries.firstOrNull { it.wire == value } }
}

/** Upstream `PullRequestMergeability`; anything unrecognised maps to [Unknown]. */
enum class PrMergeability(val wire: String) {
    Mergeable("mergeable"), Conflicting("conflicting"), Unknown("unknown");
    companion object { fun fromWire(value: String?): PrMergeability = entries.firstOrNull { it.wire == value } ?: Unknown }
}

/** Upstream `PullRequestMergeMethod`. [FastForward] refuses unless the head contains the base. */
enum class PrMergeMethod(val wire: String) {
    Merge("merge"), Squash("squash"), Rebase("rebase"), FastForward("fast-forward");
    companion object { fun fromWire(value: String?): PrMergeMethod? = entries.firstOrNull { it.wire == value } }
}

/** The `pullRequests.runAction` actions Trefoil v1 offers (a subset of upstream `PullRequestAction`). */
enum class PrAction(val wire: String) {
    Merge("merge"), UpdateBranch("update-branch"), Ready("ready"), Draft("draft");
    companion object {
        /** Null for upstream actions v1 does not offer (close, reopen, revert, ...); callers drop them. */
        fun fromWire(value: String?): PrAction? = entries.firstOrNull { it.wire == value }
    }
}

/** What a [PendingPrAction] is doing: a [PrAction] or a `pullRequests.comment`. */
enum class PrActionKind {
    Merge, UpdateBranch, Ready, Draft, Comment;
    companion object {
        fun of(action: PrAction): PrActionKind = when (action) {
            PrAction.Merge -> Merge
            PrAction.UpdateBranch -> UpdateBranch
            PrAction.Ready -> Ready
            PrAction.Draft -> Draft
        }
    }
}

/** Upstream `PullRequestUnavailableError.reason`; [Other] for reasons this client does not know. */
enum class PrUnavailableReason(val wire: String?) {
    CliMissing("cli-missing"), CliUnauthenticated("cli-unauthenticated"), ProviderUnsupported("provider-unsupported"),
    RepositoryWithoutProject("repository-without-project"), Other(null);
    companion object { fun fromWire(value: String?): PrUnavailableReason = entries.firstOrNull { it.wire != null && it.wire == value } ?: Other }
}

/** Upstream `PullRequestCheckStatus`; unrecognised statuses map to [Neutral]. */
enum class PrCheckStatus(val wire: String) {
    Pending("pending"), ActionRequired("action-required"), Success("success"), Failure("failure"),
    Skipped("skipped"), Neutral("neutral"), Cancelled("cancelled");
    companion object { fun fromWire(value: String?): PrCheckStatus = entries.firstOrNull { it.wire == value } ?: Neutral }
}

/** Upstream `PullRequestActor`, without the avatar. */
data class PrActor(val login: String, val name: String? = null, val isBot: Boolean = false)

/** Upstream `ThreadPullRequestSnapshot`: what the thread projection last saw, painted before any `detail` arrives. */
data class PrSnapshot(
    val state: PrState,
    val title: String,
    val headRef: String,
    val baseRef: String,
    val isDraft: Boolean = false,
    /** ISO-8601 from the host; null when unknown. */
    val updatedAt: String? = null,
    val author: PrActor? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
    val reviewDecision: PrReviewDecision? = null,
    val checksState: PrChecksState? = null,
    val mergeability: PrMergeability? = null,
    val rebaseRequired: Boolean = false,
)

/**
 * One `ThreadPullRequestLink` of the selected thread's projection. [source] is the wire link source (`manual`,
 * `created`, `agent`, `stack`); `stack-dismissed` links are dropped before they reach here.
 */
data class ThreadPullRequest(val key: PullRequestKey, val url: String, val source: String, val snapshot: PrSnapshot? = null)

/** Upstream `PullRequestCheck`. A [placeholder] stands for checks the host could not list; never count it as passing. */
data class PrCheck(
    val name: String,
    val status: PrCheckStatus,
    val description: String? = null,
    val url: String? = null,
    val required: Boolean = false,
    val placeholder: Boolean = false,
)

/**
 * The fields of upstream `PullRequestDetail` the PR panel uses. [actions], [mergeMethods] and [canComment] are already
 * the intersection of the PR's `capabilities` and `viewerPermissions` (and, for merge methods, `mergeCapabilities`),
 * computed by the session layer.
 */
data class PrDetail(
    val title: String,
    val url: String,
    val state: PrState,
    val isDraft: Boolean,
    val headRef: String,
    val baseRef: String,
    /** Head commit; merges send it as `expectedHeadSha`. Null when the host did not report one. */
    val headSha: String? = null,
    val mergeability: PrMergeability = PrMergeability.Unknown,
    val rebaseRequired: Boolean = false,
    /** Commits behind the base; null when not reported. */
    val behindBy: Int? = null,
    /** Detail has none upstream; filled from the link snapshot or summary when known. */
    val reviewDecision: PrReviewDecision? = null,
    val author: PrActor? = null,
    /** ISO-8601 from the host. */
    val updatedAt: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changedFiles: Int = 0,
    /** Checks embedded in the detail reply; `pullRequests.checks` supersedes them in [PrPanelState.checks]. */
    val checks: List<PrCheck> = emptyList(),
    val actions: Set<PrAction> = emptySet(),
    val mergeMethods: List<PrMergeMethod> = emptyList(),
    val canComment: Boolean = false,
    /** `capabilities.diff`: the host can serve `POST /api/pull-requests/diff`. */
    val canViewDiff: Boolean = false,
) {
    /** The PR reports [action] as allowed and its state permits it (merge also needs a merge method). */
    fun canRun(action: PrAction): Boolean {
        if (action !in actions || state != PrState.Open) return false
        return when (action) {
            PrAction.Merge -> mergeMethods.isNotEmpty()
            PrAction.UpdateBranch -> true
            PrAction.Ready -> isDraft
            PrAction.Draft -> !isDraft
        }
    }
}

/** Progress of a PR mutation. [Unknown] is uncertain: reconcile from `detail`, never resend automatically. */
sealed interface PrActionOutcome {
    data object Sending : PrActionOutcome
    data object Unknown : PrActionOutcome
    data class Rejected(val message: String) : PrActionOutcome
    data object Done : PrActionOutcome
}

data class PendingPrAction(val kind: PrActionKind, val startedAtMillis: Long, val outcome: PrActionOutcome) {
    /** [PrActionOutcome.Sending] or [PrActionOutcome.Unknown]: further mutations on this PR stay disabled. */
    val blocksActions: Boolean get() = outcome == PrActionOutcome.Sending || outcome == PrActionOutcome.Unknown
}

/** One pull request's panel: the link [snapshot] first, then `detail` and `checks`, revalidated on refresh ticks. */
data class PrPanelState(
    val snapshot: PrSnapshot? = null,
    val detail: PrDetail? = null,
    /** Latest `pullRequests.checks` reply; null until answered or when checks are unsupported. */
    val checks: List<PrCheck>? = null,
    /** True once the server answered `detail` (successfully or with an error) at least once. */
    val answered: Boolean = false,
    val refreshing: Boolean = false,
    /** Last read failure, safe to show; no PR content. */
    val error: String? = null,
    val unavailableReason: PrUnavailableReason? = null,
    val pendingAction: PendingPrAction? = null,
    val lastUpdatedMillis: Long? = null,
) {
    /** Checks to show: the dedicated reply when present, otherwise those embedded in [detail]. */
    val visibleChecks: List<PrCheck> get() = checks ?: detail?.checks.orEmpty()

    /**
     * Mutations may be offered: a live writable session with `source-control:write` on a server that supports pull
     * requests, and no sending or uncertain mutation on this PR.
     */
    fun actionsEnabled(client: ClientState): Boolean =
        client.connection == ConnectionStatus.Connected && client.canWrite && client.canWriteSourceControl &&
            client.pullRequestsSupported && pendingAction?.blocksActions != true

    /** [actionsEnabled] and the PR's detail allows [action]. */
    fun canRun(action: PrAction, client: ClientState): Boolean = actionsEnabled(client) && detail?.canRun(action) == true

    /** [actionsEnabled] and the PR's detail allows comments. */
    fun canComment(client: ClientState): Boolean = actionsEnabled(client) && detail?.canComment == true
}

/** One page of `POST /api/pull-requests/diff`, parsed. [nextCursor] null means the last page. */
data class PrDiffPage(val document: DiffDocument, val nextCursor: String?, val truncated: Boolean)
