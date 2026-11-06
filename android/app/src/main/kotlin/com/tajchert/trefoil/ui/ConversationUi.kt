/* Conversation layout adapted from T3 Code's ThreadFeed (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun Conversation(state: ClientState, send: (String) -> Unit, interrupt: () -> Unit, respond: (String, String, Map<String, List<String>>) -> Unit, onLoadActivity: (String) -> Unit = {}) {
    if (state.selectedThreadId == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Forum, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Room for your next thought", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
                Text("Choose a conversation or start a new one.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }
    var draft by rememberSaveable(state.environment?.id, state.selectedThreadId) { mutableStateOf("") }
    var submitted by rememberSaveable(state.environment?.id, state.selectedThreadId) { mutableStateOf<String?>(null) }
    var messageIdsAtSend by rememberSaveable(state.environment?.id, state.selectedThreadId) { mutableStateOf(arrayListOf<String>()) }
    LaunchedEffect(state.messages) {
        val pending = submitted
        if (pending != null && state.messages.any { it.role == "user" && it.text == pending && it.id !in messageIdsAtSend }) {
            if (draft == pending) draft = ""
            submitted = null
        }
    }
    LaunchedEffect(state.error) { if (state.error != null) submitted = null }
    val entries = remember(state.messages, state.activities, state.activeRunId, state.runs) {
        foldConversationTimeline(conversationTimeline(state.messages, state.activities), state.activeRunId, state.runs)
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nearBottom by remember { derivedStateOf { !list.canScrollForward || (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 2 } }
    var following by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to nearBottom }.collect { (scrolling, bottom) -> if (scrolling) following = bottom }
    }
    LaunchedEffect(entries, state.requests, state.activeRunId) {
        if (following) {
            // Let LazyColumn measure new rows before scrolling to the trailing anchor.
            withFrameNanos { }
            if (list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    val thread = state.threads.find { it.id == state.selectedThreadId }
    val project = state.projects.find { it.id == thread?.projectId }
    val canLoadActivity = state.connection == ConnectionStatus.Connected && !state.busy
    val providerStack = thread?.let { threadProviders(it, state.providers) }.orEmpty()
    val workStartedAt = thread?.let(::threadWorkStartedAt)?.let(::validThreadInstant)
    val clock = rememberThreadWorkClock(state.activeRunId != null && workStartedAt != null)
    val workLabel = "Working" + if (workStartedAt != null) " ${formatWorkDuration(clock - workStartedAt)}" else ""
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (providerStack.isNotEmpty()) {
                ThreadProviderStack(providerStack, state.providers)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(thread?.title?.ifBlank { "Untitled conversation" } ?: "Conversation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (project != null) Text(project.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
            if (state.activeRunId != null) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Text(workLabel, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
                if (state.busy && entries.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!state.busy && entries.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("What would you like to build?", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                        Text("Send a message to get started.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is ConversationEntry.Message -> MessageRow(entry.message, entry.stacked,
                            showMetadata = if (entry.message.role == "user") {
                                (entries.getOrNull(entries.indexOf(entry) + 1) as? ConversationEntry.Message)?.message?.role != "user"
                            } else !entry.message.streaming && (state.activeRunId == null || (entry.message.runId != null && entry.message.runId != state.activeRunId)) &&
                                entry.message.id == state.messages.lastOrNull { it.role == "assistant" && it.runId == entry.message.runId }?.id)
                        is ConversationEntry.Activities -> ActivityStack(entry.activities, canLoadActivity = canLoadActivity, onLoadActivity = onLoadActivity)
                        is ConversationEntry.Work -> CompletedWork(entry, canLoadActivity, onLoadActivity)
                    }
                }
                if (state.activeRunId != null && state.messages.none { it.streaming }) item(key = "working") {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        Text("Working…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.requests, key = { "request:${it.id}" }) { RequestCard(it, state.canWrite && !state.busy, respond) }
                item(key = "bottom") { Spacer(Modifier.height(4.dp)) }
            }
            if (!nearBottom) FilledTonalButton(onClick = { following = true; scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) } }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Latest")
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (state.activeRunId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Next message will be queued", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = interrupt, enabled = state.canWrite && !state.busy) { Icon(Icons.Default.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Stop") }
                }
                if (!state.canWrite) Text("Read only · reconnect to continue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                if (submitted != null) Text("Waiting for server confirmation…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Message T3…") }, maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                    trailingIcon = {
                        FilledIconButton(onClick = { if (draft.isNotBlank() && submitted == null && state.canWrite && !state.busy) { messageIdsAtSend = ArrayList(state.messages.map { it.id }); submitted = draft; send(draft) } }, enabled = state.canWrite && !state.busy && draft.isNotBlank() && submitted == null, modifier = Modifier.padding(end = 6.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, if (state.activeRunId != null) "Queue message" else "Send message", Modifier.size(20.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageRow(message: ConversationMessage, stacked: Boolean, showMetadata: Boolean) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(top = if (stacked) 4.dp else 16.dp, bottom = if (user) 4.dp else 12.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) {
            Surface(shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth(.85f)) {
                SelectionContainer { Text(message.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge) }
            }
        } else {
            MarkdownContent(message.text, Modifier.fillMaxWidth())
        }
        if (message.streaming) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text("Writing…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else if (showMetadata) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                messageTime(message.updatedAt ?: message.createdAt)?.let { time -> Text((if (!user) "T3 · " else "") + time, Modifier.padding(top = 6.dp, end = if (user) 4.dp else 0.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!user) IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Icon(Icons.Default.ContentCopy, "Copy response", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private fun messageTime(value: String?): String? = timelineInstant(value)?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(it) }

@Composable
private fun CompletedWork(work: ConversationEntry.Work, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit) {
    var expanded by rememberSaveable(work.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse completed work" else "Expand completed work") { expanded = !expanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(work.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse completed work" else "Expand completed work", Modifier.size(20.dp))
        }
        if (expanded) Column(Modifier.padding(start = 12.dp)) {
            work.entries.forEach { entry ->
                key(entry.key) {
                    when (entry) {
                        is ConversationEntry.Message -> MessageRow(entry.message, false, false)
                        is ConversationEntry.Activities -> ActivityStack(entry.activities, initiallyExpanded = true, canLoadActivity = canLoadActivity, onLoadActivity = onLoadActivity)
                        is ConversationEntry.Work -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityStack(activities: List<ToolActivity>, initiallyExpanded: Boolean = false, canLoadActivity: Boolean = false, onLoadActivity: (String) -> Unit = {}) {
    var expanded by rememberSaveable(activities.first().id) { mutableStateOf(initiallyExpanded) }
    val running = activities.any { it.isRunning }
    val attention = activities.any { it.needsAttention }
    val visible = expanded || running || attention
    val label = when {
        attention -> "Activity needs attention"
        running -> "Working"
        activities.all { it.kind == "reasoning" } -> "Thought"
        activities.all { it.status in setOf("completed", "succeeded", "success", "done") } -> "Work completed"
        else -> "Activity"
    }
    Surface(Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse activity" else "Expand activity") { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (running) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                else Icon(if (attention) Icons.Default.ErrorOutline else activityIcon(activities.last()), null, Modifier.size(17.dp), tint = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(if (activities.size == 1) activityHeadline(activities.first()) else "$label · ${activities.size} activities", style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!visible) {
                        val command = activities.last().takeIf { it.kind == "command_execution" }?.input?.lineSequence()?.firstOrNull()?.trim()
                        val subtitle = command?.takeIf { it.isNotBlank() } ?: activities.last().title.takeIf { activities.size > 1 }
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (command != null) FontFamily.Monospace else null), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse activity" else "Expand activity", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (visible) activities.forEach { activity ->
                key(activity.id) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    ActivityDetail(activity, canLoadActivity, onLoadActivity)
                }
            }
        }
    }
}

private fun activityHeadline(activity: ToolActivity): String {
    if (activity.title.replace(' ', '_') != activity.kind) return activity.title
    return when (activity.kind) {
        "reasoning" -> if (activity.isRunning) "Thinking" else "Thought"
        "command_execution" -> "Run command"
        "file_change" -> "File changes"
        "file_search" -> "Search files"
        "web_search" -> "Search the web"
        else -> activity.title.replaceFirstChar { it.uppercase() }
    }
}

private fun activityIcon(activity: ToolActivity) = when (activity.kind) {
    "command_execution" -> Icons.Default.Terminal
    "file_change" -> Icons.Default.EditNote
    "file_read", "file" -> Icons.Default.Description
    "file_search", "web_search", "search" -> Icons.Default.Search
    "reasoning" -> Icons.Default.Psychology
    "proposed_plan" -> Icons.Default.Assignment
    "system_notice" -> Icons.Default.Info
    "notification" -> Icons.Default.NotificationsNone
    "compaction" -> Icons.Default.Compress
    "handoff", "subagent" -> Icons.Default.AccountTree
    else -> Icons.Default.Build
}

@Composable
private fun ActivityDetail(activity: ToolActivity, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val structured = listOfNotNull(activity.input, activity.output, activity.diffStr, activity.oldStr, activity.newStr, activity.searchResults, activity.failure).filter { it.isNotBlank() }
    val fallback = activity.detail.takeIf { it.isNotBlank() && structured.isEmpty() }
    val copyText = (structured + listOfNotNull(fallback)).distinct().joinToString("\n\n")
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activityIcon(activity), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(activityHeadline(activity), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (activity.status.isNotBlank()) Text(activity.status.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = if (activity.needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (copyText.isNotBlank()) IconButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Copy activity", Modifier.size(14.dp)) }
        }
        activity.fileName?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.labelSmall) } }
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (activity.kind in setOf("reasoning", "proposed_plan")) MarkdownContent(activity.output?.takeIf { it.isNotBlank() } ?: activity.detail)
            else SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activity.input?.takeIf { it.isNotBlank() }?.let {
                        Text(if (activity.kind == "command_execution") "$ $it" else it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    val details = listOfNotNull(activity.output, activity.diffStr,
                        activity.oldStr?.takeIf { activity.diffStr == null }?.let { "Before\n$it" },
                        activity.newStr?.takeIf { activity.diffStr == null }?.let { "After\n$it" },
                        activity.searchResults, fallback).distinct()
                    details.forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                    activity.failure?.takeIf { it.isNotBlank() && it !in details }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    activity.exitCode?.let { Text("Exit code $it", style = MaterialTheme.typography.labelSmall, color = if (it == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (activity.outputOmitted || activity.detailsOmitted) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (activity.detailsOmitted) "File details are not included in this snapshot." else "Output is not included in this snapshot.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onLoadActivity(activity.id) }, enabled = canLoadActivity) { Text(if (activity.detailsOmitted) "Load details" else "Load output") }
        }
    }
}

@Composable
private fun RequestCard(request: RuntimeRequest, enabled: Boolean, respond: (String, String, Map<String, List<String>>) -> Unit) {
    var answers by rememberSaveable(request.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val valid = request.questions.all { !it.required || answers[it.id]?.any(String::isNotBlank) == true }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NEEDS YOUR ATTENTION", style = MaterialTheme.typography.labelSmall)
            Text(request.title, style = MaterialTheme.typography.titleMedium)
            if (request.detail.isNotBlank()) SelectionContainer { Text(request.detail, style = MaterialTheme.typography.bodyMedium) }
            if (!request.canApprove) Text("This request is not supported here. Open another T3 client to respond.")
            else {
                request.questions.forEach { question ->
                    Text(question.title, fontWeight = FontWeight.Medium)
                    if (question.options.isEmpty()) OutlinedTextField(value = answers[question.id]?.firstOrNull().orEmpty(), onValueChange = { answers = answers + (question.id to listOf(it)) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text("Your answer") })
                    else question.options.forEach { option ->
                        val checked = option in answers[question.id].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = { selected -> answers = answers + (question.id to if (question.multiple) { if (selected) answers[question.id].orEmpty() + option else answers[question.id].orEmpty() - option } else if (selected) listOf(option) else emptyList()) }, enabled = enabled)
                            Text(option, Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.questions.isNotEmpty()) Button(onClick = { respond(request.id, "accept", answers) }, enabled = enabled && valid) { Text("Submit answers") }
                    else { Button(onClick = { respond(request.id, "accept", emptyMap()) }, enabled = enabled) { Text("Approve") }; OutlinedButton(onClick = { respond(request.id, "decline", emptyMap()) }, enabled = enabled) { Text("Deny") } }
                }
            }
        }
    }
}
