package com.tajchert.trefoil.ui.pr

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffRow
import com.tajchert.trefoil.core.model.PendingPrAction
import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrActionKind
import com.tajchert.trefoil.core.model.PrActionOutcome
import com.tajchert.trefoil.core.model.PrActor
import com.tajchert.trefoil.core.model.PrCheck
import com.tajchert.trefoil.core.model.PrCheckStatus
import com.tajchert.trefoil.core.model.PrChecksState
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PrMergeability
import com.tajchert.trefoil.core.model.PrPanelState
import com.tajchert.trefoil.core.model.PrReviewDecision
import com.tajchert.trefoil.core.model.PrState
import com.tajchert.trefoil.core.model.PrUnavailableReason
import com.tajchert.trefoil.core.model.ThreadPullRequest

/*
 * Pure presentation models of the pull request panel and header chip. Everything here is derived from session state, so the UI stays
 * stateless and testable. Pull request titles, branches, bodies and check text are repository content: never log them.
 */

/** What the chip and the Overview call a pull request: open, draft, merged or closed. */
internal enum class PrDisplayState(val label: String) { Open("Open"), Draft("Draft"), Merged("Merged"), Closed("Closed") }

internal fun prDisplayState(state: PrState, isDraft: Boolean): PrDisplayState = when (state) {
    PrState.Merged -> PrDisplayState.Merged
    PrState.Closed -> PrDisplayState.Closed
    PrState.Open -> if (isDraft) PrDisplayState.Draft else PrDisplayState.Open
}

/** Roll-up of the checks for the chip dot and the Overview line. [None] means nothing is known (no dot). */
internal enum class PrChecksTone(val label: String) {
    None("No checks reported"), Passing("Checks passing"), Failing("Checks failing"), Pending("Checks pending")
}

/** Severity of one check for ordering: failing ones first, then pending, then the rest. Lower is more urgent. */
internal fun checkRank(status: PrCheckStatus): Int = when (status) {
    PrCheckStatus.Failure -> 0
    PrCheckStatus.ActionRequired -> 1
    PrCheckStatus.Cancelled -> 2
    PrCheckStatus.Pending -> 3
    PrCheckStatus.Success -> 4
    PrCheckStatus.Skipped -> 5
    PrCheckStatus.Neutral -> 6
}

/** A check that makes the roll-up fail. */
internal fun PrCheckStatus.isFailing(): Boolean = checkRank(this) <= 2

/** Checks in display order: failing first, then pending, then passing; required ones lead their group. Stable otherwise. */
internal fun orderChecks(checks: List<PrCheck>): List<PrCheck> =
    checks.filter { !it.placeholder }.sortedWith(compareBy<PrCheck>({ checkRank(it.status) }, { !it.required }))

/**
 * The roll-up: from the real checks when there are any, otherwise from the link snapshot's state. Placeholder checks (the host could
 * not list them) are never counted as passing.
 */
internal fun checksTone(snapshot: PrChecksState?, checks: List<PrCheck>?): PrChecksTone {
    val real = checks.orEmpty().filter { !it.placeholder }
    if (real.isNotEmpty()) return when {
        real.any { it.status.isFailing() } -> PrChecksTone.Failing
        real.any { it.status == PrCheckStatus.Pending } -> PrChecksTone.Pending
        else -> PrChecksTone.Passing
    }
    return when (snapshot) {
        PrChecksState.Failing -> PrChecksTone.Failing
        PrChecksState.Pending -> PrChecksTone.Pending
        PrChecksState.Passing -> PrChecksTone.Passing
        null -> PrChecksTone.None
    }
}

/** "2 failing · 1 pending · 5 passing"; null when there is nothing to count. */
internal fun checksSummary(checks: List<PrCheck>): String? {
    val real = checks.filter { !it.placeholder }
    if (real.isEmpty()) return null
    val failing = real.count { it.status.isFailing() }
    val pending = real.count { it.status == PrCheckStatus.Pending }
    val passing = real.size - failing - pending
    return listOfNotNull(
        "$failing failing".takeIf { failing > 0 }, "$pending pending".takeIf { pending > 0 }, "$passing passing".takeIf { passing > 0 },
    ).joinToString(" · ")
}

/** The panel's status word for one check, also its accessibility text. */
internal fun checkStatusLabel(status: PrCheckStatus): String = when (status) {
    PrCheckStatus.Success -> "Passed"
    PrCheckStatus.Failure -> "Failed"
    PrCheckStatus.Pending -> "Running"
    PrCheckStatus.ActionRequired -> "Action required"
    PrCheckStatus.Cancelled -> "Cancelled"
    PrCheckStatus.Skipped -> "Skipped"
    PrCheckStatus.Neutral -> "Neutral"
}

/** The header chip: state, checks dot, number. [extra] counts further linked pull requests behind this one. */
internal data class PrChipModel(val number: Int, val state: PrDisplayState?, val checks: PrChecksTone, val extra: Int, val description: String)

/** The chip's model for the first of [links]; [panel] (when the caller has it) refines the snapshot with fresher detail and checks. */
internal fun prChipModel(links: List<ThreadPullRequest>, panel: PrPanelState? = null): PrChipModel? {
    val link = links.firstOrNull() ?: return null
    val state = panel?.detail?.let { prDisplayState(it.state, it.isDraft) } ?: (panel?.snapshot ?: link.snapshot)?.let { prDisplayState(it.state, it.isDraft) }
    val checks = checksTone((panel?.snapshot ?: link.snapshot)?.checksState, panel?.visibleChecks)
    val extra = links.size - 1
    val description = buildString {
        append("Pull request #${link.key.number}")
        if (state != null) append(", ${state.label.lowercase()}")
        if (checks != PrChecksTone.None) append(", ${checks.label.lowercase()}")
        if (extra > 0) append(", and $extra more")
    }
    return PrChipModel(link.key.number, state, checks, extra, description)
}

/** What the Overview shows, from the panel's detail when it has arrived, otherwise from the thread link's snapshot. */
internal data class PrOverview(
    val title: String,
    val state: PrDisplayState,
    val headRef: String,
    val baseRef: String,
    val author: String?,
    val mergeability: PrMergeability?,
    val rebaseRequired: Boolean,
    val behindBy: Int?,
    val reviewDecision: PrReviewDecision?,
    val additions: Int?,
    val deletions: Int?,
    val changedFiles: Int?,
    val updatedAt: String?,
    /** True while only the link snapshot is known (no `detail` yet). */
    val fromSnapshot: Boolean,
)

internal fun PrActor.displayName(): String = login.takeIf { it.isNotBlank() } ?: name.orEmpty()

internal fun prOverview(link: ThreadPullRequest?, panel: PrPanelState): PrOverview? {
    val detail = panel.detail
    val snapshot = panel.snapshot ?: link?.snapshot
    if (detail != null) return PrOverview(
        title = detail.title, state = prDisplayState(detail.state, detail.isDraft), headRef = detail.headRef, baseRef = detail.baseRef,
        author = (detail.author ?: snapshot?.author)?.displayName()?.takeIf { it.isNotBlank() },
        mergeability = detail.mergeability, rebaseRequired = detail.rebaseRequired, behindBy = detail.behindBy,
        reviewDecision = detail.reviewDecision ?: snapshot?.reviewDecision,
        additions = detail.additions, deletions = detail.deletions, changedFiles = detail.changedFiles,
        updatedAt = detail.updatedAt ?: snapshot?.updatedAt, fromSnapshot = false,
    )
    if (snapshot == null) return null
    return PrOverview(
        title = snapshot.title, state = prDisplayState(snapshot.state, snapshot.isDraft), headRef = snapshot.headRef, baseRef = snapshot.baseRef,
        author = snapshot.author?.displayName()?.takeIf { it.isNotBlank() }, mergeability = snapshot.mergeability,
        rebaseRequired = snapshot.rebaseRequired, behindBy = null, reviewDecision = snapshot.reviewDecision,
        additions = snapshot.additions, deletions = snapshot.deletions, changedFiles = snapshot.changedFiles,
        updatedAt = snapshot.updatedAt, fromSnapshot = true,
    )
}

internal fun mergeabilityLabel(mergeability: PrMergeability?, rebaseRequired: Boolean, behindBy: Int?, baseRef: String): String? {
    val behind = behindBy?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "commit" else "commits"} behind $baseRef" }
    val core = when (mergeability) {
        PrMergeability.Mergeable -> "No conflicts"
        PrMergeability.Conflicting -> "Has merge conflicts"
        PrMergeability.Unknown, null -> null
    }
    return listOfNotNull(core, behind, "Rebase required".takeIf { rebaseRequired }).joinToString(" · ").takeIf { it.isNotEmpty() }
}

internal fun reviewDecisionLabel(decision: PrReviewDecision): String = when (decision) {
    PrReviewDecision.Approved -> "Approved"
    PrReviewDecision.ChangesRequested -> "Changes requested"
    PrReviewDecision.ReviewRequired -> "Review required"
}

/** Friendly body of the panel when `pullRequests.detail` fails with `PullRequestUnavailableError`; carries no server text. */
internal fun unavailableMessage(reason: PrUnavailableReason): Pair<String, String> = when (reason) {
    PrUnavailableReason.CliMissing ->
        "GitHub CLI not found" to "The machine running T3 has no GitHub CLI (gh). Install it there to see pull requests here."
    PrUnavailableReason.CliUnauthenticated ->
        "GitHub CLI is not signed in" to "The GitHub CLI on the machine running T3 is not signed in. Run gh auth login there, then open this panel again."
    PrUnavailableReason.ProviderUnsupported ->
        "Pull requests are not available for this project" to "This project's code host is not supported. Pull requests work for GitHub projects."
    PrUnavailableReason.RepositoryWithoutProject ->
        "Repository not set up as a project" to "The machine running T3 has no project for this pull request's repository."
    PrUnavailableReason.Other ->
        "Pull request unavailable" to "The server could not read this pull request right now."
}

/** "5 min ago" style age; [nowMillis] is injected. */
internal fun agoLabel(thenMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 45 -> "just now"
        seconds < 90 -> "1 min ago"
        seconds < 45 * 60 -> "${(seconds + 30) / 60} min ago"
        seconds < 90 * 60 -> "1 hour ago"
        seconds < 36 * 3600 -> "${(seconds + 1800) / 3600} hours ago"
        else -> "${(seconds + 43_200) / 86_400} days ago"
    }
}

/** "Last updated 5 min ago" while the panel is not live (offline, reconnecting) and a read ever succeeded; null otherwise. */
internal fun lastUpdatedLabel(panel: PrPanelState, live: Boolean, nowMillis: Long): String? {
    if (live) return null
    val at = panel.lastUpdatedMillis ?: return null
    return "Last updated ${agoLabel(at, nowMillis)}"
}

/** The merge method's picker label. */
internal fun mergeMethodLabel(method: PrMergeMethod): String = when (method) {
    PrMergeMethod.Merge -> "Create a merge commit"
    PrMergeMethod.Squash -> "Squash and merge"
    PrMergeMethod.Rebase -> "Rebase and merge"
    PrMergeMethod.FastForward -> "Fast-forward"
}

/** The method the picker starts on: squash when offered, otherwise the first the server allows. */
internal fun defaultMergeMethod(methods: List<PrMergeMethod>): PrMergeMethod? =
    methods.firstOrNull { it == PrMergeMethod.Squash } ?: methods.firstOrNull()

/** Largest comment the host accepts. */
internal const val PR_COMMENT_MAX_CHARS = 65_536

/** True when [body] may be sent: not blank and within 1..[PR_COMMENT_MAX_CHARS]. */
internal fun isValidComment(body: String): Boolean = body.isNotBlank() && body.length <= PR_COMMENT_MAX_CHARS

/** The buttons of the Actions section, each enabled only by the session's own rule ([PrPanelState.canRun] / [PrPanelState.canComment]). */
internal data class PrActionsModel(
    val merge: Boolean = false,
    val updateBranch: Boolean = false,
    val ready: Boolean = false,
    val draft: Boolean = false,
    val comment: Boolean = false,
    val mergeMethods: List<PrMergeMethod> = emptyList(),
    /** Whether the Ready (true) or Convert to draft (false) button is the relevant one for this pull request. */
    val showReady: Boolean = false,
    /** Why every button is off, in plain words; null when something is enabled. */
    val disabledReason: String? = null,
    val baseRef: String? = null,
    val number: Int? = null,
) {
    val anyEnabled: Boolean get() = merge || updateBranch || ready || draft || comment
}

/** Maps the panel and the client state onto the buttons. Nothing here decides on its own: it forwards `canRun` / `canComment`. */
internal fun prActionsModel(panel: PrPanelState, client: ClientState, number: Int? = null): PrActionsModel {
    val detail = panel.detail
    val merge = panel.canRun(PrAction.Merge, client)
    val update = panel.canRun(PrAction.UpdateBranch, client)
    val ready = panel.canRun(PrAction.Ready, client)
    val draft = panel.canRun(PrAction.Draft, client)
    val comment = panel.canComment(client)
    val any = merge || update || ready || draft || comment
    return PrActionsModel(
        merge = merge, updateBranch = update, ready = ready, draft = draft, comment = comment,
        mergeMethods = detail?.mergeMethods.orEmpty(), showReady = detail?.isDraft == true,
        disabledReason = if (any) null else actionsDisabledReason(panel, client),
        baseRef = detail?.baseRef, number = number,
    )
}

/** Plain-words reason that no action is available, most fundamental cause first; never blank. */
internal fun actionsDisabledReason(panel: PrPanelState, client: ClientState): String {
    val detail = panel.detail
    return when {
        client.connection != ConnectionStatus.Connected -> "You are offline. Actions are unavailable until the connection is back."
        !client.pullRequestsSupported -> "This server does not support pull request actions."
        !client.canWrite -> "This session is read-only, so pull request actions are unavailable."
        !client.canWriteSourceControl -> "Your account on this server cannot change source control, so pull request actions are unavailable."
        panel.pendingAction?.blocksActions == true -> "Another action on this pull request is still in progress."
        detail == null -> "Actions become available once the pull request has loaded."
        detail.state == PrState.Merged -> "This pull request is already merged."
        detail.state == PrState.Closed -> "This pull request is closed."
        else -> "The host does not allow any action on this pull request for your account."
    }
}

/** What to show for the pull request's pending mutation (null: nothing). [progress] draws a spinner; [error] uses the error colour. */
internal data class PrPendingText(val text: String, val progress: Boolean, val error: Boolean)

internal fun actionKindLabel(kind: PrActionKind): String = when (kind) {
    PrActionKind.Merge -> "Merging"
    PrActionKind.UpdateBranch -> "Updating branch"
    PrActionKind.Ready -> "Marking ready for review"
    PrActionKind.Draft -> "Converting to draft"
    PrActionKind.Comment -> "Posting comment"
}

internal fun pendingText(pending: PendingPrAction?): PrPendingText? = when (val outcome = pending?.outcome) {
    null, PrActionOutcome.Done -> null
    PrActionOutcome.Sending -> PrPendingText("${actionKindLabel(pending.kind)}…", progress = true, error = false)
    PrActionOutcome.Unknown ->
        if (pending.kind == PrActionKind.Comment) PrPendingText("Comment may have posted – check the pull request before sending again.", progress = false, error = true)
        else PrPendingText("Outcome unknown – checking…", progress = true, error = true)
    is PrActionOutcome.Rejected -> PrPendingText(outcome.message.takeIf { it.isNotBlank() } ?: "The server rejected this action.", progress = false, error = true)
}

/** Only web links leave the app. */
internal fun isOpenableUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

/**
 * Joins one diff [page] after [base] into a single document the viewer can show: file indexes, row indexes and row ids of the page are
 * shifted past [base]'s. Each page is parsed on its own, so its numbering starts at zero.
 */
internal fun appendDiffPage(base: DiffDocument?, page: DiffDocument): DiffDocument {
    if (base == null || (base.files.isEmpty() && base.rows.isEmpty())) return page.copy(truncated = page.truncated || base?.truncated == true)
    val fileShift = base.files.size
    val rowShift = base.rows.size
    val idShift = (base.rows.maxOfOrNull { it.id } ?: -1L) + 1L
    val rows = page.rows.map { row ->
        val file = if (row.fileIndex >= 0) row.fileIndex + fileShift else row.fileIndex
        when (row) {
            is DiffRow.FileHeader -> row.copy(id = row.id + idShift, fileIndex = file)
            is DiffRow.Hunk -> row.copy(id = row.id + idShift, fileIndex = file)
            is DiffRow.Line -> row.copy(id = row.id + idShift, fileIndex = file)
            is DiffRow.Notice -> row.copy(id = row.id + idShift, fileIndex = file)
        }
    }
    val files = page.files.map { f: DiffFile ->
        f.copy(index = f.index + fileShift, firstRowIndex = f.firstRowIndex + rowShift, endRowIndex = f.endRowIndex + rowShift)
    }
    return DiffDocument(
        files = base.files + files, rows = base.rows + rows,
        truncated = base.truncated || page.truncated, rowCapHit = base.rowCapHit || page.rowCapHit,
    )
}
