/* Conversation layout adapted from T3 Code's ThreadFeed (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.tajchert.trefoil.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/* ---------- Messages ---------- */

@Composable
internal fun MessageRow(message: ConversationMessage, stacked: Boolean, showMetadata: Boolean) {
    val c = Tf.colors
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    val layout = LocalConversationLayout.current
    // Wide column: the bubble shrink-wraps up to its cap at the column's end; files sit under it (user) or at prose width.
    val cardWidth = if (!layout.wide) Modifier.fillMaxWidth(if (user) .86f else 1f) else Modifier.widthIn(max = (if (user) layout.bubbleMax else layout.proseMax).dp).fillMaxWidth()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) {
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = c.s2, contentColor = c.text,
                modifier = if (layout.wide) Modifier.widthIn(max = layout.bubbleMax.dp) else Modifier.fillMaxWidth(.86f)) {
                // A message that quotes an assistant response shows the quote as a block, never the raw `t3-citation://` link.
                val citations = remember(message.text) { collectAssistantCitations(message.text) }
                SelectionContainer {
                    if (citations.isEmpty()) Text(rememberLinkified(message.text), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                    else CitedUserText(message.text, citations, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        } else {
            MarkdownContent(message.text, Modifier.fillMaxWidth())
        }
        MessageFileCards(message, cardWidth)
        if (message.streaming) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkingSpinner(size = 14.dp)
            Text("Writing…", style = TrefoilType.meta, color = c.blue)
        } else if (showMetadata) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val time = messageTime(message.updatedAt ?: message.createdAt)
                time?.let { Text((if (!user) "T3 · " else "") + it, Modifier.padding(top = 6.dp, end = if (user) 4.dp else 0.dp), style = TrefoilType.meta.copy(fontSize = 12.sp), color = c.muted) }
                // Wide column: with no timestamp in front, pull the 48dp button left so its glyph lines up with the prose edge (the hit area keeps its size).
                if (!user) Box(if (layout.wide && time == null) Modifier.offset(x = (-16).dp) else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TfIconButton(Icons.Default.ContentCopy, "Copy response", { clipboard.setText(AnnotatedString(message.text)) }, iconSize = 15.dp, tint = c.muted)
                        val onCite = LocalAssistantCiting.current.onCite
                        if (onCite != null && message.text.isNotBlank()) TfIconButton(TfIcons.Quote, stringResource(R.string.citation_cite_action), { onCite(message) }, iconSize = 15.dp, tint = c.muted)
                    }
                }
            }
        }
    }
}

private fun messageTime(value: String?): String? = timelineInstant(value)?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(it) }

/* ---------- Work log: flat, muted rows (after T3 Code's TimelineWorkGroupRows) ---------- */

internal val WorkRowText = TrefoilType.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal)

private val WorkIcon.vector: ImageVector get() = when (this) {
    WorkIcon.Read -> TfIcons.File
    WorkIcon.Edit -> TfIcons.Edit
    WorkIcon.Command -> TfIcons.Terminal
    WorkIcon.CodeSearch -> TfIcons.Search
    WorkIcon.WebSearch, WorkIcon.Browser -> TfIcons.Globe
    WorkIcon.Device -> TfIcons.Screens
    WorkIcon.Agent -> TfIcons.Agents
    WorkIcon.Html -> TfIcons.Scan
    WorkIcon.Other -> TfIcons.Wrench
    WorkIcon.Mixed -> TfIcons.Hammer
    WorkIcon.Reasoning -> TfIcons.Reasoning
    WorkIcon.Error -> TfIcons.Alert
}

/** One 32dp row: icon (or spinner) + single-line label + optional trailing content + small chevron when it toggles something. */
@Composable
private fun WorkRow(
    icon: ImageVector, iconTint: Color, label: String, labelColor: Color, expanded: Boolean?, onClick: (() -> Unit)?, onClickLabel: String,
    spinner: Boolean = false, description: String? = null, wrapLabel: Boolean = false, labelWeight: FontWeight? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
        .then(if (onClick != null) Modifier.focusRing().handPointer().clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
        .then(if (description != null || (expanded != null && onClick != null)) Modifier.semantics(mergeDescendants = true) {
            description?.let { contentDescription = it }
            if (expanded != null && onClick != null) stateDescription = if (expanded) "Expanded" else "Collapsed"
        } else Modifier)
        .padding(vertical = 4.dp), verticalAlignment = if (wrapLabel) Alignment.Top else Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // A wrapped label's icon centres on its first line (18sp line height) rather than the whole block.
        val iconModifier = if (wrapLabel) Modifier.padding(top = 1.dp).size(16.dp) else Modifier.size(16.dp)
        if (spinner) WorkingSpinner(size = 16.dp) else Icon(icon, null, iconModifier, tint = iconTint)
        val style = if (labelWeight != null) WorkRowText.copy(fontWeight = labelWeight) else WorkRowText
        // Wrapped labels are notices: the whole text is the content, so it stays selectable (e.g. a login command to copy).
        if (wrapLabel) SelectionContainer(Modifier.weight(1f, fill = false)) { Text(label, style = style, color = labelColor) }
        else Text(label, Modifier.weight(1f, fill = false), style = style, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
        if (expanded != null) Icon(if (expanded) TfIcons.ChevronDown else TfIcons.ChevronRight, null, Modifier.size(14.dp), tint = c.muted)
    }
}

/** "Worked for 42s": a quiet fold row with a divider beneath; expanded it reveals the run's messages and work groups inline. */
@Composable
internal fun CompletedWork(
    work: ConversationEntry.Work, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit, initiallyExpanded: Boolean = false,
    subagents: List<Subagent> = emptyList(), onOpenThread: ((String) -> Unit)? = null, onOpenAgents: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(work.key) { mutableStateOf(initiallyExpanded) }
    val c = Tf.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).focusRing().handPointer().clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse completed work" else "Expand completed work") { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(work.label, Modifier.weight(1f, fill = false), style = WorkRowText, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (expanded) TfIcons.ChevronDown else TfIcons.ChevronRight, if (expanded) "Collapse completed work" else "Expand completed work", Modifier.size(14.dp), tint = c.muted)
        }
        HorizontalDivider(color = c.line)
        if (expanded) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            work.entries.forEach { entry ->
                key(entry.key) {
                    when (entry) {
                        is ConversationEntry.Message -> MessageRow(entry.message, false, false)
                        is ConversationEntry.Activities -> if (entry.isSubagentGroup) SubagentActivityRows(entry.activities, subagents, onOpenThread, onOpenAgents)
                            else WorkGroup(entry.activities, live = false, canLoadActivity = canLoadActivity, onLoadActivity = onLoadActivity)
                        is ConversationEntry.HtmlRender, is ConversationEntry.Work -> Unit
                    }
                }
            }
        }
    }
}

/**
 * A run of consecutive activities. Zero visible entries render nothing, one renders as a single entry row, two or more get a
 * summary header that toggles the entry rows. [live] means the group belongs to the active run. Failures stay subtle: no
 * auto-expansion, and headers carry no failure visual (only the accessibility description).
 */
@Composable
internal fun WorkGroup(activities: List<ToolActivity>, live: Boolean = false, canLoadActivity: Boolean = false, onLoadActivity: (String) -> Unit = {}, initiallyExpanded: Boolean = false) {
    val visible = activities.filter { it.visibleInGroup() }
    if (visible.isEmpty()) return
    val c = Tf.colors
    val images = remember(activities) { viewedImages(activities) }
    Column(Modifier.fillMaxWidth()) {
        if (visible.size == 1) WorkEntry(visible.single(), single = true, live = live, canLoadActivity = canLoadActivity, onLoadActivity = onLoadActivity, initiallyExpanded = initiallyExpanded)
        else {
            val summary = remember(activities) { summarizeWorkGroup(visible) }
            var expanded by rememberSaveable(activities.first().id) { mutableStateOf(initiallyExpanded) }
            val running = if (live) visible.lastOrNull { it.isRunning } else null
            // A running entry outside the live run has an unknown outcome; the header must say so even while the group is collapsed.
            val stale = visible.any { !live && it.isRunning }
            val base = running?.let(::liveWorkLabel) ?: summary.label
            val label = if (stale) "$base · outcome unknown" else base
            val description = listOfNotNull(if (summary.hasFailure) "tool call failed" else null, if (stale) "outcome unknown" else null)
            WorkRow(summary.icon.vector, c.muted.copy(alpha = .7f), label, c.muted, expanded, { expanded = !expanded }, if (expanded) "Collapse work details" else "Expand work details",
                spinner = running != null, description = if (description.isEmpty()) null else (listOf(base) + description).joinToString(", "))
            if (expanded) Column(Modifier.padding(start = 16.dp)) {
                visible.forEach { activity -> key(activity.id) { WorkEntry(activity, single = false, live = live, canLoadActivity = canLoadActivity, onLoadActivity = onLoadActivity) } }
            }
        }
        if (images.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 24.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            images.forEach { reference -> key(reference.identity) { Box(Modifier.width(160.dp)) { InlineOrCard(reference) } } }
        }
    }
}

/** One entry row; expands to its flat detail when it has any. Failed entries tint their icon faintly red, `error` items are prominent. */
@Composable
private fun WorkEntry(activity: ToolActivity, single: Boolean, live: Boolean, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit, initiallyExpanded: Boolean = false) {
    val c = Tf.colors
    val severe = activity.severeFailure
    // T3 `system_notice`: the row is the whole message, wrapped, so it never expands to repeat itself.
    val notice = activity.kind == "system_notice"
    val warning = activity.isWarningNotice
    val running = live && activity.isRunning
    val stale = !live && activity.isRunning
    val hasDetail = !notice && activity.hasExpandableDetail()
    var expanded by rememberSaveable(activity.id) { mutableStateOf(initiallyExpanded) }
    val label = remember(activity, running, single) { if (running && single) liveWorkLabel(activity) else if (single) singleWorkLabel(activity) else workEntryLabel(activity) }
    val failed = remember(activity) { activity.displayFailure }
    val icon = remember(activity) { workEntryIcon(activity) }
    val stats = remember(activity.diffStr) { if (activity.kind == "file_change") diffStats(activity.diffStr) else null }
    Column(Modifier.fillMaxWidth()) {
        WorkRow(if (severe || warning) TfIcons.Alert else if (notice) TfIcons.Bolt else icon.vector,
            if (severe) c.red else if (warning) c.amber else if (failed) c.red.copy(alpha = .4f) else c.muted.copy(alpha = if (stale) .45f else .7f),
            label, if (severe) c.red else if (warning) c.amber else c.muted, if (hasDetail) expanded else null, if (hasDetail) ({ expanded = !expanded }) else null,
            if (expanded) "Collapse details" else "Expand details", spinner = running,
            description = if (warning) "Warning: $label" else entryDescription(label, failed, stale, stats),
            wrapLabel = notice, labelWeight = if (warning) FontWeight.Medium else null) {
            if (stale) Text("· outcome unknown", style = WorkRowText, color = c.muted.copy(alpha = .7f), maxLines = 1)
            stats?.let { (added, removed) ->
                Text("+$added", style = TrefoilType.mono, color = c.green)
                Text("−$removed", style = TrefoilType.mono, color = c.red)
            }
        }
        if (expanded && hasDetail) WorkEntryDetail(activity, canLoadActivity, onLoadActivity)
    }
}

/** Announced text for a row whose visual extras (failure tint, stale caption, diff counts) would otherwise be replaced by a bare label. */
internal fun entryDescription(label: String, failed: Boolean, stale: Boolean, stats: Pair<Int, Int>?): String? {
    val parts = listOfNotNull(if (failed) "tool call failed" else null, if (stale) "outcome unknown" else null, stats?.let { (added, removed) -> "+$added −$removed" })
    return if (parts.isEmpty()) null else (listOf(label) + parts).joinToString(", ")
}

/** Detail worth expanding an entry for. Images are not part of it: they render once under the group. */
internal fun ToolActivity.hasExpandableDetail(): Boolean =
    listOf(input, output, diffStr, oldStr, newStr, searchResults, failure).any { !it.isNullOrBlank() } ||
        (detail.isNotBlank() && !((kind == "checkpoint" || title.equals("checkpoint", true)) && detail.trim().equals("completed", true))) ||
        outputOmitted || detailsOmitted || (exitCode != null && exitCode != 0)

@Composable
private fun WorkEntryDetail(activity: ToolActivity, canLoadActivity: Boolean, onLoadActivity: (String) -> Unit) {
    val c = Tf.colors
    val clipboard = LocalClipboardManager.current
    val structured = listOfNotNull(activity.input, activity.output, activity.diffStr, activity.oldStr, activity.newStr, activity.searchResults, activity.failure).filter { it.isNotBlank() }
    val fallback = activity.detail.takeIf { it.isNotBlank() && structured.isEmpty() }
    val copyText = (structured + listOfNotNull(fallback)).distinct().joinToString("\n\n")
    val code = TrefoilType.code.copy(fontSize = 12.sp, lineHeight = 17.sp)
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                activity.fileName?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Text(it, style = TrefoilType.mono, color = c.muted) } }
                if (activity.kind in setOf("reasoning", "proposed_plan")) MarkdownContent(activity.reasoningText)
                else SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        activity.input?.takeIf { it.isNotBlank() }?.let { Text(if (activity.kind == "command_execution") "$ $it" else it, style = code) }
                        val details = listOfNotNull(activity.output, activity.diffStr,
                            activity.oldStr?.takeIf { activity.diffStr == null }?.let { "Before\n$it" },
                            activity.newStr?.takeIf { activity.diffStr == null }?.let { "After\n$it" },
                            activity.searchResults, fallback).distinct()
                        details.forEach { Text(it, style = code, color = c.muted) }
                        activity.failure?.takeIf { it.isNotBlank() && it !in details }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.red) }
                        activity.exitCode?.takeIf { it != 0 }?.let { Text("exit $it", style = code, color = c.red) }
                    }
                }
            }
            if (copyText.isNotBlank()) TfIconButton(Icons.Default.ContentCopy, "Copy activity", { clipboard.setText(AnnotatedString(copyText)) }, Modifier.size(32.dp), iconSize = 14.dp, tint = c.muted)
        }
        if (activity.outputOmitted || activity.detailsOmitted) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (activity.detailsOmitted) "File details are not included in this snapshot." else "Output is not included in this snapshot.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.muted)
            TextButton(onClick = { onLoadActivity(activity.id) }, enabled = canLoadActivity) { Text(if (activity.detailsOmitted) "Load details" else "Load output") }
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
    if (activity.kind == "dynamic_tool") when {
        activity.title.endsWith("html_render") -> return if (activity.isRunning) "Rendering HTML" else "Rendered HTML"
        activity.title.endsWith("html_preview") -> return if (activity.isRunning) "Previewing HTML" else "Previewed HTML"
    }
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
/* ---------- Pending requests ---------- */

/** A single-question, single-choice request maps onto quick-reply chips above the composer. */
internal fun RuntimeRequest.quickReplyQuestion(): Question? =
    questions.singleOrNull()?.takeIf { canApprove && it.options.isNotEmpty() && !it.multiple }

// Allow, Deny and Submit are a Material Surface with onClick: it already reaches a 48dp touch area around the 40dp pill (touchBoundsInRoot),
// and a disabled one does not respond, so a wide pane needs no wrapper here.
@Composable
private fun TfButton(label: String, onClick: () -> Unit, enabled: Boolean, primary: Boolean, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp), color = if (primary) c.accent else c.s2, contentColor = if (primary) c.onAccent else c.text,
        modifier = modifier.alpha(if (enabled) 1f else .4f).focusRing(RoundedCornerShape(20.dp)).handPointer(enabled).semantics { role = Role.Button }) {
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
    // Wide pane: a request waiting on the user's decision gets an amber border so it stands apart from the passive cards around it.
    val border = if (LocalConversationLayout.current.wide && request.canApprove) BorderStroke(1.5.dp, c.amber.copy(alpha = .45f)) else BorderStroke(1.dp, c.line)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.s1, contentColor = c.text, border = border) {
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
    // Wide: the dock lines the chips up with the reading column (its own side padding replaces the phone's 16dp).
    ReadingDock {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = if (LocalConversationLayout.current.wide) 0.dp else 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            question.options.forEach { option -> TfChip(option, onClick = { respond(request.id, "accept", mapOf(question.id to listOf(option))) }, enabled = enabled) }
        }
    }
}
