/* Conversation layout adapted from T3 Code's ThreadFeed (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* ---------- Messages ---------- */

@Composable
internal fun MessageRow(message: ConversationMessage, stacked: Boolean, showMetadata: Boolean) {
    val c = Tf.colors
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) {
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = c.s2, contentColor = c.text, modifier = Modifier.fillMaxWidth(.86f)) {
                SelectionContainer { Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)) }
            }
        } else {
            MarkdownContent(message.text, Modifier.fillMaxWidth())
        }
        MessageFileCards(message, Modifier.fillMaxWidth(if (user) .86f else 1f))
        if (message.streaming) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkingSpinner(size = 14.dp)
            Text("Writing…", style = TrefoilType.meta, color = c.blue)
        } else if (showMetadata) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                messageTime(message.updatedAt ?: message.createdAt)?.let { time -> Text((if (!user) "T3 · " else "") + time, Modifier.padding(top = 6.dp, end = if (user) 4.dp else 0.dp), style = TrefoilType.meta.copy(fontSize = 12.sp), color = c.muted) }
                if (!user) IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Icon(Icons.Default.ContentCopy, "Copy response", Modifier.size(15.dp), tint = c.muted) }
            }
        }
    }
}

private fun messageTime(value: String?): String? = timelineInstant(value)?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(it) }

/* ---------- Activity rows (the design's ".act") ---------- */

internal data class ActivityCounts(val commands: Int, val edits: Int, val searches: Int) {
    val total get() = commands + edits + searches
}

internal fun activityCounts(activities: List<ToolActivity>) = ActivityCounts(
    commands = activities.count { it.kind == "command_execution" },
    edits = activities.count { it.kind == "file_change" },
    searches = activities.count { it.kind in setOf("file_search", "web_search", "search") },
)

@Composable
private fun CountChip(icon: ImageVector, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = Tf.colors.muted)
        Text(count.toString(), style = TrefoilType.meta, color = Tf.colors.muted)
    }
}

@Composable
private fun ActivityCountsRow(counts: ActivityCounts) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (counts.commands > 0) CountChip(TfIcons.Terminal, counts.commands)
        if (counts.edits > 0) CountChip(TfIcons.Edit, counts.edits)
        if (counts.searches > 0) CountChip(TfIcons.Search, counts.searches)
    }
}

/** Outlined container shared by activity rows and their expanded details. */
@Composable
private fun OutlinedPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(14.dp), color = Color.Transparent, contentColor = Tf.colors.text, border = BorderStroke(1.dp, Tf.colors.line)) {
        Column(content = content)
    }
}

@Composable
internal fun CompletedWork(work: ConversationEntry.Work, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit) {
    var expanded by rememberSaveable(work.key) { mutableStateOf(false) }
    val c = Tf.colors
    val counts = remember(work) { activityCounts(work.entries.filterIsInstance<ConversationEntry.Activities>().flatMap { it.activities }) }
    val duration = work.label.removePrefix("Worked for ").takeIf { it != work.label } ?: "Done"
    Column(Modifier.fillMaxWidth()) {
        OutlinedPanel {
            Row(Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse completed work" else "Expand completed work") { expanded = !expanded }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.green)
                Text(duration, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                ActivityCountsRow(counts)
                Icon(TfIcons.ChevronDown, if (expanded) "Collapse completed work" else "Expand completed work", Modifier.size(18.dp), tint = c.muted)
            }
        }
        if (expanded) Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
internal fun ActivityStack(activities: List<ToolActivity>, initiallyExpanded: Boolean = false, canLoadActivity: Boolean = false, onLoadActivity: (String) -> Unit = {}) {
    if (activities.all { it.kind == "command_execution" }) {
        CommandStack(activities, canLoadActivity, onLoadActivity)
        return
    }
    val c = Tf.colors
    val running = activities.any { it.isRunning }
    val attention = activities.any { it.needsAttention }
    val expandable = activities.size > 1 || activities.any { it.hasExpandableDetail() }
    var expanded by rememberSaveable(activities.first().id) { mutableStateOf(initiallyExpanded || running || attention) }
    val visible = expanded && expandable
    val label = when {
        attention -> "Activity needs attention"
        running -> "Working"
        activities.all { it.kind == "reasoning" } -> "Thought"
        activities.all { it.status in setOf("completed", "succeeded", "success", "done") } -> "Work completed"
        else -> "Activity"
    }
    val single = activities.singleOrNull()
    val fileChange = single?.takeIf { it.kind == "file_change" && !it.fileName.isNullOrBlank() }
    val counts = remember(activities) { activityCounts(activities) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(if (expandable) Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse activity" else "Expand activity") { expanded = !expanded } else Modifier).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    running -> WorkingSpinner(size = 18.dp)
                    attention -> Icon(TfIcons.Close, null, Modifier.size(18.dp), tint = c.red)
                    fileChange != null -> Icon(TfIcons.File, null, Modifier.size(18.dp), tint = c.muted)
                    else -> Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.green)
                }
                Column(Modifier.weight(1f)) {
                    if (fileChange != null) Text(fileChange.fileName.orEmpty(), style = TrefoilType.mono.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text(if (single != null) activityHeadline(single) else "$label · ${activities.size} activities", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!visible) {
                        val command = activities.last().takeIf { it.kind == "command_execution" }?.input?.lineSequence()?.firstOrNull()?.trim()
                        val subtitle = command?.takeIf { it.isNotBlank() } ?: activities.last().title.takeIf { activities.size > 1 }
                        if (subtitle != null) Text(subtitle, style = if (command != null) TrefoilType.mono else TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (fileChange != null) diffStats(fileChange.diffStr)?.let { (added, removed) ->
                    Text("+$added", style = TrefoilType.mono, color = c.green)
                    Text("−$removed", style = TrefoilType.mono, color = c.red)
                } else if (activities.size > 1) ActivityCountsRow(counts)
                if (expandable) Icon(TfIcons.ChevronDown, if (expanded) "Collapse activity" else "Expand activity", Modifier.size(18.dp), tint = c.muted)
            }
            if (visible) activities.forEach { activity ->
                key(activity.id) {
                    HorizontalDivider(color = c.line)
                    ActivityDetail(activity, canLoadActivity, onLoadActivity)
                }
            }
        }
    }
}

internal fun ToolActivity.hasExpandableDetail(): Boolean =
    listOf(input, output, diffStr, oldStr, newStr, searchResults, failure).any { !it.isNullOrBlank() } ||
        (detail.isNotBlank() && !((kind == "checkpoint" || title.equals("checkpoint", true)) && detail.trim().equals("completed", true))) ||
        outputOmitted || detailsOmitted || exitCode != null

private fun ToolActivity.hasCommandOutput(): Boolean =
    listOf(output, diffStr, oldStr, newStr, searchResults, failure).any { !it.isNullOrBlank() } ||
        detail.isNotBlank() || outputOmitted || detailsOmitted || exitCode != null

@Composable
private fun CommandStack(activities: List<ToolActivity>, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit) {
    val c = Tf.colors
    val running = activities.any { it.isRunning }
    val expandable = activities.any { !it.input.isNullOrBlank() || it.hasCommandOutput() || it.isRunning }
    var expanded by rememberSaveable(activities.first().id) { mutableStateOf(running) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().then(if (expandable) Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse commands" else "Expand commands") { expanded = !expanded } else Modifier).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) WorkingSpinner(size = 16.dp) else Icon(TfIcons.Terminal, null, Modifier.size(16.dp), tint = c.muted)
            Text(if (running) "Running command…" else "Ran ${activities.size} command${if (activities.size == 1) "" else "s"}", Modifier.weight(1f), style = TrefoilType.meta, color = c.muted, maxLines = 1)
            if (expandable) Icon(TfIcons.ChevronDown, if (expanded) "Collapse commands" else "Expand commands", Modifier.size(18.dp), tint = c.muted)
        }
        if (expanded && expandable) activities.forEach { activity ->
            key(activity.id) {
                var detailExpanded by rememberSaveable(activity.id) { mutableStateOf(false) }
                val hasOutput = activity.hasCommandOutput()
                Row(Modifier.fillMaxWidth().then(if (hasOutput) Modifier.clickable(role = Role.Button) { detailExpanded = !detailExpanded } else Modifier).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activity.isRunning) WorkingSpinner(size = 14.dp) else Icon(if (activity.needsAttention) TfIcons.Close else TfIcons.Check, null, Modifier.size(14.dp), tint = if (activity.needsAttention) c.red else c.green)
                    Text(activity.input?.lineSequence()?.firstOrNull().orEmpty().ifBlank { activityHeadline(activity) }, style = TrefoilType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (detailExpanded && hasOutput) ActivityDetail(activity, canLoadActivity, onLoadActivity)
            }
        }
    }
}

/** Added/removed line counts from a unified diff, or null when there is no diff to count. */
internal fun diffStats(diff: String?): Pair<Int, Int>? {
    if (diff.isNullOrBlank()) return null
    val lines = diff.lineSequence()
    val added = lines.count { it.startsWith("+") && !it.startsWith("+++") }
    val removed = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
    return if (added + removed == 0) null else added to removed
}

internal fun activityHeadline(activity: ToolActivity): String {
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

private fun activityIcon(activity: ToolActivity): ImageVector = when (activity.kind) {
    "command_execution" -> TfIcons.Terminal
    "file_change" -> TfIcons.Edit
    "file_search", "web_search", "search" -> TfIcons.Search
    "file_read", "file" -> TfIcons.File
    else -> TfIcons.Settled
}

@Composable
private fun ActivityDetail(activity: ToolActivity, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit) {
    val c = Tf.colors
    val clipboard = LocalClipboardManager.current
    val structured = listOfNotNull(activity.input, activity.output, activity.diffStr, activity.oldStr, activity.newStr, activity.searchResults, activity.failure).filter { it.isNotBlank() }
    val fallback = activity.detail.takeIf { it.isNotBlank() && structured.isEmpty() }
    val copyText = (structured + listOfNotNull(fallback)).distinct().joinToString("\n\n")
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activityIcon(activity), null, Modifier.size(14.dp), tint = c.muted)
            Text(activityHeadline(activity), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (activity.status.isNotBlank()) Text(activity.status.replace('_', ' '), style = TrefoilType.meta.copy(fontSize = 12.sp), color = if (activity.needsAttention) c.red else c.muted)
            if (copyText.isNotBlank()) IconButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Copy activity", Modifier.size(14.dp), tint = c.muted) }
        }
        activity.fileName?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Text(it, style = TrefoilType.mono, color = c.muted) } }
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (activity.kind in setOf("reasoning", "proposed_plan")) MarkdownContent(activity.output?.takeIf { it.isNotBlank() } ?: activity.detail)
            else SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activity.input?.takeIf { it.isNotBlank() }?.let {
                        Text(if (activity.kind == "command_execution") "$ $it" else it, style = TrefoilType.code.copy(fontSize = 12.sp, lineHeight = 17.sp))
                    }
                    val details = listOfNotNull(activity.output, activity.diffStr,
                        activity.oldStr?.takeIf { activity.diffStr == null }?.let { "Before\n$it" },
                        activity.newStr?.takeIf { activity.diffStr == null }?.let { "After\n$it" },
                        activity.searchResults, fallback).distinct()
                    details.forEach { Text(it, style = TrefoilType.code.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.text.copy(alpha = .86f)) }
                    activity.failure?.takeIf { it.isNotBlank() && it !in details }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.red) }
                    activity.exitCode?.let { Text("Exit code $it", style = TrefoilType.meta.copy(fontSize = 12.sp), color = if (it == 0) c.muted else c.red) }
                }
            }
        }
        if (activity.outputOmitted || activity.detailsOmitted) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (activity.detailsOmitted) "File details are not included in this snapshot." else "Output is not included in this snapshot.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.muted)
            TextButton(onClick = { onLoadActivity(activity.id) }, enabled = canLoadActivity) { Text(if (activity.detailsOmitted) "Load details" else "Load output") }
        }
    }
}

/* ---------- Pending requests ---------- */

/** A single-question, single-choice request maps onto quick-reply chips above the composer. */
internal fun RuntimeRequest.quickReplyQuestion(): Question? =
    questions.singleOrNull()?.takeIf { canApprove && it.options.isNotEmpty() && !it.multiple }

@Composable
private fun TfButton(label: String, onClick: () -> Unit, enabled: Boolean, primary: Boolean, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp), color = if (primary) c.accent else c.s2, contentColor = if (primary) c.onAccent else c.text,
        modifier = modifier.alpha(if (enabled) 1f else .4f).semantics { role = Role.Button }) {
        Box(Modifier.height(40.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
internal fun RequestCard(request: RuntimeRequest, enabled: Boolean, respond: (String, String, Map<String, List<String>>) -> Unit) {
    val c = Tf.colors
    var answers by rememberSaveable(request.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val valid = request.questions.all { !it.required || answers[it.id]?.any(String::isNotBlank) == true }
    val quickReply = request.quickReplyQuestion()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Needs you", dot = c.amber)
            if (request.questions.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(TfIcons.Terminal, null, Modifier.size(18.dp), tint = c.amber)
                    Text(request.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                if (request.detail.isNotBlank()) SelectionContainer {
                    Text(request.detail, Modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp), style = TrefoilType.mono, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(request.title, style = MaterialTheme.typography.titleSmall)
                if (request.detail.isNotBlank()) SelectionContainer { Text(request.detail, style = MaterialTheme.typography.bodyMedium, color = c.muted) }
            }
            if (!request.canApprove) Text("This request is not supported here. Open another T3 client to respond.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            else {
                request.questions.forEach { question ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        StatusDot(c.amber, Modifier.padding(top = 8.dp))
                        Text(question.title, style = TrefoilType.message, modifier = Modifier.weight(1f))
                    }
                    if (question.id == quickReply?.id) Unit // options are offered as quick replies above the composer
                    else if (question.options.isEmpty()) OutlinedTextField(value = answers[question.id]?.firstOrNull().orEmpty(), onValueChange = { answers = answers + (question.id to listOf(it)) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text("Your answer") })
                    else FlowRowChips(question.options) { option ->
                        val checked = option in answers[question.id].orEmpty()
                        TfChip(option, onClick = {
                            val current = answers[question.id].orEmpty()
                            answers = answers + (question.id to if (question.multiple) { if (checked) current - option else current + option } else if (checked) emptyList() else listOf(option))
                        }, selected = checked, enabled = enabled)
                    }
                }
                if (quickReply == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.questions.isNotEmpty()) TfButton("Submit answers", { respond(request.id, "accept", answers) }, enabled && valid, primary = true)
                    else {
                        TfButton("Allow", { respond(request.id, "accept", emptyMap()) }, enabled, primary = true)
                        TfButton("Deny", { respond(request.id, "decline", emptyMap()) }, enabled, primary = false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(options: List<String>, chip: @Composable (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { chip(it) } }
}

/** Quick replies for the first pending single-choice question, shown above the composer. */
@Composable
internal fun QuickReplies(requests: List<RuntimeRequest>, enabled: Boolean, respond: (String, String, Map<String, List<String>>) -> Unit) {
    val request = requests.firstOrNull { it.quickReplyQuestion() != null } ?: return
    val question = request.quickReplyQuestion() ?: return
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        question.options.forEach { option -> TfChip(option, onClick = { respond(request.id, "accept", mapOf(question.id to listOf(option))) }, enabled = enabled) }
    }
}
