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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

internal data class ThreadShelf(val title: String, val threads: List<ThreadSummary>)
internal data class ThreadIndicator(val label: String, val kind: String)

internal fun threadIndicator(thread: ThreadSummary): ThreadIndicator {
    val request = thread.pendingRequestKind.orEmpty().lowercase(Locale.ROOT)
    val status = (thread.activityRunStatus ?: thread.status).lowercase(Locale.ROOT)
    return when {
        request.contains("approval") || status.contains("approval") -> ThreadIndicator("Needs approval", "attention")
        request.contains("input") || status in setOf("waiting_for_input", "awaiting_input") -> ThreadIndicator("Needs input", "input")
        request.isNotEmpty() -> ThreadIndicator("Needs attention", "attention")
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
internal fun threadShelves(threads: List<ThreadSummary>, now: Long = System.currentTimeMillis()): List<ThreadShelf> {
    val groups = threads.groupBy {
        when {
            it.archived || it.archivedAt != null -> "Archived"
            it.pinnedAt != null -> "Pinned"
            timestamp(it.snoozedUntil) > now -> "Snoozed"
            threadIndicator(it).kind in setOf("attention", "input") -> "Threads"
            it.settledOverride == "settled" || (it.settledAt != null && it.settledOverride != "active") -> "Settled"
            threadIndicator(it).kind == "working" -> "Working"
            else -> "Threads"
        }
    }
    return listOf("Pinned", "Threads", "Working", "Snoozed", "Settled", "Archived").mapNotNull { title ->
        val rows = groups[title] ?: return@mapNotNull null
        val sorted = if (title == "Pinned") rows.sortedWith(compareBy<ThreadSummary> { it.pinOrderKey == null }.thenBy { it.pinOrderKey.orEmpty() }.thenByDescending { timestamp(it.createdAt) }.thenBy { it.id })
        else rows.sortedWith(compareByDescending<ThreadSummary> {
            when (title) {
                "Working" -> maxOf(timestamp(it.latestUserAuthoredMessageAt ?: it.latestUserMessageAt ?: it.latestRunRequestedAt), timestamp(it.createdAt))
                "Settled" -> timestamp(it.settledAt).takeIf { time -> time != 0L } ?: maxOf(timestamp(it.latestUserMessageAt), timestamp(it.updatedAt), timestamp(it.createdAt))
                "Snoozed" -> -timestamp(it.snoozedUntil)
                else -> maxOf(timestamp(it.updatedAt), timestamp(it.latestUserMessageAt), timestamp(it.createdAt), timestamp(it.unsettledAt), timestamp(it.latestRunRequestedAt), timestamp(it.latestRunCompletedAt))
            }
        }.thenBy { it.id })
        ThreadShelf(title, sorted)
    }
}

@Composable
internal fun ThreadList(state: ClientState, select: (String) -> Unit, create: () -> Unit) {
    var query by rememberSaveable(state.environment?.id) { mutableStateOf("") }
    val projects = remember(state.projects) { state.projects.associateBy { it.id } }
    val filtered = remember(query, state.threads, projects) {
        state.threads.filter { thread -> query.isBlank() || listOf(thread.title, thread.latestMessage, projects[thread.projectId]?.title.orEmpty(), thread.branch.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) } }
    }
    var expanded by rememberSaveable(state.environment?.id) { mutableStateOf(listOf("Pinned", "Threads", "Working")) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shelves = remember(filtered, now) { threadShelves(filtered, now) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Threads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${state.threads.count { !it.archived && it.archivedAt == null }} conversations · ${state.projects.size} projects", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = create, enabled = state.canWrite && state.projects.isNotEmpty() && state.models.isNotEmpty()) { Icon(Icons.Default.Edit, "New conversation") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search threads", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } },
            singleLine = true, shape = RoundedCornerShape(16.dp))
        if (state.busy && state.threads.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
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
                    ThreadRow(thread, projects[thread.projectId], state.selectedThreadId == thread.id, now) { select(thread.id) }
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
private fun ThreadRow(thread: ThreadSummary, project: Project?, selected: Boolean, now: Long, select: () -> Unit) {
    val indicator = threadIndicator(thread)
    val tint = when (indicator.kind) {
        "attention" -> Color(0xFFB7791F)
        "input" -> MaterialTheme.colorScheme.tertiary
        "working" -> if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF7CC8F8) else Color(0xFF1677A8)
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(onClick = select, shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).semantics { this.selected = selected }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp, end = 10.dp).size(7.dp).background(if (selected) MaterialTheme.colorScheme.primary else tint.copy(alpha = if (indicator.kind == "ready") .35f else 1f), CircleShape))
            Column(Modifier.weight(1f)) {
                Text(thread.title.ifBlank { "Untitled conversation" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = if (indicator.kind in setOf("working", "waiting") && !selected) FontWeight.Normal else FontWeight.Medium)
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(project?.title ?: "Project", Modifier.padding(start = 4.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val label = if (indicator.kind in setOf("ready", "waiting")) relativeThreadTime(thread.updatedAt ?: thread.createdAt, now) else indicator.label
                    if (label.isNotBlank()) {
                        if (indicator.kind == "working") CircularProgressIndicator(Modifier.padding(start = 8.dp).size(10.dp), strokeWidth = 1.5.dp, color = tint)
                        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = tint)
                    }
                }
                if (!thread.branch.isNullOrBlank()) Text("⑂ ${thread.branch}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
