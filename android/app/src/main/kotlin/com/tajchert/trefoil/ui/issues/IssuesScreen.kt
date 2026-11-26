package com.tajchert.trefoil.ui.issues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.IssueDetailUiState
import com.tajchert.trefoil.IssueListUiState
import com.tajchert.trefoil.IssuesController
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.maxContentWidth
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the list asks of its owner. [onBack] null leaves the leave arrow out (beside the rail there is none). */
class IssueListActions(
    val onQueryChange: (IssueQuery) -> Unit, val onOpen: (IssueSummary) -> Unit, val onRefresh: () -> Unit,
    val onLoadMore: () -> Unit, val onRetry: () -> Unit, val onBack: (() -> Unit)? = null,
)

/** Clock for the relative "updated" labels; screenshots pin it. */
internal val LocalIssueClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

private val ContentMaxWidth = 720.dp
private const val MAX_ROW_LABELS = 3
private const val LOAD_MORE_AHEAD = 6

/** Short age such as "now", "5m", "3h", "2d", "4w"; older than a year (or unparseable) shows the date, or nothing. */
internal fun issueAge(iso: String, nowMillis: Long): String {
    val then = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return ""
    val seconds = ((nowMillis - then) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 7 * 86_400 -> "${seconds / 86_400}d"
        seconds < 365 * 86_400L -> "${seconds / (7 * 86_400)}w"
        else -> Instant.ofEpochMilli(then).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    }
}

/** Up to two letters of a login or name: "ada-l" is "AL", "bob" is "B". */
internal fun issueInitials(actor: IssueActor): String {
    val source = actor.name?.takeIf { it.isNotBlank() } ?: actor.login
    val parts = source.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    val letters = if (parts.size >= 2) parts.take(2).map { it.first() } else listOfNotNull(parts.firstOrNull()?.first())
    return letters.joinToString("").uppercase().ifEmpty { "?" }
}

/** Label names to offer: the selected ones first, then those on the loaded rows, each once. */
internal fun issueLabelOptions(state: IssueListUiState): List<String> =
    (state.query.labels + state.entries.flatMap { row -> row.labels.map { it.name } }).filter { it.isNotBlank() }.distinct()

/** Assignee logins to offer: the selected one first, then those on the loaded rows, each once. */
internal fun issueAssigneeOptions(state: IssueListUiState): List<String> =
    (listOfNotNull(state.query.assignee) + state.entries.flatMap { row -> row.assignees.map { it.login } }).filter { it.isNotBlank() }.distinct()

internal fun issueQueryIsDefault(query: IssueQuery) = query == IssueQuery()

/**
 * The cross-project issue list: header, search, filter chips, then rows with pull to refresh and paging. Stateless apart from the
 * search text being typed; the controller debounces it. [selected] marks the open issue beside a detail pane.
 */
@Composable
fun IssuesScreen(
    state: IssueListUiState, projects: List<Project>, selected: IssueRef?, actions: IssueListActions, modifier: Modifier = Modifier,
) {
    val c = Tf.colors
    Column(modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (actions.onBack != null) TfIconButton(TfIcons.Back, "Back to threads", actions.onBack, iconSize = 22.dp, tint = c.text)
            Text("Issues", Modifier.weight(1f).padding(start = if (actions.onBack != null) 4.dp else 12.dp).semantics { heading() }, style = TrefoilType.rowTitle, color = c.text, maxLines = 1)
            TfIconButton(TfIcons.Refresh, "Refresh issues", actions.onRefresh, enabled = state.live && !state.refreshing && !state.loading, iconSize = 22.dp, tint = c.text)
        }
        SearchField(state.query.text) { actions.onQueryChange(state.query.copy(text = it)) }
        FilterBar(state, projects, actions.onQueryChange)
        HorizontalDivider(color = c.line)
        Banners(state, actions)
        PullToRefreshBox(state.refreshing, { if (state.live) actions.onRefresh() }, Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.entries.isEmpty() -> EmptyState(state, actions)
                else -> Rows(state, selected, actions)
            }
        }
    }
}

@Composable
private fun SearchField(text: String, onText: (String) -> Unit) {
    val c = Tf.colors
    // The text being typed lives here; the controller sees it after the debounce. A reset from outside (Clear filters) replaces it.
    var typed by remember { mutableStateOf(text) }
    LaunchedEffect(text) { if (text != typed.trim()) typed = text }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).height(44.dp).background(c.s2, RoundedCornerShape(22.dp)).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(TfIcons.Search, null, Modifier.size(18.dp), tint = c.muted)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(typed, { typed = it; onText(it) }, Modifier.fillMaxWidth().semantics { contentDescription = "Search issues" }, singleLine = true,
                textStyle = TrefoilType.preview.copy(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    if (typed.isEmpty()) Text("Search issues", style = TrefoilType.preview.copy(fontSize = 15.sp), color = c.muted)
                    inner()
                })
        }
        if (typed.isNotEmpty()) TfIconButton(TfIcons.Close, "Clear search", { typed = ""; onText("") }, iconSize = 18.dp, tint = c.muted)
    }
}

@Composable
private fun FilterBar(state: IssueListUiState, projects: List<Project>, onQuery: (IssueQuery) -> Unit) {
    val query = state.query
    val default = IssueQuery()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChipMenu("State: " + stateLabel(query.state), query.state != default.state) { close ->
            IssueListState.entries.forEach { Choice(stateLabel(it), query.state == it) { close(); onQuery(query.copy(state = it)) } }
        }
        FilterChipMenu(if (query.involvement == IssueInvolvement.All) "Involvement" else involvementLabel(query.involvement), query.involvement != default.involvement) { close ->
            IssueInvolvement.entries.forEach { Choice(involvementLabel(it), query.involvement == it) { close(); onQuery(query.copy(involvement = it)) } }
        }
        val project = projects.firstOrNull { it.id == query.projectId }
        FilterChipMenu(if (query.projectId == null) "Project" else project?.title ?: "Project", query.projectId != null) { close ->
            Choice("All projects", query.projectId == null) { close(); onQuery(query.copy(projectId = null)) }
            projects.forEach { p -> Choice(p.title, query.projectId == p.id) { close(); onQuery(query.copy(projectId = p.id)) } }
        }
        val labels = issueLabelOptions(state)
        FilterChipMenu(if (query.labels.isEmpty()) "Labels" else "Labels (${query.labels.size})", query.labels.isNotEmpty()) { _ ->
            if (labels.isEmpty()) Text("No labels on the loaded issues", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = TrefoilType.meta, color = Tf.colors.muted)
            labels.forEach { name ->
                val on = name in query.labels
                Choice(name, on) { onQuery(query.copy(labels = if (on) query.labels - name else query.labels + name)) }
            }
            if (query.labels.isNotEmpty()) Choice("Clear labels", false) { onQuery(query.copy(labels = emptyList())) }
        }
        val assignees = issueAssigneeOptions(state)
        FilterChipMenu(query.assignee?.let { "Assignee: $it" } ?: "Assignee", query.assignee != null) { close ->
            Choice("Anyone", query.assignee == null) { close(); onQuery(query.copy(assignee = null)) }
            assignees.forEach { login -> Choice(login, query.assignee == login) { close(); onQuery(query.copy(assignee = login)) } }
        }
        FilterChipMenu(sortLabel(query.sort), query.sort != default.sort) { close ->
            IssueSort.entries.forEach { Choice(sortLabel(it), query.sort == it) { close(); onQuery(query.copy(sort = it)) } }
        }
    }
}

private fun stateLabel(state: IssueListState) = when (state) { IssueListState.Open -> "Open"; IssueListState.Closed -> "Closed"; IssueListState.All -> "All" }
private fun involvementLabel(involvement: IssueInvolvement) = when (involvement) {
    IssueInvolvement.All -> "Anyone"; IssueInvolvement.Assigned -> "Assigned to me"; IssueInvolvement.Authored -> "Created by me"; IssueInvolvement.Mentioned -> "Mentioning me"
}
private fun sortLabel(sort: IssueSort) = when (sort) { IssueSort.Updated -> "Recently updated"; IssueSort.Created -> "Newest" }

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onClick,
        trailingIcon = if (selected) ({ Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = Tf.colors.accent) }) else null,
        modifier = Modifier.semantics { if (selected) stateDescription = "Selected" },
    )
}

/** One filter chip (48dp touch target, 32dp pill) that opens a menu; the pill is accent-tinted while its filter is not the default. */
@Composable
private fun FilterChipMenu(label: String, active: Boolean, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val c = Tf.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.heightIn(min = 48.dp).focusRing().handPointer().clickable(role = Role.Button, onClickLabel = "Change filter") { open = true }, contentAlignment = Alignment.Center) {
            Row(Modifier.height(32.dp).background(if (active) c.accentSoft else c.s2, RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, Modifier.widthIn(max = 180.dp), style = TrefoilType.meta.copy(color = if (active) c.text else c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(TfIcons.ChevronDown, null, Modifier.size(16.dp), tint = c.muted)
            }
        }
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

@Composable
private fun Banners(state: IssueListUiState, actions: IssueListActions) {
    var errorsOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!state.live && state.entries.isNotEmpty()) BannerRow("Not live. Showing the last loaded list; connect to refresh.")
        // With no rows held the failure fills the body instead (EmptyState); with rows it is a banner above them.
        if (state.failure != null && state.entries.isNotEmpty()) BannerRow(state.failureMessage ?: "Could not refresh issues.",
            action = if (state.live && state.failure != IssueFailure.NotSupported) "Try again" else null, onAction = actions.onRetry)
        if (state.errors.isNotEmpty()) {
            val n = state.errors.size
            BannerRow(if (n == 1) "1 project could not be loaded" else "$n projects could not be loaded", action = if (errorsOpen) "Hide" else "Details", onAction = { errorsOpen = errorsOpen.not() })
            if (errorsOpen) state.errors.forEach { e ->
                Text("${e.projectTitle}: ${e.message}", Modifier.fillMaxWidth().padding(horizontal = 4.dp), style = TrefoilType.meta, color = Tf.colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (!state.live && state.entries.isNotEmpty() || state.errors.isNotEmpty() || (state.failure != null && state.entries.isNotEmpty())) Spacer(Modifier.height(6.dp))
}

@Composable
private fun BannerRow(message: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().background(c.amberSoft, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = TrefoilType.meta, color = c.text)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
private fun EmptyState(state: IssueListUiState, actions: IssueListActions) {
    val c = Tf.colors
    val unconfigured = state.trackers.filter { !it.configured }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(Modifier.maxContentWidth(ContentMaxWidth), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                state.failure != null -> {
                    Text(if (state.failure == IssueFailure.NotConnected) "Connect to view issues" else "Issues unavailable", style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
                    state.failureMessage?.takeIf { state.failure != IssueFailure.NotConnected }?.let { Text(it, style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center) }
                    if (state.live && state.failure != IssueFailure.NotSupported) OutlinedButton(actions.onRetry) { Text("Try again") }
                }
                state.trackers.isNotEmpty() && state.trackers.none { it.configured } -> {
                    Text("Connect trackers in T3 on your computer", Modifier.semantics { heading() }, style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
                    unconfigured.forEach { t ->
                        Text(listOf(t.host.ifBlank { t.tracker }, t.detail?.takeIf { it.isNotBlank() }).filterNotNull().joinToString(": "), style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
                    }
                }
                state.loaded -> {
                    Text("No issues match", Modifier.semantics { heading() }, style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
                    if (!issueQueryIsDefault(state.query)) {
                        Text("Try other filters or a different search.", style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
                        OutlinedButton({ actions.onQueryChange(IssueQuery()) }) { Text("Clear filters") }
                    }
                }
                else -> Text("No issues to show", style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Rows(state: IssueListUiState, selected: IssueRef?, actions: IssueListActions) {
    val c = Tf.colors
    val listState = rememberLazyListState()
    val selectedKey = selected?.let { IssuesController.rowKey(it) }
    val canLoadMore by rememberUpdatedState(state.canLoadMore)
    val onLoadMore by rememberUpdatedState(actions.onLoadMore)
    val count = state.entries.size
    LaunchedEffect(listState, count) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= count - LOAD_MORE_AHEAD }
            .distinctUntilChanged().collect { nearEnd -> if (nearEnd && canLoadMore) onLoadMore() }
    }
    LaunchedEffect(state.nextCursors, state.loadingMore, state.moreFailure) {
        if (canLoadMore && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= count - LOAD_MORE_AHEAD) onLoadMore()
    }
    LazyColumn(Modifier.fillMaxSize(), listState) {
        items(state.entries, key = { IssuesController.rowKey(it.ref) }) { issue ->
            IssueRow(issue, selectedKey != null && selectedKey == IssuesController.rowKey(issue.ref)) { actions.onOpen(issue) }
            HorizontalDivider(color = c.line)
        }
        item("footer") {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    state.loadingMore -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    state.moreFailure != null -> {
                        Text(state.moreFailure, style = TrefoilType.meta, color = c.red, textAlign = TextAlign.Center)
                        if (state.live) OutlinedButton(actions.onRetry) { Text("Try again") }
                    }
                    state.capped -> Text("Refine filters to see more", style = TrefoilType.meta, color = c.muted, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueRow(issue: IssueSummary, selected: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    val now = LocalIssueClock.current()
    val age = issueAge(issue.updatedAt, now)
    val description = buildString {
        append(if (issue.state.open) "Open" else "Closed").append(", ").append(issue.displayKey).append(", ").append(issue.title)
        if (issue.commentCount > 0) append(", ").append(issue.commentCount).append(if (issue.commentCount == 1) " comment" else " comments")
        if (age.isNotEmpty()) append(", updated ").append(age)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(if (selected) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .focusRing().handPointer().clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; if (selected) stateDescription = "Open in detail" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(if (issue.state.open) c.green else c.blue, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(listOf(issue.displayKey, issue.projectTitle.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "), style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(issue.title, style = TrefoilType.preview, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (issue.labels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                issue.labels.take(MAX_ROW_LABELS).forEach { label ->
                    Row(Modifier.background(c.s2, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(labelColor(label.color) ?: c.muted, CircleShape))
                        Text(label.name, Modifier.padding(start = 5.dp).widthIn(max = 120.dp), style = TrefoilType.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (issue.labels.size > MAX_ROW_LABELS) Text("+${issue.labels.size - MAX_ROW_LABELS}", Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = TrefoilType.meta, color = c.muted)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (age.isNotEmpty()) Text(age, style = TrefoilType.meta, color = c.muted, maxLines = 1)
            if (issue.commentCount > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(TfIcons.Threads, null, Modifier.size(13.dp), tint = c.muted)
                Text(issue.commentCount.toString(), style = TrefoilType.meta, color = c.muted, maxLines = 1)
            }
            issue.assignees.firstOrNull()?.let { who ->
                Box(Modifier.size(22.dp).background(c.s3, CircleShape), contentAlignment = Alignment.Center) {
                    Text(issueInitials(who), style = TrefoilType.meta.copy(fontSize = 10.sp, lineHeight = 12.sp), color = c.text, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Everything the shell needs from the issue controllers: the list and detail state and what the screens call. [onOpenIssue] starts the
 * detail controller for an issue of an environment; [onOpenThread] selects a linked thread of that environment (false when it cannot be
 * opened). The defaults make previews and tests that do not touch issues compile unchanged.
 */
class IssuesUi(
    val list: IssueListUiState = IssueListUiState(), val detail: IssueDetailUiState = IssueDetailUiState(),
    val onOpenList: () -> Unit = {}, val onQuery: (IssueQuery) -> Unit = {}, val onRefreshList: () -> Unit = {},
    val onLoadMore: () -> Unit = {}, val onRetryList: () -> Unit = {},
    val onOpenIssue: (environmentId: String, ref: IssueRef, key: IssueKey) -> Unit = { _, _, _ -> },
    val onRefreshDetail: () -> Unit = {}, val onRetryDetail: () -> Unit = {}, val onLoadOlder: () -> Unit = {},
    val onOpenThread: suspend (environmentId: String, threadId: String) -> Boolean = { _, _ -> false },
)
