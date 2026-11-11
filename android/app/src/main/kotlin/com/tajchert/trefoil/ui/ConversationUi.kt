/* Conversation layout adapted from T3 Code's ThreadFeed (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
internal fun Conversation(
    rawState: ClientState, send: (String) -> Unit, interrupt: () -> Unit, respond: (String, String, Map<String, List<String>>) -> Unit,
    onLoadActivity: (String) -> Unit = {}, fileDownloadUi: FileDownloadUi = FileDownloadUi(),
    draft: ThreadDraft? = null, onSetRuntimeMode: (RuntimeMode) -> Unit = {}, onSetModel: (ModelOption) -> Unit = {},
    sendFailed: Flow<Unit> = emptyFlow(),
    /** Back affordance for the conversation header; null on wide layouts where the list stays visible. */
    onBack: (() -> Unit)? = null,
    /** Trailing header actions supplied by the shell (thread actions menu, etc.). */
    actions: @Composable RowScope.() -> Unit = {},
    /** First prompt from the new-thread sheet: put in the composer and submit once, then call [onSeedConsumed]. */
    seedPrompt: String? = null,
    onSeedConsumed: () -> Unit = {},
) {
    val c = Tf.colors
    // A draft hides whatever thread was previously selected; its first message creates the real thread.
    val base = if (draft == null) rawState else rawState.copy(messages = emptyList(), activities = emptyList(), requests = emptyList(), activeRunId = null, runs = emptyList(), attempts = emptyList())
    // A draft launches a new thread, which needs only the shell; an open thread rides out its refresh gaps.
    val steadyWrite = rememberSteadyCanWrite(base)
    val writable = if (draft != null) base.canLaunchThreads else steadyWrite
    val state = if (writable == base.canWrite) base else base.copy(canWrite = writable)
    if (state.selectedThreadId == null && draft == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TrefoilMark(Modifier.size(48.dp))
                Text("Pick a thread", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
                Text("Choose a thread from the list, or start a new one.", style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return
    }
    val composerKey = if (draft != null) "draft" else state.selectedThreadId
    var text by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf("") }
    var submitted by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf<String?>(null) }
    var messageIdsAtSend by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf(arrayListOf<String>()) }
    val canSend = state.canWrite && !state.busy
    /** The one send path: the Send button and the seeded first prompt both go through here. */
    fun submit(value: String): Boolean {
        if (value.isBlank() || submitted != null || !canSend) return false
        messageIdsAtSend = ArrayList(state.messages.map { it.id })
        submitted = value
        send(value)
        return true
    }
    // Survives recomposition and configuration change so the seed is never submitted twice.
    var seedHandled by rememberSaveable(seedPrompt) { mutableStateOf(false) }
    LaunchedEffect(seedPrompt, draft != null, seedHandled) {
        if (seedPrompt != null && draft != null && !seedHandled) {
            seedHandled = true
            text = seedPrompt
            submit(seedPrompt) // if this cannot send (read-only), the text stays in the composer
            onSeedConsumed()
        }
    }
    LaunchedEffect(state.messages) {
        val pending = submitted
        if (pending != null && state.messages.any { it.role == "user" && it.text == pending && it.id !in messageIdsAtSend }) {
            if (text == pending) text = ""
            submitted = null
        }
    }
    LaunchedEffect(state.error) { if (state.error != null) submitted = null }
    // Identical consecutive errors do not retrigger the effect above, so every failed send also emits here.
    LaunchedEffect(sendFailed) { sendFailed.collect { submitted = null } }
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
    // The keyboard opening (or the composer growing) shrinks the viewport, and LazyColumn keeps its top
    // anchored, which would push the latest messages under the keyboard. Stay pinned to the bottom only if
    // the list was at its end before the resize; a position the reader scrolled to is left untouched.
    LaunchedEffect(list) {
        var lastHeight = -1
        var wasAtEnd = true
        snapshotFlow { list.layoutInfo.viewportSize.height to !list.canScrollForward }.collect { (height, atEnd) ->
            if (height != lastHeight && lastHeight > 0 && wasAtEnd) {
                val count = list.layoutInfo.totalItemsCount
                if (!atEnd && count > 0) list.scrollToItem(count - 1)
            } else wasAtEnd = atEnd
            lastHeight = height
        }
    }
    val thread = if (draft != null) null else state.threads.find { it.id == state.selectedThreadId }
    val project = state.projects.find { it.id == (draft?.projectId ?: thread?.projectId) }
    val canLoadActivity = state.connection == ConnectionStatus.Connected && !state.busy
    val workStartedAt = thread?.let(::threadWorkStartedAt)?.let(::validThreadInstant)
    val clock = rememberThreadWorkClock(state.activeRunId != null && workStartedAt != null)
    val workLabel = "Working" + if (workStartedAt != null) " ${formatWorkDuration(clock - workStartedAt)}" else ""
    CompositionLocalProvider(LocalFileDownloadUi provides fileDownloadUi, LocalContentColor provides c.text) {
        Column(Modifier.fillMaxSize()) {
            ConversationHeader(
                title = if (draft != null) "New thread" else thread?.title?.ifBlank { "Untitled conversation" } ?: "Conversation",
                projectTitle = project?.title, branch = thread?.branch?.takeIf { it.isNotBlank() },
                workLabel = workLabel.takeIf { state.activeRunId != null }, onBack = onBack, actions = actions)
            HorizontalDivider(color = c.line)
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.busy && entries.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.s2) }
                    if (!state.busy && entries.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            TrefoilMark(Modifier.size(40.dp))
                            Text(if (draft != null) "New thread in ${project?.title ?: "this project"}" else "Nothing here yet", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium)
                            Text(if (draft != null) "Your first message starts the thread." else "Send a message to get started.", Modifier.padding(top = 6.dp), style = TrefoilType.preview, color = c.muted)
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WorkingSpinner(size = 16.dp)
                            Text("Working…", style = TrefoilType.meta, color = c.blue)
                        }
                    }
                    items(state.requests, key = { "request:${it.id}" }) { RequestCard(it, canSend, respond) }
                    item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
                }
                if (!nearBottom) Surface(onClick = { following = true; scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text, border = BorderStroke(1.dp, c.line), shadowElevation = 2.dp) {
                    Row(Modifier.height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(TfIcons.ChevronDown, null, Modifier.size(16.dp)); Text("Latest", style = TrefoilType.meta.copy(color = c.text))
                    }
                }
            }
            // The composer owns its bottom insets: it rides above the keyboard, or above the navigation bar when closed.
            Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                QuickReplies(state.requests, canSend, respond)
                ConversationComposer(state, draft, text, { text = it }, submitted != null, onSetRuntimeMode, onSetModel, interrupt) { submit(text) }
            }
        }
    }
}

@Composable
private fun ConversationHeader(title: String, projectTitle: String?, branch: String?, workLabel: String?, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) { Icon(TfIcons.Back, "Back to threads", Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(start = if (onBack != null) 2.dp else 0.dp)) {
            Text(title, style = TrefoilType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (projectTitle != null || workLabel != null) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (projectTitle != null) {
                    ProjectTile(projectTitle, size = 20.dp)
                    Text(projectTitle, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (branch != null) BranchLabel(branch, Modifier.weight(1f, fill = false))
                }
                if (workLabel != null) {
                    WorkingSpinner(size = 14.dp)
                    Text(workLabel, style = TrefoilType.meta, color = c.blue, maxLines = 1)
                }
            }
        }
        actions()
    }
}

@Composable
private fun ConversationComposer(
    state: ClientState, draft: ThreadDraft?, text: String, onText: (String) -> Unit, waiting: Boolean,
    onSetRuntimeMode: (RuntimeMode) -> Unit, onSetModel: (ModelOption) -> Unit, interrupt: () -> Unit, onSend: () -> Unit,
) {
    val c = Tf.colors
    val running = state.activeRunId != null
    val canSend = state.canWrite && !state.busy
    val noticeStyle = TrefoilType.meta.copy(fontSize = 12.sp, color = c.muted)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp, top = 4.dp)) {
        if (!state.canWrite) Text("Read only · reconnect to continue", Modifier.padding(start = 6.dp, bottom = 6.dp), style = noticeStyle)
        if (waiting) Text("Waiting for server confirmation…", Modifier.padding(start = 6.dp, bottom = 6.dp), style = noticeStyle)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
            Column {
                BasicTextField(
                    value = text, onValueChange = onText, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text(if (draft != null) "What should the agent do?" else if (running) "Queue a message…" else "Reply…", style = MaterialTheme.typography.bodyLarge, color = c.muted)
                            inner()
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ComposerPickers(state, draft, onSetRuntimeMode, onSetModel, Modifier.weight(1f))
                    if (running && text.isBlank()) CircleButton("Stop", TfIcons.Stop, c.text, c.bg, canSend, interrupt)
                    else CircleButton(if (running) "Queue message" else "Send message", TfIcons.Send, c.accent, c.onAccent, canSend && text.isNotBlank() && !waiting, onSend)
                }
            }
        }
    }
}

@Composable
private fun CircleButton(description: String, icon: ImageVector, container: Color, content: Color, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = container, contentColor = content,
        modifier = Modifier.size(44.dp).alpha(if (enabled) 1f else .4f).semantics { contentDescription = description; role = Role.Button }) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

@Composable
private fun ComposerPickers(state: ClientState, draft: ThreadDraft?, onSetRuntimeMode: (RuntimeMode) -> Unit, onSetModel: (ModelOption) -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val mode = if (draft != null) draft.runtimeMode else state.selectedRuntimeMode
    val model = if (draft != null) draft.model else state.selectedModel
    val enabled = state.canWrite && !state.busy
    var modeOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f, fill = false)) {
            TfPill(model?.label ?: "Model", { modelOpen = true }, icon = TfIcons.Sparkle, enabled = enabled && state.models.isNotEmpty(), contentDescription = "Model: ${model?.label ?: "not set"}")
            DropdownMenu(modelOpen, { modelOpen = false }) {
                val choices = if (state.selectedProviderLocked && draft == null && model != null) state.models.filter { it.instanceId == model.instanceId } else state.models
                choices.forEach { option ->
                    DropdownMenuItem(text = { Column { Text(option.label); Text(option.instanceId, style = MaterialTheme.typography.labelSmall, color = c.muted) } },
                        onClick = { modelOpen = false; onSetModel(option) },
                        trailingIcon = { if (option == model) Icon(TfIcons.Check, "Selected", Modifier.size(18.dp)) })
                }
            }
        }
        Box(Modifier.weight(1f, fill = false)) {
            TfPill(mode?.label ?: "Mode", { modeOpen = true }, icon = TfIcons.Shield, enabled = enabled, contentDescription = "Runtime mode: ${mode?.label ?: "not set"}")
            DropdownMenu(modeOpen, { modeOpen = false }) {
                allowedRuntimeModes(model).forEach { option ->
                    DropdownMenuItem(text = { Column { Text(option.label); Text(option.description, style = MaterialTheme.typography.labelSmall, color = c.muted) } },
                        onClick = { modeOpen = false; onSetRuntimeMode(option) },
                        trailingIcon = { if (option == mode) Icon(TfIcons.Check, "Selected", Modifier.size(18.dp)) })
                }
            }
        }
    }
}

/**
 * While an agent works, every server event briefly marks the thread stale until its refetch lands, so the raw
 * `canWrite` blinks. Keep thread controls enabled through gaps shorter than [STALE_GRACE_MS] while the
 * connection itself is healthy; the client waits for freshness if one is tapped mid-gap. Any real outage
 * (disconnected, shell not ready) disables them immediately.
 */
@Composable
internal fun rememberSteadyCanWrite(state: ClientState): Boolean {
    val live = state.canWrite
    val healthy = state.canLaunchThreads
    var held by remember(state.environment?.id, state.selectedThreadId) { mutableStateOf(live) }
    LaunchedEffect(live, healthy) {
        if (live) held = true
        else if (!healthy) held = false
        else { delay(STALE_GRACE_MS); held = false }
    }
    return live || (held && healthy)
}

private const val STALE_GRACE_MS = 1_500L

/** Launching a thread needs the shell (projects, models), not a fresh projection of whichever thread is open. `canWrite` implies it. */
internal val ClientState.canLaunchThreads: Boolean get() = canWrite || (canWriteThreads && connection == ConnectionStatus.Connected)
