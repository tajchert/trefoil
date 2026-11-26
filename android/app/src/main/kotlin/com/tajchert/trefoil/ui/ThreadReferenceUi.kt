package com.tajchert.trefoil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.AssistantCitation
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.SideThreadKind
import com.tajchert.trefoil.core.model.ThreadReference
import com.tajchert.trefoil.core.model.ThreadReferenceMatch
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.collectThreadReferenceMatches
import com.tajchert.trefoil.core.model.joinCitationDraft
import com.tajchert.trefoil.core.model.joinThreadReferenceDraft
import com.tajchert.trefoil.core.model.sanitizeContextLabel
import com.tajchert.trefoil.core.model.sideThreadKind
import com.tajchert.trefoil.core.model.splitCitationDraft
import com.tajchert.trefoil.core.model.splitThreadReferenceDraft
import com.tajchert.trefoil.core.model.toComposerContextId
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer

/*
 * Side chats and thread references in the conversation. A reference is a `[title](t3-context://v1/thread/<id>)` marker in the
 * composer text (like a citation): chips above the field, the marker sent as it is, a chip again in the sent bubble. The
 * `t3-context:` link itself is never opened by anything: it is parsed out of the text before any link handling sees it.
 */

/* ---------- Composer text: citations, thread references and the user's words ---------- */

/** A composer's text as the user's own [words], the [citations] quoted in it and the thread [references] named in it. */
internal data class ComposerParts(val words: String, val citations: List<AssistantCitation>, val references: List<ThreadReference>)

/**
 * The one composer layout: quote markers, a blank line, thread markers, a blank line, then the words. Each splitter lifts only its
 * own kind of marker, so the citations come off first and the references from what is left; [joinComposerText] is the inverse.
 */
internal fun splitComposerText(text: String): ComposerParts {
    val cited = splitCitationDraft(text)
    val referenced = splitThreadReferenceDraft(cited.body)
    return ComposerParts(referenced.body, cited.citations, referenced.references)
}

internal fun joinComposerText(words: String, citations: List<AssistantCitation>, references: List<ThreadReference>): String =
    joinCitationDraft(joinThreadReferenceDraft(words, references), citations)

/** The reference [threadId] gets in a composer: its sanitized [title] and its context id. */
internal fun threadReferenceFor(threadId: String, title: String): ThreadReference =
    ThreadReference(sanitizeContextLabel(title), toComposerContextId("thread", threadId))

/** [text] with [reference] added (a thread already referenced is kept once); null when the result would not fit [maxLength]. */
internal fun addThreadReference(text: String, reference: ThreadReference, maxLength: Int): String? {
    val parts = splitComposerText(text)
    if (parts.references.any { it.contextId == reference.contextId }) return text
    val next = joinComposerText(parts.words, parts.citations, parts.references + reference)
    return if (next.length > maxLength) null else next
}

/** [text] without any reference to the thread with [contextId]. */
internal fun removeThreadReference(text: String, contextId: String): String {
    val parts = splitComposerText(text)
    if (parts.references.none { it.contextId == contextId }) return text
    return joinComposerText(parts.words, parts.citations, parts.references.filter { it.contextId != contextId })
}

/* ---------- /side ---------- */

private val SideCommandPrefix = Regex("^/side\\s+")

/** Whether `/side` can start a side chat from the selected thread: the server supports it and this thread may have one. */
internal fun sideChatsAvailable(state: ClientState, isDraft: Boolean): Boolean {
    if (isDraft || !state.sideChatsSupported || !state.canWrite) return false
    val thread = state.threads.firstOrNull { it.id == state.selectedThreadId } ?: return false
    return thread.deletedAt == null && thread.sideThreadKind() == null &&
        (thread.latestUserMessageAt != null || state.messages.any { it.role == "user" })
}

/**
 * The question of a `/side <question>` composer, or null when the text is an ordinary message: no `/side ` prefix on the words,
 * or side chats are not available here. An empty question (the prefix alone) is recognised and returns "", so it is not sent as
 * a message. Quotes and thread references stay in the question.
 */
internal fun sideChatQuestion(fullText: String, state: ClientState, isDraft: Boolean): String? {
    if (!sideChatsAvailable(state, isDraft)) return null
    val parts = splitComposerText(fullText)
    val prefix = SideCommandPrefix.find(parts.words) ?: return null
    val question = joinComposerText(parts.words.substring(prefix.range.last + 1).trim(), parts.citations, parts.references)
    return if (question.isBlank()) "" else question
}

/** What a `/side` start came to; kept by the view model until the composer that asked consumes it. */
sealed interface SideChatResult {
    val environmentId: String
    val parentThreadId: String
    data class Started(override val environmentId: String, override val parentThreadId: String, val threadId: String) : SideChatResult
    data class Failed(override val environmentId: String, override val parentThreadId: String, val message: String) : SideChatResult
}

/** What the view model holds for `/side`: a start in flight (one at a time) and its result until the composer consumes it. */
internal data class SideChatState(val inFlight: Boolean = false, val result: SideChatResult? = null)

/** `/side` as the conversation sees it: whether a start is in flight (one at a time), its result, and the two calls. */
class SideChatUi(
    val inFlight: Boolean = false,
    val result: SideChatResult? = null,
    val start: (String) -> Unit = {},
    val consume: () -> Unit = {},
)

/** "Add to chat": [reference] goes into the composer of [parentThreadId] in [environmentId] once, then [Conversation] reports it done. */
internal class ThreadReferenceInsert(val environmentId: String, val parentThreadId: String, val reference: ThreadReference)

/**
 * "Add to chat" for the side chat [childId] of the selected thread: a reference for that thread's composer, or null when the chat is not one
 * of this thread's side chats, has been deleted, or has no user message yet (there is nothing to refer to).
 */
internal fun addToChatReference(state: ClientState, childId: String): ThreadReferenceInsert? {
    val environmentId = state.environment?.id ?: return null
    val parentId = state.selectedThreadId ?: return null
    val child = state.threads.firstOrNull { it.id == childId } ?: return null
    if (child.sideThreadKind() != SideThreadKind.SideChat || child.parentThreadId != parentId || child.deletedAt != null || child.latestUserMessageAt == null) return null
    return ThreadReferenceInsert(environmentId, parentId, threadReferenceFor(child.id, child.title))
}

/* ---------- Resolving a reference to a side chat of the open thread ---------- */

/** How references in this conversation open: [resolve] finds the side chat of this thread a reference names; [open] shows it (null: nothing opens). */
internal class ThreadReferenceLinks(
    val resolve: (ThreadReference) -> ThreadSummary? = { null },
    val open: ((String) -> Unit)? = null,
)

internal val LocalThreadReferenceLinks = compositionLocalOf { ThreadReferenceLinks() }

/** The click action for [reference]: opens its side chat when it names one of this thread, otherwise null. */
internal fun ThreadReferenceLinks.opener(reference: ThreadReference): (() -> Unit)? {
    val open = open ?: return null
    val thread = resolve(reference) ?: return null
    return { open(thread.id) }
}

/* ---------- Side chat chips above the composer ---------- */

/** A status mark for a side chat chip, from the same indicator the thread list uses. */
internal enum class SideChatMark { None, Working, Approval, Input, Error }

internal fun sideChatMark(thread: ThreadSummary): SideChatMark = when (threadIndicator(thread).kind) {
    "attention" -> SideChatMark.Approval
    "input" -> SideChatMark.Input
    "working" -> SideChatMark.Working
    "error" -> SideChatMark.Error
    else -> SideChatMark.None
}

/** The side chats of the open thread, newest first: one chip each, with an approval or input dot while it waits for the user. */
@Composable
internal fun SideChatsRow(chats: List<ThreadSummary>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.side_chats_row_description)
    Row(
        modifier.fillMaxWidth().testTag("side-chats-row").horizontalScroll(rememberScrollState()).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        chats.forEach { chat -> SideChatChip(chat) { onOpen(chat.id) } }
    }
}

@Composable
private fun SideChatChip(chat: ThreadSummary, onClick: () -> Unit) {
    val c = Tf.colors
    val mark = sideChatMark(chat)
    val title = chat.title.ifBlank { stringResource(R.string.side_chat_untitled) }
    val status = when (mark) {
        SideChatMark.Approval -> threadIndicator(chat).label
        SideChatMark.Input -> threadIndicator(chat).label
        SideChatMark.Working -> threadIndicator(chat).label
        SideChatMark.Error -> threadIndicator(chat).label
        SideChatMark.None -> null
    }
    val description = if (status == null) stringResource(R.string.side_chat_chip_description, title) else stringResource(R.string.side_chat_chip_description_status, title, status)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape).background(c.s2).border(BorderStroke(1.dp, c.line), shape)
            .focusRing(shape).handPointer().clickable(role = Role.Button, onClickLabel = stringResource(R.string.side_chat_open), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(TfIcons.Agents, null, Modifier.size(14.dp), tint = c.muted)
        Text(title, Modifier.widthIn(max = 180.dp), style = TrefoilType.meta.copy(fontSize = 13.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (mark) {
            SideChatMark.Approval -> StatusDot(c.amber)
            SideChatMark.Input -> StatusDot(c.blue)
            SideChatMark.Error -> StatusDot(c.red)
            SideChatMark.Working -> WorkingSpinner(size = 12.dp)
            SideChatMark.None -> {}
        }
    }
}

/* ---------- Reference chips in the composer ---------- */

/** The thread references of the composer draft, one removable chip each; a chip that names a side chat of this thread opens it when tapped. */
@Composable
internal fun ThreadReferenceChips(references: List<ThreadReference>, onRemove: (ThreadReference) -> Unit, modifier: Modifier = Modifier) {
    val links = LocalThreadReferenceLinks.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        references.distinctBy { it.contextId }.forEach { reference ->
            // The title the thread has now, when it is one of this thread's side chats; otherwise the label that was inserted.
            val label = links.resolve(reference)?.title?.let(::sanitizeContextLabel) ?: reference.label
            ThreadReferenceChip(label, links.opener(reference), { onRemove(reference) })
        }
    }
}

@Composable
private fun ThreadReferenceChip(label: String, onOpen: (() -> Unit)?, onRemove: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(14.dp)
    val description = stringResource(R.string.thread_reference_description, label)
    Row(Modifier.heightIn(min = 40.dp).clip(shape).background(c.s2).border(BorderStroke(1.dp, c.line), shape).testTag("thread-reference-chip"), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.widthIn(max = 220.dp)
                .then(if (onOpen != null) Modifier.handPointer().clickable(role = Role.Button, onClickLabel = stringResource(R.string.side_chat_open), onClick = onOpen) else Modifier)
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp).semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(TfIcons.Link, null, Modifier.size(14.dp), tint = c.muted)
            Text(label, style = TrefoilType.meta.copy(fontSize = 13.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TfIconButton(TfIcons.Close, stringResource(R.string.thread_reference_remove), onRemove, Modifier.size(40.dp), iconSize = 14.dp, tint = c.muted)
    }
}

/* ---------- References in a sent user message ---------- */

/** A sent message's text in order: the user's own words, and the thread references. */
internal sealed interface UserTextPart {
    data class Words(val text: String) : UserTextPart
    data class Reference(val reference: ThreadReference) : UserTextPart
}

internal fun userTextParts(text: String, matches: List<ThreadReferenceMatch>): List<UserTextPart> = buildList {
    var cursor = 0
    for (match in matches) {
        text.substring(cursor, match.start).trim().takeIf { it.isNotEmpty() }?.let { add(UserTextPart.Words(it)) }
        add(UserTextPart.Reference(match.reference))
        cursor = match.end
    }
    text.substring(cursor).trim().takeIf { it.isNotEmpty() }?.let { add(UserTextPart.Words(it)) }
}

/** The words of a sent user message: linkified text, with each thread reference as a chip showing its label instead of the `t3-context:` link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserWords(text: String, modifier: Modifier = Modifier) {
    val matches = remember(text) { collectThreadReferenceMatches(text) }
    if (matches.isEmpty()) {
        Text(rememberLinkified(text), modifier, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
        return
    }
    val parts = remember(text, matches) { userTextParts(text, matches) }
    val links = LocalThreadReferenceLinks.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var index = 0
        while (index < parts.size) {
            val part = parts[index]
            if (part is UserTextPart.Words) {
                Text(rememberLinkified(part.text), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                index++
            } else {
                val run = parts.drop(index).takeWhile { it is UserTextPart.Reference }.filterIsInstance<UserTextPart.Reference>()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    run.forEach { SentReferenceChip(it.reference.label, links.opener(it.reference)) }
                }
                index += run.size
            }
        }
    }
}

@Composable
private fun SentReferenceChip(label: String, onOpen: (() -> Unit)?) {
    val c = Tf.colors
    val shape = RoundedCornerShape(12.dp)
    val description = stringResource(R.string.thread_reference_description, label)
    Row(
        Modifier.heightIn(min = 32.dp).clip(shape).background(c.s1).border(BorderStroke(1.dp, c.line), shape).testTag("thread-reference-bubble-chip")
            .then(if (onOpen != null) Modifier.handPointer().clickable(role = Role.Button, onClickLabel = stringResource(R.string.side_chat_open), onClick = onOpen) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(TfIcons.Link, null, Modifier.size(13.dp), tint = c.muted)
        Text(label, Modifier.widthIn(max = 240.dp), style = TrefoilType.meta.copy(fontSize = 13.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
