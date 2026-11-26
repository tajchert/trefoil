package com.tajchert.trefoil.ui.issues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.IssueDetailUiState
import com.tajchert.trefoil.IssueLinkAction
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.MarkdownContent
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.maxContentWidth
import com.tajchert.trefoil.ui.isSafeLink
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class IssueDetailActions(
    val onBack: () -> Unit, val onOpenUrl: (String) -> Unit, val onRefresh: () -> Unit, val onRetry: () -> Unit,
    val onLoadOlder: () -> Unit, val onOpenThread: (IssueLinkedThread) -> Unit,
)

/** Zone for the timestamps on this screen; screenshots pin it. */
internal val LocalIssueTimeZone = compositionLocalOf { ZoneId.systemDefault() }

private val ContentMaxWidth = 720.dp
private val TimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** One row of the Activity list: comments and timeline events merged by time. */
internal sealed interface IssueActivityItem {
    val id: String
    val createdAt: String
    data class Comment(val comment: IssueComment) : IssueActivityItem {
        override val id get() = "c:" + comment.id
        override val createdAt get() = comment.createdAt
    }
    data class Event(val event: IssueEvent) : IssueActivityItem {
        override val id get() = "e:" + event.id
        override val createdAt get() = event.createdAt
    }
}

/** Comments and events by time, oldest first; equal or unparseable times keep comments before events. */
internal fun issueActivity(detail: IssueDetail): List<IssueActivityItem> =
    (detail.comments.distinctBy { it.id }.map { IssueActivityItem.Comment(it) } + detail.events.distinctBy { it.id }.map { IssueActivityItem.Event(it) })
        .sortedBy { parseIssueInstant(it.createdAt)?.toEpochMilli() ?: Long.MAX_VALUE }

/** ISO-8601 instant with `Z` or a numeric offset (GitLab and self-hosted trackers send offsets); null when unparseable. */
internal fun parseIssueInstant(value: String?): Instant? =
    value?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

internal fun issueEventText(event: IssueEvent): String {
    val who = event.actor?.login ?: "Someone"
    val detail = event.detail?.takeIf { it.isNotBlank() }
    return when (event.kind) {
        IssueEventKind.Closed -> "$who closed this"
        IssueEventKind.Reopened -> "$who reopened this"
        IssueEventKind.Labeled -> "$who added the label ${detail ?: ""}".trim()
        IssueEventKind.Unlabeled -> "$who removed the label ${detail ?: ""}".trim()
        IssueEventKind.Assigned -> "$who assigned ${detail ?: "this"}"
        IssueEventKind.Unassigned -> "$who unassigned ${detail ?: "this"}"
        IssueEventKind.Renamed -> "$who renamed this" + (detail?.let { ": $it" } ?: "")
        IssueEventKind.Referenced -> "$who referenced this" + (detail?.let { ": $it" } ?: "")
        IssueEventKind.StateChanged -> "$who changed the state" + (detail?.let { " to $it" } ?: "")
    }
}

private fun formatTime(value: String?, zone: ZoneId): String =
    parseIssueInstant(value)?.let { TimeFormat.format(it.atZone(zone)) } ?: ""

/**
 * Stateless issue detail: toolbar, header, labels, assignees, body, Activity (comments and events by time, oldest first),
 * linked pull requests and linked threads. Tracker links open only through [IssueDetailActions.onOpenUrl], and only http(s).
 * [toolbarExtras] sits in the toolbar before Refresh and Open in browser.
 */
@Composable
fun IssueDetailContent(
    state: IssueDetailUiState, actions: IssueDetailActions, modifier: Modifier = Modifier,
    toolbarExtras: @Composable RowScope.() -> Unit = {},
) {
    val c = Tf.colors
    val detail = state.detail
    val openUrl: (String) -> Unit = { url -> if (isSafeLink(url)) actions.onOpenUrl(url) }
    val uriHandler = remember(actions) { object : UriHandler { override fun openUri(uri: String) = openUrl(uri) } }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Column(modifier.fillMaxSize().background(c.bg)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TfIconButton(TfIcons.Back, "Back", actions.onBack, iconSize = 22.dp, tint = c.text)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(detail?.summary?.displayKey ?: "Issue", Modifier.semantics { heading() }, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail?.summary?.projectTitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                toolbarExtras()
                TfIconButton(TfIcons.Link, "Open in browser", { detail?.summary?.url?.let(openUrl) }, enabled = detail != null && isSafeLink(detail.summary.url), iconSize = 22.dp, tint = c.text)
                TfIconButton(TfIcons.Refresh, "Refresh", actions.onRefresh, enabled = state.live && detail != null && !state.refreshing, iconSize = 22.dp, tint = c.text)
            }
            if (state.refreshing || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            HorizontalDivider(color = c.line)
            if (detail == null) DetailPlaceholder(state, actions)
            else DetailBody(state, detail, actions, openUrl)
        }
    }
}

/**
 * Toolbar entry for the thread that is open: "Link" or "Unlink". While a command is [pending] it shows progress and ignores taps.
 * Draws nothing for [IssueLinkAction.None]. Meant for [IssueDetailContent]'s `toolbarExtras`.
 */
@Composable
fun RowScope.IssueLinkToolbarButton(action: IssueLinkAction, pending: Boolean, onLink: () -> Unit, onUnlink: () -> Unit) {
    if (action == IssueLinkAction.None) return
    val c = Tf.colors
    val linking = action == IssueLinkAction.Link
    val description = if (linking) "Link to current thread" else "Unlink from current thread"
    TextButton(
        onClick = { if (!pending) { if (linking) onLink() else onUnlink() } }, enabled = !pending,
        modifier = Modifier.handPointer(!pending).testTag(if (linking) "issue-link" else "issue-unlink").semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        if (pending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.muted)
        else Text(if (linking) "Link" else "Unlink", style = TrefoilType.meta, color = if (linking) c.text else c.red)
    }
}

/**
 * Toolbar entry that starts a thread from the open issue: the New thread sheet opens on the issue's project with the first message
 * prefilled. Meant for [IssueDetailContent]'s `toolbarExtras`; [enabled] follows connection, thread writes and the issue's environment.
 */
@Composable
fun RowScope.IssueStartThreadButton(enabled: Boolean, onStart: () -> Unit) {
    TextButton(
        onClick = onStart, enabled = enabled,
        modifier = Modifier.handPointer(enabled).testTag("issue-start-thread").semantics { contentDescription = "Start thread from this issue" },
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) { Text("Start thread", style = TrefoilType.meta, color = if (enabled) Tf.colors.text else Tf.colors.muted, maxLines = 1) }
}

@Composable
private fun DetailPlaceholder(state: IssueDetailUiState, actions: IssueDetailActions) {
    val c = Tf.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.failure != null -> Column(Modifier.maxContentWidth(ContentMaxWidth), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (state.failure == IssueFailure.NotConnected) "Connect to view this issue" else "Issue unavailable",
                    style = TrefoilType.rowTitle, color = c.text,
                )
                state.failureMessage?.takeIf { state.failure != IssueFailure.NotConnected }?.let { Text(it, style = TrefoilType.preview, color = c.muted) }
                if (state.live && state.failure != IssueFailure.NotSupported) OutlinedButton(actions.onRetry) { Text("Try again") }
            }
            else -> Text("No issue selected", style = TrefoilType.preview, color = c.muted)
        }
    }
}

@Composable
private fun DetailBody(state: IssueDetailUiState, detail: IssueDetail, actions: IssueDetailActions, openUrl: (String) -> Unit) {
    val c = Tf.colors
    val zone = LocalIssueTimeZone.current
    val summary = detail.summary
    val activity = remember(detail) { issueActivity(detail) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!state.live) item("notlive") { Banner("Not live. Showing the last loaded copy; connect to refresh.") }
        if (state.failure != null) item("failure") {
            Banner(state.failureMessage ?: "Could not refresh this issue.", action = if (state.live && state.failure != IssueFailure.NotSupported) "Try again" else null, onAction = actions.onRetry)
        }
        item("header") { Header(summary, zone) }
        if (summary.labels.isNotEmpty()) item("labels") { Labels(summary.labels) }
        if (summary.assignees.isNotEmpty()) item("assignees") {
            Text("Assigned to " + summary.assignees.joinToString(", ") { it.login }, style = TrefoilType.meta, color = c.muted)
        }
        item("body") {
            if (detail.body.isBlank()) Text("No description provided.", style = TrefoilType.preview, color = c.muted)
            else Box(Modifier.fillMaxWidth()) { MarkdownContent(detail.body) }
        }
        item("activity-title") { Section("ACTIVITY") }
        if (detail.commentsCursor != null || state.commentsCapped) item("older") { OlderComments(state, detail, actions, openUrl) }
        if (activity.isEmpty()) item("activity-empty") { Text("No activity yet.", style = TrefoilType.preview, color = c.muted) }
        items(activity, key = { it.id }) { item ->
            when (item) {
                is IssueActivityItem.Comment -> CommentRow(item.comment, zone)
                is IssueActivityItem.Event -> EventRow(item.event, zone, openUrl)
            }
        }
        if (detail.linkedPullRequests.isNotEmpty()) {
            item("prs-title") { Section("LINKED PULL REQUESTS") }
            items(detail.linkedPullRequests.distinctBy { it.url }, key = { "pr:" + it.url }) { PullRequestRow(it, openUrl) }
        }
        if (state.linkedThreadsSupported && state.linkedThreads.isNotEmpty()) item("threads") {
            LinkedThreadsSection(state.linkedThreads, actions.onOpenThread)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.semantics { heading() }, style = TrefoilType.section, color = Tf.colors.muted)
}

@Composable
private fun Banner(message: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().background(c.amberSoft, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = TrefoilType.meta, color = c.text)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
private fun Header(summary: IssueSummary, zone: ZoneId) {
    val c = Tf.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (summary.state.open) c.green else c.blue
            Row(Modifier.background(tint.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tint, CircleShape))
                Text(summary.state.name, Modifier.padding(start = 6.dp), style = TrefoilType.meta, color = c.text, maxLines = 1)
            }
            Text(summary.displayKey, style = TrefoilType.mono, color = c.muted, maxLines = 1)
        }
        Text(summary.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = c.text)
        val meta = listOfNotNull(
            summary.author?.let { "by ${it.login}" },
            formatTime(summary.createdAt, zone).takeIf { it.isNotEmpty() }?.let { "opened $it" },
            formatTime(summary.updatedAt, zone).takeIf { it.isNotEmpty() }?.let { "updated $it" },
            summary.state.closeReason?.takeIf { !summary.state.open && it.isNotBlank() },
        )
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = TrefoilType.meta, color = c.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Labels(labels: List<IssueLabel>) {
    val c = Tf.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Row(Modifier.background(c.s2, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(labelColor(label.color) ?: c.muted, CircleShape))
                Text(label.name, Modifier.padding(start = 6.dp), style = TrefoilType.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `rrggbb` or `#rrggbb`; anything else is no colour. */
internal fun labelColor(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

@Composable
private fun OlderComments(state: IssueDetailUiState, detail: IssueDetail, actions: IssueDetailActions, openUrl: (String) -> Unit) {
    val c = Tf.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.commentsCapped) {
            Text("Showing the latest ${detail.comments.size} comments.", style = TrefoilType.meta, color = c.muted)
            OutlinedButton({ openUrl(detail.summary.url) }, enabled = isSafeLink(detail.summary.url)) {
                Text("Open on ${detail.summary.hostName.ifBlank { "tracker" }}")
            }
        } else {
            OutlinedButton(actions.onLoadOlder, enabled = state.live && !state.loadingOlder) {
                if (state.loadingOlder) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Load older comments")
            }
            if (!state.live) Text("Not live. Connect to load older comments.", style = TrefoilType.meta, color = c.muted)
            state.olderFailure?.let { Text(it, style = TrefoilType.meta, color = c.red) }
        }
    }
}

@Composable
private fun CommentRow(comment: IssueComment, zone: ZoneId) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(comment.author?.login ?: "Unknown", style = TrefoilType.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(formatTime(comment.createdAt, zone), style = TrefoilType.meta, color = c.muted, maxLines = 1)
        }
        if (comment.body.isBlank()) Text("No content.", style = TrefoilType.preview, color = c.muted) else MarkdownContent(comment.body)
    }
}

@Composable
private fun EventRow(event: IssueEvent, zone: ZoneId, openUrl: (String) -> Unit) {
    val c = Tf.colors
    val url = event.referenceUrl?.takeIf { isSafeLink(it) }
    Row(
        Modifier.fillMaxWidth().then(if (url != null) Modifier.handPointer(true).clickable(role = Role.Button) { openUrl(url) } else Modifier).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(c.muted, CircleShape))
        Text(issueEventText(event), Modifier.weight(1f), style = TrefoilType.meta, color = if (url != null) c.blue else c.muted)
        Text(formatTime(event.createdAt, zone), style = TrefoilType.meta, color = c.muted, maxLines = 1)
    }
}

@Composable
private fun PullRequestRow(pr: IssueLinkedPr, openUrl: (String) -> Unit) {
    val c = Tf.colors
    val safe = isSafeLink(pr.url)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (safe) Modifier.handPointer(true).clickable(role = Role.Button) { openUrl(pr.url) } else Modifier)
            .background(c.s1, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(pr.title, style = TrefoilType.preview, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val state = listOfNotNull(pr.state.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }, "Draft".takeIf { pr.draft }, "Closes this issue".takeIf { pr.closesIssue })
        Text(listOf("${pr.repository}#${pr.number}") .plus(state).joinToString(" · "), style = TrefoilType.meta, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
