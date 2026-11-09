/* Presentation adapted from T3 Code mobile threadListV2 (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

internal data class ThreadShelf(val title: String, val threads: List<ThreadSummary>)
internal data class ThreadIndicator(val label: String, val kind: String)

internal fun threadIndicator(thread: ThreadSummary): ThreadIndicator {
    val requests = listOfNotNull(thread.pendingRequestKind, thread.pendingChildRequestKind).map { it.lowercase(Locale.ROOT) }
    val status = (if (thread.completionHeldBackgroundWork && thread.status != "failed") "idle" else thread.activityRunStatus ?: thread.status).lowercase(Locale.ROOT)
    return when {
        requests.any { it.contains("approval") } || status.contains("approval") -> ThreadIndicator("Needs approval", "attention")
        requests.any { it.contains("input") } || status in setOf("waiting_for_input", "awaiting_input") -> ThreadIndicator("Needs input", "input")
        requests.isNotEmpty() -> ThreadIndicator("Needs attention", "attention")
        thread.blockedOnThreadWait == true -> ThreadIndicator("Waiting", "waiting")
        status in setOf("preparing", "queued", "starting", "running", "waiting", "working", "streaming") -> ThreadIndicator(if (status == "queued") "Queued" else "Working", "working")
        status in setOf("failed", "error", "interrupted") -> ThreadIndicator(if (status == "interrupted") "Interrupted" else "Failed", "error")
        status == "idle" -> ThreadIndicator("Waiting", "waiting")
        else -> ThreadIndicator("Ready", "ready")
    }
}
private fun timestamp(value: String?): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
internal fun relativeThreadTime(value: String?, now: Long = System.currentTimeMillis()): String {
    val time = timestamp(value)
    if (time == 0L) return ""
    val minutes = ((now - time).coerceAtLeast(0) / 60_000)
    return when { minutes < 1 -> "now"; minutes < 60 -> "${minutes}m"; minutes < 1440 -> "${minutes / 60}h"; minutes < 10080 -> "${minutes / 1440}d"; else -> "${minutes / 10080}w" }
}
/** T3 hides child conversations before searching, counting or partitioning its lists. */
internal fun threadListRows(threads: List<ThreadSummary>): List<ThreadSummary> {
    val active = threads.filter { !it.archived && it.archivedAt == null && it.deletedAt == null }
    val byId = active.associateBy { it.id }
    val requests = mutableMapOf<String, String>()
    fun flag(id: String, kind: String) { if (requests[id] != "approval") requests[id] = kind }
    active.forEach { child ->
        val kind = child.pendingRequestKind?.takeIf { it != "auth_refresh" } ?: return@forEach
        val parentId = child.parentThreadId ?: return@forEach
        val request = if (kind == "user_input") "user_input" else "approval"
        if (child.sideChat) { flag(parentId, request); return@forEach }
        if (child.relationshipToParent != "subagent" || child.creationSource == "provider" || request == "user_input") return@forEach
        val live = if (child.activityRunStatusPresent) child.activityRunStatus != null
            else child.status in setOf("preparing", "starting", "running", "waiting")
        if (!live) return@forEach
        var parent: String? = parentId
        val seen = mutableSetOf(child.id)
        while (parent != null && seen.add(parent)) {
            flag(parent, request)
            val row = byId[parent]
            parent = if (row?.relationshipToParent == "subagent" || row?.sideChat == true) row.parentThreadId else null
        }
    }
    return threads.filter { it.deletedAt == null && it.relationshipToParent != "subagent" && !it.sideChat }.map {
        val request = requests[it.id] ?: it.pendingChildRequestKind
        if (request == it.pendingChildRequestKind) it else it.copy(pendingChildRequestKind = request)
    }
}

private fun parsedTimestamp(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
private fun threadIsSnoozed(thread: ThreadSummary, now: Long): Boolean {
    if ((parsedTimestamp(thread.snoozedUntil) ?: return false) <= now) return false
    val request = thread.pendingRequestKind
    if (request != null && request != "user_input") return false
    if (request == "user_input") {
        val question = parsedTimestamp(thread.pendingRequestCreatedAt)
        val snoozed = parsedTimestamp(thread.snoozedAt)
        if (question == null || snoozed == null || question > snoozed) return false
    }
    if (thread.pendingChildRequestKind != null || thread.pendingGuestMessageCount > 0) return false
    val runtimeStatus = if (thread.completionHeldBackgroundWork && thread.status != "failed") "idle" else thread.activityRunStatus ?: thread.status
    if (runtimeStatus in setOf("failed", "error")) {
        val failed = if (thread.latestRunId != null && thread.status == "failed") parsedTimestamp(thread.latestRunCompletedAt) else parsedTimestamp(thread.updatedAt)
        val snoozed = parsedTimestamp(thread.snoozedAt)
        if (snoozed == null || failed != null && failed > snoozed) return false
    }
    return true
}

internal fun threadIsWorking(thread: ThreadSummary): Boolean {
    if (thread.pendingRequestKind != null && thread.pendingRequestKind != "auth_refresh" || thread.pendingChildRequestKind != null) return false
    val status = if (thread.completionHeldBackgroundWork && thread.status != "failed") "idle" else thread.activityRunStatus ?: thread.status
    val active = status in setOf("preparing", "queued", "starting", "running", "waiting", "working", "streaming")
    val idle = status == "idle" && (thread.latestRunId != null || thread.activeProviderThreadId != null || thread.completionHeldBackgroundWork)
    if (!active && !idle) return false
    val terminalRun = thread.latestRunId != null && thread.status !in setOf("preparing", "queued", "starting", "running", "waiting") && thread.activeRunId != thread.latestRunId
    return !(thread.interactionMode == "plan" && thread.hasActionableProposedPlan && terminalRun)
}

internal fun threadShelves(threads: List<ThreadSummary>, now: Long = System.currentTimeMillis()): List<ThreadShelf> {
    val groups = threadListRows(threads).groupBy {
        when {
            it.archived || it.archivedAt != null -> "Archived"
            threadIsSnoozed(it, now) -> "Snoozed"
            it.settledOverride == "settled" -> "Settled"
            it.pinnedAt != null -> "Pinned"
            threadIsWorking(it) -> "Working"
            else -> "Threads"
        }
    }
    return listOf("Pinned", "Threads", "Working", "Snoozed", "Settled", "Archived").mapNotNull { title ->
        val rows = groups[title] ?: return@mapNotNull null
        val sorted = if (title == "Pinned") rows.sortedWith(compareBy<ThreadSummary> { it.pinOrderKey == null }.thenBy { it.pinOrderKey.orEmpty() }.thenByDescending { timestamp(it.createdAt) }.thenBy { it.id })
        else rows.sortedWith(compareByDescending<ThreadSummary> {
            when (title) {
                "Working" -> maxOf(timestamp(if (it.latestUserAuthoredMessageAtPresent || it.latestUserAuthoredMessageAt != null) it.latestUserAuthoredMessageAt else it.latestRunRequestedAt), timestamp(it.createdAt))
                "Settled" -> parsedTimestamp(it.settledAt) ?: listOfNotNull(parsedTimestamp(it.latestUserMessageAt), parsedTimestamp(it.latestRunRequestedAt), parsedTimestamp(it.latestRunStartedAt), parsedTimestamp(it.latestRunCompletedAt)).maxOrNull() ?: timestamp(it.updatedAt)
                "Snoozed" -> -timestamp(it.snoozedUntil)
                "Threads" -> maxOf(timestamp(it.createdAt), timestamp(it.unsettledAt), timestamp(it.latestRunRequestedAt), timestamp(it.latestRunCompletedAt))
                else -> timestamp(it.updatedAt)
            }
        }.thenBy { it.id })
        ThreadShelf(title, sorted)
    }
}

@Composable
internal fun ThreadList(state: ClientState, select: (String) -> Unit, create: () -> Unit, onAction: (ThreadTarget, ThreadAction, String?) -> Unit = { _, _, _ -> }) {
    var query by rememberSaveable(state.environment?.id) { mutableStateOf("") }
    var projectId by rememberSaveable(state.environment?.id) { mutableStateOf<String?>(null) }
    var projectsOpen by remember { mutableStateOf(false) }
    val projects = remember(state.projects) { state.projects.associateBy { it.id } }
    LaunchedEffect(projects, projectId) { if (projectId != null && projectId !in projects) projectId = null }
    val listed = remember(state.threads) { threadListRows(state.threads) }
    val filtered = remember(query, listed, projects, projectId) {
        listed.filter { thread -> (projectId == null || thread.projectId == projectId) && (query.isBlank() || listOf(thread.title, thread.latestMessage, projects[thread.projectId]?.title.orEmpty(), thread.branch.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }) }
    }
    var expanded by rememberSaveable(state.environment?.id) { mutableStateOf(listOf("Pinned", "Threads", "Working")) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shelves = remember(filtered, now) { threadShelves(filtered, now) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val liveTiming = remember(filtered) { filtered.any { thread ->
        (threadIndicator(thread).kind == "working" && threadWorkStartedAt(thread) != null) ||
            (threadIndicator(thread).kind == "waiting" && validThreadInstant(thread.latestRunCompletedAt) != null)
    } }
    LaunchedEffect(lifecycle, liveTiming, state.threads) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (coroutineContext.isActive) {
                val current = System.currentTimeMillis()
                now = current
                delay(nextThreadClockDelay(current, liveTiming, state.threads.mapNotNull { validThreadInstant(it.snoozedUntil) }))
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Threads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${listed.count { !it.archived && it.archivedAt == null }} conversations · ${state.projects.size} projects", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = create, enabled = state.canWrite && state.projects.isNotEmpty() && state.models.isNotEmpty()) { Icon(Icons.Default.Edit, "New conversation") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search threads", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } },
            singleLine = true, shape = RoundedCornerShape(16.dp))
        if (state.busy && state.threads.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { projectsOpen = true }) { Text(projects[projectId]?.title ?: "All projects") }
            DropdownMenu(projectsOpen, { projectsOpen = false }) {
                DropdownMenuItem(text = { Text("All projects") }, onClick = { projectId = null; projectsOpen = false })
                state.projects.forEach { project -> DropdownMenuItem(text = { Text(project.title) }, onClick = { projectId = project.id; projectsOpen = false }) }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (shelves.isEmpty() && !state.busy) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (query.isBlank()) Icons.Default.Forum else Icons.Default.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (query.isBlank()) "Your next idea starts here" else "No matching threads", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text(if (query.isBlank()) "Start a conversation in one of your projects." else "Try another title or project name.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            shelves.forEach { shelf ->
                item(key = "shelf:${shelf.title}") {
                    ShelfHeading(shelf.title, shelf.threads.size, query.isNotBlank() || shelf.title in expanded) {
                        expanded = if (shelf.title in expanded) expanded - shelf.title else expanded + shelf.title
                    }
                }
                items(shelf.threads.filter { query.isNotBlank() || shelf.title in expanded }, key = { "thread:${it.id}" }) { thread ->
                    ThreadRow(thread, projects[thread.projectId], state.providers, now, { ThreadActionsMenu(state, thread, onAction) }) { select(thread.id) }
                }
            }
        }
    }
}

@Composable
private fun ShelfHeading(title: String, count: Int, open: Boolean, toggle: () -> Unit) {
    Surface(onClick = toggle, modifier = Modifier.semantics { contentDescription = "$title shelf"; stateDescription = if (open) "Expanded" else "Collapsed" }, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, Modifier.padding(start = 6.dp).weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun ThreadRow(thread: ThreadSummary, project: Project?, providers: List<ProviderInstance>, now: Long, actions: @Composable () -> Unit, select: () -> Unit) {
    val indicator = threadIndicator(thread)
    val tint = when (indicator.kind) {
        "attention" -> Color(0xFFB7791F)
        "input" -> MaterialTheme.colorScheme.tertiary
        "working" -> if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF7CC8F8) else Color(0xFF1677A8)
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(onClick = select, shape = RoundedCornerShape(20.dp), color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp, end = 10.dp).size(7.dp).background(tint.copy(alpha = if (indicator.kind == "ready") .35f else 1f), CircleShape))
            Column(Modifier.weight(1f)) {
                val providerStack = threadProviders(thread, providers)
                val hasContext = !thread.branch.isNullOrBlank() || !thread.model.isNullOrBlank()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(thread.title.ifBlank { "Untitled conversation" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = if (indicator.kind in setOf("working", "waiting")) FontWeight.Normal else FontWeight.Medium)
                    actions()
                }
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(project?.title ?: "Project", Modifier.padding(start = 4.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!hasContext && providerStack.isNotEmpty()) {
                        ThreadProviderStack(providerStack, providers)
                        Spacer(Modifier.width(8.dp))
                    }
                    val label = if (indicator.kind == "ready") relativeThreadTime(thread.updatedAt ?: thread.createdAt, now) else threadStatusLabel(thread, now)
                    if (label.isNotBlank()) {
                        if (indicator.kind == "working") CircularProgressIndicator(Modifier.padding(start = 8.dp).size(10.dp), strokeWidth = 1.5.dp, color = tint)
                        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = tint)
                    }
                }
                if (hasContext) Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!thread.branch.isNullOrBlank() || !thread.model.isNullOrBlank()) Text(thread.branch?.takeIf { it.isNotBlank() }?.let { "⑂ $it" } ?: thread.model.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Spacer(Modifier.weight(1f))
                    ThreadProviderStack(providerStack, providers)
                }
            }
        }
    }
}
