package com.tajchert.trefoil.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*

/* ---------- Header ---------- */

@Composable
internal fun ThreadListHeader(state: ClientState, shell: ShellActions?, railLayout: Boolean, canCreate: Boolean, create: () -> Unit, onSearch: () -> Unit) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = if (railLayout) 20.dp else 14.dp, end = if (railLayout) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (railLayout) {
            Text(state.environment?.name ?: "Threads", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
                color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Box(Modifier.weight(1f)) { MachinePill(state, onClick = { shell?.onMachines?.invoke() }) }
        }
        IconButton(onClick = onSearch) { Icon(TfIcons.Search, "Search", Modifier.size(22.dp), tint = c.text) }
        if (railLayout) {
            Surface(onClick = create, enabled = canCreate, shape = RoundedCornerShape(14.dp), color = if (canCreate) c.accent else c.s2,
                contentColor = if (canCreate) c.onAccent else c.muted, modifier = Modifier.padding(start = 4.dp).size(44.dp).semantics { contentDescription = "New thread" }) {
                Box(contentAlignment = Alignment.Center) { Icon(TfIcons.Plus, null, Modifier.size(22.dp)) }
            }
        } else if (shell != null) ShellMenuButton(state, shell, c.text)
    }
}

/** Current machine: status dot + environment name + chevron. Opens Machines. */
@Composable
internal fun MachinePill(state: ClientState, onClick: () -> Unit) {
    val c = Tf.colors
    val status = machineStatus(state)
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = c.s2, contentColor = c.text,
        modifier = Modifier.semantics { contentDescription = "Switch machine"; stateDescription = "${state.environment?.name ?: "No machine"}, ${status.label}" }) {
        Row(Modifier.height(40.dp).padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(status.color(c))
            Text(state.environment?.name ?: "Machines", Modifier.weight(1f, fill = false), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(TfIcons.ChevronDown, null, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ThreadSearchField(query: String, onQuery: (String) -> Unit, close: () -> Unit) {
    val c = Tf.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).height(48.dp).background(c.s2, RoundedCornerShape(24.dp)).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(TfIcons.Search, null, Modifier.size(18.dp), tint = c.muted)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                textStyle = TrefoilType.preview.copy(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search threads", style = TrefoilType.preview.copy(fontSize = 15.sp), color = c.muted)
                    inner()
                })
        }
        IconButton(onClick = close) { Icon(TfIcons.Close, if (query.isEmpty()) "Close search" else "Clear search", Modifier.size(18.dp), tint = c.muted) }
    }
}

/* ---------- Chips ---------- */

@Composable
internal fun ThreadFilterChips(filter: ThreadFilter, counts: Map<ThreadFilter, Int>, projects: List<Project>, project: Project?, onFilter: (ThreadFilter) -> Unit, onProject: (String?) -> Unit) {
    val c = Tf.colors
    var projectsOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TfChip("All", { onFilter(ThreadFilter.All) }, selected = filter == ThreadFilter.All, count = counts[ThreadFilter.All])
        TfChip("Needs you", { onFilter(if (filter == ThreadFilter.NeedsYou) ThreadFilter.All else ThreadFilter.NeedsYou) }, selected = filter == ThreadFilter.NeedsYou,
            count = counts[ThreadFilter.NeedsYou], leading = { StatusDot(c.amber) })
        TfChip("Working", { onFilter(if (filter == ThreadFilter.Working) ThreadFilter.All else ThreadFilter.Working) }, selected = filter == ThreadFilter.Working,
            count = counts[ThreadFilter.Working], leading = { WorkingSpinner(size = 16.dp, animated = false) })
        if (projects.isNotEmpty()) Box {
            TfChip(project?.title ?: "Project", { projectsOpen = true }, selected = project != null,
                trailing = { Icon(TfIcons.ChevronDown, null, Modifier.size(14.dp)) })
            DropdownMenu(projectsOpen, { projectsOpen = false }) {
                DropdownMenuItem(text = { Text("All projects") }, onClick = { onProject(null); projectsOpen = false })
                projects.forEach { item ->
                    DropdownMenuItem(text = { Text(item.title) }, leadingIcon = { ProjectTile(item.title, size = 24.dp, key = item.id) },
                        onClick = { onProject(item.id); projectsOpen = false })
                }
            }
        }
    }
}

/* ---------- Sections ---------- */

/** Uppercase section label; tapping folds the section. Count + chevron only appear while folded. */
@Composable
internal fun ShelfLabel(title: String, count: Int, open: Boolean, dot: Color?, toggle: () -> Unit) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).semantics { contentDescription = "$title shelf"; stateDescription = if (open) "Expanded" else "Collapsed"; heading() }
        .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f), dot = dot)
        if (!open) {
            Text(count.toString(), style = TrefoilType.section, color = c.muted)
            Icon(TfIcons.ChevronRight, null, Modifier.padding(start = 4.dp).size(16.dp), tint = c.muted)
        }
    }
}

/** Settled / Archived: one quiet row with a count that expands in place. */
@Composable
internal fun FoldedShelfRow(title: String, count: Int, open: Boolean, toggle: () -> Unit) {
    val c = Tf.colors
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "fold")
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).semantics { contentDescription = "$title shelf"; stateDescription = if (open) "Expanded" else "Collapsed" }
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(TfIcons.Settled, null, Modifier.size(18.dp), tint = c.muted)
        Text(title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.muted)
        Text(count.toString(), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.muted.copy(alpha = .72f))
        Spacer(Modifier.weight(1f))
        Icon(TfIcons.ChevronRight, null, Modifier.size(18.dp).rotate(angle), tint = c.muted)
    }
}

/* ---------- Rows ---------- */

/** What the right side of a row shows: spinner for work, dots for done/error, otherwise just time. */
internal enum class RowMark { None, Working, Done, Error }

internal fun threadRowMark(thread: ThreadSummary): RowMark = when (threadIndicator(thread).kind) {
    "working" -> RowMark.Working
    "error" -> RowMark.Error
    "ready", "waiting" -> if (thread.latestRunCompletedAt != null || thread.status.equals("completed", ignoreCase = true)) RowMark.Done else RowMark.None
    else -> RowMark.None
}

/** Right-aligned row time: work duration while working, otherwise time since the last update. */
internal fun threadRowTime(thread: ThreadSummary, now: Long): String {
    if (threadIndicator(thread).kind == "working") threadWorkStartedAt(thread)?.let(::validThreadInstant)?.let { return formatWorkDuration(now - it) }
    return relativeThreadTime(thread.updatedAt ?: thread.latestRunCompletedAt ?: thread.createdAt, now)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThreadRow(state: ClientState, thread: ThreadSummary, project: Project?, now: Long, selected: Boolean,
                       onAction: (ThreadTarget, ThreadAction, String?) -> Unit, select: () -> Unit) {
    val c = Tf.colors
    val indicator = threadIndicator(thread)
    val mark = threadRowMark(thread)
    val title = thread.title.ifBlank { "Untitled conversation" }
    var menu by remember { mutableStateOf(false) }
    val hasMenu = state.environment != null
    Box {
        Row(Modifier.fillMaxWidth().background(if (selected) c.s2 else Color.Transparent)
            .semantics {
                this.selected = selected
                if (hasMenu) customActions = listOf(CustomAccessibilityAction("Thread actions for ${thread.title}") { menu = true; true })
            }
            .combinedClickable(onClickLabel = "Open thread", onLongClickLabel = if (hasMenu) "Thread actions" else null,
                onLongClick = if (hasMenu) ({ menu = true }) else null, onClick = select)
            .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            ProjectTile(project?.title ?: "?", size = 40.dp, key = project?.id ?: thread.projectId)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.weight(1f), style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RowTime(thread, indicator, mark, now)
                }
                val preview = thread.latestMessage.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                val needs = indicator.kind in setOf("attention", "input")
                when {
                    preview != null -> Text(preview, style = TrefoilType.preview, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    needs || indicator.kind == "error" -> Text(indicator.label, style = TrefoilType.preview, color = if (needs) c.amber else c.red, maxLines = 1)
                }
                // Project name keeps its full width; only the branch gives way. The agent mark sits in a fixed slot at the row's bottom-right.
                Row(Modifier.padding(top = 6.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(project?.title ?: "Project", style = TrefoilType.meta, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        thread.branch?.takeIf { it.isNotBlank() }?.let { branch ->
                            Icon(TfIcons.Branch, null, Modifier.size(14.dp), tint = c.muted)
                            Text(branch, Modifier.weight(1f, fill = false), style = TrefoilType.mono, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    ThreadProviderStack(threadProviders(thread, state.providers), state.providers)
                }
            }
        }
        if (hasMenu) ThreadActionsDropdown(state, thread, onAction, menu, { menu = it }, offset = DpOffset(74.dp, 0.dp))
    }
}

@Composable
private fun RowTime(thread: ThreadSummary, indicator: ThreadIndicator, mark: RowMark, now: Long) {
    val c = Tf.colors
    val time = threadRowTime(thread, now)
    val status = if (indicator.kind == "ready") "" else threadStatusLabel(thread, now)
    val description = when { status.isBlank() -> time; time.isBlank() || status.endsWith(time) -> status; else -> "$status, $time" }
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (mark) {
            RowMark.Working -> WorkingSpinner(size = 16.dp)
            RowMark.Done -> StatusDot(c.accent)
            RowMark.Error -> StatusDot(c.red)
            RowMark.None -> {}
        }
        if (time.isNotEmpty()) Text(time, style = TrefoilType.meta, color = if (mark == RowMark.Working) c.blue else c.muted, maxLines = 1)
    }
}

/* ---------- New + empty ---------- */

@Composable
internal fun NewThreadFab(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val shape = RoundedCornerShape(18.dp)
    Surface(onClick = onClick, enabled = enabled, shape = shape, color = if (enabled) c.accent else c.s2, contentColor = if (enabled) c.onAccent else c.muted,
        modifier = modifier.shadow(if (enabled) 8.dp else 0.dp, shape).semantics { contentDescription = "New thread"; role = Role.Button }) {
        Row(Modifier.height(56.dp).padding(start = 18.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TfIcons.Plus, null, Modifier.size(22.dp))
            Text("New", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun ThreadListEmpty(searching: Boolean) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (searching) Icon(TfIcons.Search, null, Modifier.size(32.dp), tint = c.muted)
        else TrefoilMark(Modifier.size(40.dp), color = c.muted.copy(alpha = .6f))
        Text(if (searching) "No matching threads" else "Your next idea starts here", Modifier.padding(top = 16.dp), style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
        Text(if (searching) "Try another title, project or filter." else "Start a thread in one of your projects.", Modifier.padding(top = 6.dp),
            style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
    }
}
