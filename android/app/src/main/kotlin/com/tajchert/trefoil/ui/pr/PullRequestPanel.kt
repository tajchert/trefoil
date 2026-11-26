package com.tajchert.trefoil.ui.pr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrCheck
import com.tajchert.trefoil.core.model.PrCheckStatus
import com.tajchert.trefoil.core.model.PrDiffPage
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PrMergeability
import com.tajchert.trefoil.core.model.PrPanelState
import com.tajchert.trefoil.core.model.PullRequestKey
import com.tajchert.trefoil.core.model.ThreadPullRequest
import com.tajchert.trefoil.ui.StatusDot
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfChip
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.WorkingSpinner
import com.tajchert.trefoil.ui.adaptive.TfAdaptiveSheet
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Pull request panel of the selected thread: Overview, Checks, Files and Actions. It paints the thread link's snapshot first, then the
 * server's detail, and revalidates through the session. Pull request titles, branches, check text and diffs are repository content:
 * never log them. Every mutation is sent once and never resent; an unknown outcome is shown, not retried.
 */

/** What the app gives the pull request panel and header chip: the session's reads and mutations, by pull request. */
@Immutable
class PullRequestUi(
    /** `TrefoilClient.prPanel`: the panel state of one pull request. Collecting it keeps the refresh stream open. */
    val panel: (PullRequestKey) -> StateFlow<PrPanelState> = { MutableStateFlow(PrPanelState()) },
    /** `TrefoilClient.refreshPullRequest`: prefetch / revalidate; a no-op when not live. */
    val refresh: suspend (PullRequestKey) -> Unit = {},
    val loadDiff: suspend (PullRequestKey, String?) -> PrDiffPage = { _, _ -> error("Pull request diffs are unavailable") },
    /** Fire-and-forget: the session sends it on a scope that outlives the panel; a second call for a busy [PullRequestKey] is dropped. */
    val runAction: (PullRequestKey, PrAction, PrMergeMethod?) -> Unit = { _, _, _ -> },
    val comment: (PullRequestKey, String) -> Unit = { _, _ -> },
    /** Pull requests with a send in flight (disables every action for them, also after the panel is reopened). */
    val busy: StateFlow<Set<PullRequestKey>> = MutableStateFlow(emptySet()),
    /** The last refusal before sending, per pull request. */
    val errors: StateFlow<Map<PullRequestKey, String>> = MutableStateFlow(emptyMap()),
    val dismissError: (PullRequestKey) -> Unit = {},
)

/** Sections of the panel, saved by name. */
internal enum class PrSection(val label: String) { Overview("Overview"), Checks("Checks"), Files("Files"), Actions("Actions") }

/** Everything the stateless panel needs besides the link and the panel state. */
@Immutable
internal class PrPanelEnv(
    val live: Boolean,
    val nowMillis: Long,
    val actions: PrActionsModel,
    val files: PrFilesState,
    val checksSupported: Boolean = true,
    val localError: String? = null,
    val busy: Boolean = false,
)

/**
 * Stateful panel for [state]'s selected thread: one pull request at a time, with a small switcher when the thread links several.
 * [onClose] adds a close button (the sheet); null where the side panel has its own.
 */
@Composable
internal fun PullRequestPanel(state: ClientState, ui: PullRequestUi, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val links = state.threadPullRequests
    if (links.isEmpty()) { EmptyPanel(modifier); return }
    var selected by rememberSaveable(state.selectedThreadId) { mutableStateOf(0) }
    val link = links[selected.coerceIn(0, links.lastIndex)]
    val key = link.key
    val environmentId = state.environment?.id
    val flow = remember(ui, environmentId, key) { ui.panel(key) }
    val panel by flow.collectAsStateWithLifecycle()
    val live = state.connection == ConnectionStatus.Connected
    // `ui.panel(key)` already reads when live and unwatched, so only a reconnect (not live -> live) revalidates here; the session
    // ignores it while not live.
    val wasLive = remember(ui, environmentId, key) { booleanArrayOf(live) }
    LaunchedEffect(key, live) {
        if (shouldRefreshOnLive(wasLive[0], live)) runCatching { ui.refresh(key) }
        wasLive[0] = live
    }

    val scope = rememberCoroutineScope()
    val loadDiff = rememberUpdatedState(ui.loadDiff)
    // A new head commit is a new diff: the loaded pages go away with the loader.
    val headSha = panel.detail?.headSha
    val loader = remember(environmentId, key, headSha) { PrFilesLoader(scope) { cursor -> loadDiff.value(key, cursor) } }
    DisposableEffect(loader) { onDispose { loader.cancel() } }
    var section by rememberSaveable { mutableStateOf(PrSection.Overview.name) }
    val current = PrSection.entries.firstOrNull { it.name == section } ?: PrSection.Overview
    val wantsFiles = current == PrSection.Files && panel.detail?.canViewDiff == true
    LaunchedEffect(loader, wantsFiles, live) { if (wantsFiles && live) loader.ensure() }

    val busyKeys by ui.busy.collectAsStateWithLifecycle()
    val errors by ui.errors.collectAsStateWithLifecycle()
    // A refusal is shown while this pull request's panel is open; leaving the panel clears it (a send in flight is not affected).
    DisposableEffect(ui, key) { onDispose { ui.dismissError(key) } }
    val handlers = remember(ui, key) {
        PrActionHandlers(onRun = { action, method -> ui.runAction(key, action, method) }, onComment = { body -> ui.comment(key, body) })
    }
    val nowMillis = remember(panel.lastUpdatedMillis, live) { System.currentTimeMillis() }
    val env = PrPanelEnv(
        live = live, nowMillis = nowMillis, actions = prActionsModel(panel, state, key.number), files = loader.state,
        checksSupported = state.pullRequestChecksSupported, localError = errors[key], busy = key in busyKeys,
    )
    PullRequestPanelContent(
        link = link, panel = panel, env = env, section = current, onSection = { section = it.name }, handlers = handlers,
        onLoadMore = loader::loadMore, onRetryFiles = loader::retry, others = links, selectedIndex = selected.coerceIn(0, links.lastIndex),
        onSelectLink = { selected = it }, onClose = onClose, modifier = modifier,
    )
}

@Composable
private fun EmptyPanel(modifier: Modifier) {
    val c = Tf.colors
    Box(modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Text("This thread has no pull request.", style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
    }
}

/** Phone and narrow windows: the panel as an adaptive sheet (bottom sheet, or a dialog from 600dp). */
@Composable
internal fun PullRequestSheet(state: ClientState, ui: PullRequestUi, dismiss: () -> Unit) {
    TfAdaptiveSheet(onDismiss = dismiss, dialogMaxWidth = PR_SHEET_DIALOG_WIDTH, dialogMaxHeight = PR_SHEET_DIALOG_HEIGHT, insetContent = false) {
        PullRequestPanel(state, ui, onClose = dismiss, modifier = Modifier.fillMaxHeight(PR_SHEET_FRACTION))
    }
}

private val PR_SHEET_DIALOG_WIDTH = 640.dp
private val PR_SHEET_DIALOG_HEIGHT = 720.dp
private const val PR_SHEET_FRACTION = 0.92f

/**
 * Stateless panel: header, section tabs and the open section. The unavailable state replaces the body. Separate from the sheet chrome so
 * screenshots and tests render it directly.
 */
@Composable
internal fun PullRequestPanelContent(
    link: ThreadPullRequest?, panel: PrPanelState, env: PrPanelEnv, section: PrSection, onSection: (PrSection) -> Unit,
    handlers: PrActionHandlers, onLoadMore: () -> Unit, onRetryFiles: () -> Unit, modifier: Modifier = Modifier,
    others: List<ThreadPullRequest> = emptyList(), selectedIndex: Int = 0, onSelectLink: (Int) -> Unit = {}, onClose: (() -> Unit)? = null,
    onOpenUrl: (String) -> Unit = {},
) {
    val c = Tf.colors
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { url -> if (isOpenableUrl(url)) { runCatching { uriHandler.openUri(url) }; onOpenUrl(url) } }
    val overview = prOverview(link, panel)
    val url = panel.detail?.url ?: link?.url
    var wrap by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(c.bg)) {
        Header(link?.key?.number, overview, url, onClose) { url?.let(open) }
        if (others.size > 1) LinkSwitcher(others, selectedIndex, onSelectLink)
        val unavailable = panel.unavailableReason
        if (unavailable != null) {
            UnavailableBody(unavailable, Modifier.weight(1f))
        } else if (overview == null) {
            LoadingOrError(panel, env, Modifier.weight(1f))
        } else {
            SectionTabs(section, onSection, panel, env)
            HorizontalDivider(color = c.line)
            Notices(panel, env)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    PrSection.Overview -> OverviewSection(overview, panel, env, open, onSection)
                    PrSection.Checks -> ChecksSection(panel, env, open)
                    PrSection.Files -> PullRequestFiles(
                        files = env.files, live = env.live, available = panel.detail?.canViewDiff == true, wrap = wrap, onWrapChange = { wrap = it },
                        onLoadMore = onLoadMore, onRetry = onRetryFiles,
                    )
                    PrSection.Actions -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PullRequestActionsSection(env.actions, panel.pendingAction, env.localError, env.busy, handlers, prKey = link?.key)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(number: Int?, overview: PrOverview?, url: String?, onClose: (() -> Unit)?, onOpen: () -> Unit) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(overview?.title ?: number?.let { "Pull request #$it" } ?: "Pull request", style = TrefoilType.rowTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (overview != null && number != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatePill(overview.state)
                    Text("#$number", style = TrefoilType.mono, color = c.muted)
                }
            }
        }
        if (isOpenableUrl(url)) TfIconButton(TfIcons.Link, "Open pull request in browser", onOpen, Modifier.size(48.dp), iconSize = 22.dp, tooltip = "Open in browser")
        if (onClose != null) TfIconButton(TfIcons.Close, "Close pull request", onClose, Modifier.size(48.dp), iconSize = 22.dp, tooltip = "Close")
    }
}

@Composable
internal fun StatePill(state: PrDisplayState, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val tone = prStateColor(state)
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusDot(tone, size = 7.dp)
        Text(state.label, style = TrefoilType.meta.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
    }
}

@Composable
internal fun prStateColor(state: PrDisplayState): androidx.compose.ui.graphics.Color {
    val c = Tf.colors
    return when (state) {
        PrDisplayState.Open -> c.green
        PrDisplayState.Draft -> c.muted
        PrDisplayState.Merged -> c.blue
        PrDisplayState.Closed -> c.red
    }
}

@Composable
private fun LinkSwitcher(links: List<ThreadPullRequest>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEachIndexed { i, l -> TfChip("#${l.key.number}", onClick = { onSelect(i) }, selected = i == selected, dense = true) }
    }
}

@Composable
private fun SectionTabs(section: PrSection, onSection: (PrSection) -> Unit, panel: PrPanelState, env: PrPanelEnv) {
    val filesCount = panel.detail?.changedFiles
    val checks = panel.visibleChecks.count { !it.placeholder }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrSection.entries.forEach { s ->
            val count = when (s) { PrSection.Checks -> checks.takeIf { it > 0 }; PrSection.Files -> filesCount?.takeIf { it > 0 }; else -> null }
            TfChip(s.label, onClick = { onSection(s) }, selected = s == section, count = count, dense = true)
        }
    }
}

/** Stale, offline and refresh-failure notes above the section. */
@Composable
private fun Notices(panel: PrPanelState, env: PrPanelEnv) {
    val c = Tf.colors
    val stale = lastUpdatedLabel(panel, env.live, env.nowMillis)
    val error = panel.error
    if (stale == null && error == null && !panel.refreshing) return
    Column(Modifier.fillMaxWidth().background(c.s2).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (stale != null) Text("$stale. Offline: showing the last known state.", style = TrefoilType.meta, color = c.muted)
        if (error != null) Text("Could not refresh: $error", style = TrefoilType.meta, color = c.amber)
        if (panel.refreshing && stale == null && error == null) Text("Refreshing…", style = TrefoilType.meta, color = c.muted)
    }
}

@Composable
private fun UnavailableBody(reason: com.tajchert.trefoil.core.model.PrUnavailableReason, modifier: Modifier) {
    val c = Tf.colors
    val (title, body) = unavailableMessage(reason)
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(body, style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
    }
}

/** Nothing to paint yet: a spinner while the first read is out, the failure otherwise. */
@Composable
private fun LoadingOrError(panel: PrPanelState, env: PrPanelEnv, modifier: Modifier) {
    val c = Tf.colors
    val error = panel.error
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when {
            error != null -> {
                Text("Could not load the pull request", style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
                Spacer(Modifier.size(8.dp))
                Text(error, style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
            }
            !env.live && !panel.answered -> Text("Offline. Reconnect to load this pull request.", style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
            else -> {
                CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text("Loading pull request…", style = TrefoilType.meta, color = c.muted)
            }
        }
    }
}

@Composable
private fun OverviewSection(overview: PrOverview, panel: PrPanelState, env: PrPanelEnv, open: (String) -> Unit, onSection: (PrSection) -> Unit) {
    val c = Tf.colors
    val tone = checksTone(panel.snapshot?.checksState, panel.visibleChecks)
    val mergeability = mergeabilityLabel(overview.mergeability, overview.rebaseRequired, overview.behindBy, overview.baseRef)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Fact("Branches") {
            Text("${overview.headRef} → ${overview.baseRef}", style = TrefoilType.mono, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        overview.author?.let { Fact("Author") { Text(it, style = TrefoilType.preview, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        if (overview.additions != null || overview.changedFiles != null) Fact("Changes") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                overview.changedFiles?.let { Text(if (it == 1) "1 file" else "$it files", style = TrefoilType.meta, color = c.text) }
                overview.additions?.let { Text("+$it", style = TrefoilType.mono, color = c.green) }
                overview.deletions?.let { Text("−$it", style = TrefoilType.mono, color = c.red) }
            }
        }
        if (mergeability != null) Fact("Merge") {
            val bad = overview.mergeability == PrMergeability.Conflicting || overview.rebaseRequired
            Text(mergeability, style = TrefoilType.preview, color = if (bad) c.amber else c.text)
        }
        overview.reviewDecision?.let { Fact("Review") { Text(reviewDecisionLabel(it), style = TrefoilType.preview, color = c.text) } }
        if (tone != PrChecksTone.None) {
            Fact("Checks", onClick = { onSection(PrSection.Checks) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChecksDot(tone)
                    Text(checksSummary(panel.visibleChecks) ?: tone.label, style = TrefoilType.preview, color = c.text)
                }
            }
        }
        if (overview.fromSnapshot) Text(
            if (env.live) "Loading the latest details…" else "Showing the last known details.",
            style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val url = panel.detail?.url
        if (isOpenableUrl(url)) Box(Modifier.fillMaxWidth().padding(16.dp)) {
            OutlinedButton(onClick = { open(url!!) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Open on the code host") }
        }
    }
}

@Composable
private fun Fact(label: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Tf.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.focusRing().handPointer().clickable(role = Role.Button, onClickLabel = "Show $label", onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label.uppercase(), Modifier.width(88.dp).padding(top = 3.dp), style = TrefoilType.section, color = c.muted, maxLines = 1)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
internal fun ChecksDot(tone: PrChecksTone, modifier: Modifier = Modifier) {
    val c = Tf.colors
    when (tone) {
        PrChecksTone.None -> Unit
        PrChecksTone.Passing -> StatusDot(c.green, modifier)
        PrChecksTone.Failing -> StatusDot(c.red, modifier)
        PrChecksTone.Pending -> StatusDot(c.amber, modifier)
    }
}

@Composable
private fun ChecksSection(panel: PrPanelState, env: PrPanelEnv, open: (String) -> Unit) {
    val c = Tf.colors
    val all = panel.visibleChecks
    val ordered = remember(all) { orderChecks(all) }
    val hasPlaceholder = all.any { it.placeholder }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val summary = checksSummary(all)
        when {
            ordered.isEmpty() -> Text(
                when {
                    hasPlaceholder -> "The code host could not list the checks of this pull request."
                    !env.checksSupported && panel.checks == null -> "This server does not report checks."
                    else -> "No checks reported."
                },
                style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(16.dp),
            )
            else -> {
                if (summary != null) Text(summary, style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp))
                ordered.forEachIndexed { i, check ->
                    CheckRow(check, open)
                    if (i != ordered.lastIndex) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 48.dp))
                }
                if (hasPlaceholder) Text("Some checks could not be listed by the code host.", style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun CheckRow(check: PrCheck, open: (String) -> Unit) {
    val c = Tf.colors
    val url = check.url?.takeIf { isOpenableUrl(it) }
    val status = checkStatusLabel(check.status)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (url != null) Modifier.focusRing().handPointer().clickable(role = Role.Button, onClickLabel = "Open check details") { open(url) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "${check.name}, $status${if (check.required) ", required" else ""}" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) { CheckStatusIcon(check.status) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(check.name, style = TrefoilType.preview, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(status, "Required".takeIf { check.required }).joinToString(" · ")
            Text(detail, style = TrefoilType.meta, color = c.muted)
            check.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = TrefoilType.meta, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (url != null) Icon(TfIcons.Link, null, Modifier.size(16.dp).padding(top = 2.dp), tint = c.muted)
    }
}

@Composable
private fun CheckStatusIcon(status: PrCheckStatus) {
    val c = Tf.colors
    when (status) {
        PrCheckStatus.Success -> Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.green)
        PrCheckStatus.Failure -> Icon(TfIcons.Close, null, Modifier.size(18.dp), tint = c.red)
        PrCheckStatus.ActionRequired -> StatusDot(c.amber, size = 10.dp)
        PrCheckStatus.Pending -> WorkingSpinner(size = 16.dp, color = c.amber, animated = !LocalInspectionMode.current)
        PrCheckStatus.Cancelled, PrCheckStatus.Skipped, PrCheckStatus.Neutral -> StatusDot(c.muted, size = 8.dp)
    }
}

/** The header chip's look: state dot, number and a checks dot. Tapping opens the panel. */
@Composable
internal fun PullRequestChipContent(model: PrChipModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val shape = RoundedCornerShape(16.dp)
    androidx.compose.material3.Surface(
        onClick = onClick, shape = shape, color = c.s2, contentColor = c.text,
        modifier = modifier.padding(end = 2.dp).focusRing(shape).handPointer().semantics { contentDescription = model.description; role = Role.Button },
    ) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            model.state?.let { StatusDot(prStateColor(it), size = 8.dp) } ?: Icon(TfIcons.Branch, null, Modifier.size(14.dp))
            Text("#${model.number}", style = TrefoilType.meta.copy(color = c.text), maxLines = 1)
            if (model.extra > 0) Text("+${model.extra}", style = TrefoilType.meta.copy(color = c.muted), maxLines = 1)
            ChecksDot(model.checks, Modifier.clip(CircleShape))
        }
    }
}

/**
 * Header chip of the selected thread's pull request. Showing it asks the session to prefetch the pull request once per link (again after
 * a reconnect), so the panel opens already warm.
 */
@Composable
internal fun PullRequestChip(links: List<ThreadPullRequest>, live: Boolean, ui: PullRequestUi, onClick: () -> Unit) {
    val key = links.firstOrNull()?.key
    LaunchedEffect(key, live) { if (key != null && live) runCatching { ui.refresh(key) } }
    val model = remember(links) { prChipModel(links) } ?: return
    PullRequestChipContent(model, onClick)
}

/** Whether the panel revalidates now: only when the session turns live after the panel was open (a reconnect), not on first entry. */
internal fun shouldRefreshOnLive(wasLive: Boolean, live: Boolean): Boolean = live && !wasLive
