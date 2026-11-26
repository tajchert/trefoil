package com.tajchert.trefoil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.ASSISTANT_CITATION_MAX_COMMENT_LENGTH
import com.tajchert.trefoil.core.model.ASSISTANT_CITATION_MAX_TEXT_LENGTH
import com.tajchert.trefoil.core.model.AssistantCitation
import com.tajchert.trefoil.core.model.AssistantCitationMatch
import com.tajchert.trefoil.core.model.AssistantTextSelector
import com.tajchert.trefoil.core.model.CitationAddFailure
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.collapseWhitespace
import com.tajchert.trefoil.core.model.createAssistantTextSelector
import com.tajchert.trefoil.ui.adaptive.TfAdaptiveSheet
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.handPointer

/*
 * Assistant citations on the device: a quoted passage of an assistant message travels as a `t3-citation://` markdown link in the
 * message text. Sent messages show it as a quote block (never as a link, never opened); the composer shows its quotes as chips
 * and a "Cite" sheet adds one. Quote and comment are conversation content: never logged.
 */

/**
 * What the conversation offers the rows below it. One stable object per conversation (its lambdas read current state), so
 * rows do not recompose when the composer text or the list moves.
 */
@Stable
internal class AssistantCiting(
    /** Starts citing an assistant message; null when this conversation cannot take a quote (no thread, read only). */
    val onCite: ((ConversationMessage) -> Unit)? = null,
    /** Whether [goTo] can scroll to the cited message right now (it is a visible message of this thread). */
    val canGoTo: (AssistantCitation) -> Boolean = { false },
    val goTo: (AssistantCitation) -> Unit = {},
)

internal val LocalAssistantCiting = compositionLocalOf { AssistantCiting() }

/** A sent message's text in order: the user's own words, and the quotes. */
internal sealed interface CitationPart {
    data class Words(val text: String) : CitationPart
    data class Quote(val citation: AssistantCitation) : CitationPart
}

internal fun citationParts(text: String, matches: List<AssistantCitationMatch>): List<CitationPart> = buildList {
    var cursor = 0
    for (match in matches) {
        text.substring(cursor, match.start).trim().takeIf { it.isNotEmpty() }?.let { add(CitationPart.Words(it)) }
        add(CitationPart.Quote(match.citation))
        cursor = match.end
    }
    text.substring(cursor).trim().takeIf { it.isNotEmpty() }?.let { add(CitationPart.Words(it)) }
}

/** The text of a sent user message that holds [matches]: quote blocks where the links were, the user's words as before. */
@Composable
internal fun CitedUserText(text: String, matches: List<AssistantCitationMatch>, modifier: Modifier = Modifier) {
    val parts = remember(text, matches) { citationParts(text, matches) }
    val citing = LocalAssistantCiting.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is CitationPart.Words -> Text(rememberLinkified(part.text), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                is CitationPart.Quote -> CitationQuoteBlock(part.citation, if (citing.canGoTo(part.citation)) ({ citing.goTo(part.citation) }) else null)
            }
        }
    }
}

/** A quoted passage with the user's comment under it. Tapping it goes to the quoted message when [onClick] is given. */
@Composable
internal fun CitationQuoteBlock(citation: AssistantCitation, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val quote = remember(citation.text) { collapseWhitespace(citation.text).trim() }
    val description = stringResource(R.string.citation_quote_description, quote)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.s1).height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.handPointer().clickable(role = Role.Button, onClickLabel = stringResource(R.string.citation_go_to_message), onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(TfIcons.Quote, null, Modifier.size(12.dp), tint = c.muted)
                Text(stringResource(R.string.citation_label), style = TrefoilType.meta, color = c.muted)
            }
            Text(quote, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp, fontStyle = FontStyle.Italic), color = c.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
            citation.comment?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp), color = c.text, maxLines = 8, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The composer's quotes, one removable chip each; tapping a chip opens it to comment on, remove or go to. */
@Composable
internal fun CitationChips(citations: List<AssistantCitation>, onOpen: (AssistantCitation) -> Unit, onRemove: (AssistantCitation) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        citations.forEach { citation -> CitationChip(citation, { onOpen(citation) }, { onRemove(citation) }) }
    }
}

@Composable
private fun CitationChip(citation: AssistantCitation, onOpen: () -> Unit, onRemove: () -> Unit) {
    val c = Tf.colors
    val quote = remember(citation.text) { collapseWhitespace(citation.text).trim() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape).background(c.s2).border(BorderStroke(1.dp, c.line), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.widthIn(max = 220.dp).handPointer().clickable(role = Role.Button, onClickLabel = stringResource(R.string.citation_edit_title), onClick = onOpen)
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp).semantics(mergeDescendants = true) { contentDescription = quote },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(TfIcons.Quote, null, Modifier.size(14.dp), tint = c.muted)
            Column {
                Text(quote, style = TrefoilType.meta.copy(fontSize = 13.sp), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                citation.comment?.let { Text(it, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        TfIconButton(TfIcons.Close, stringResource(R.string.citation_remove), onRemove, Modifier.size(40.dp), iconSize = 14.dp, tint = c.muted)
    }
}

/** A composer quote opened: its text, an editable comment, and Remove / Go to message. */
@Composable
internal fun CitationEditDialog(citation: AssistantCitation, onDismiss: () -> Unit, onSave: (AssistantCitation) -> Unit, onRemove: () -> Unit) {
    val c = Tf.colors
    val citing = LocalAssistantCiting.current
    var comment by rememberSaveable(citation) { mutableStateOf(citation.comment.orEmpty()) }
    val quote = remember(citation.text) { collapseWhitespace(citation.text).trim() }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.sheet, shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.citation_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(quote, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = c.muted)
                OutlinedTextField(comment, { comment = it.take(ASSISTANT_CITATION_MAX_COMMENT_LENGTH) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.citation_comment_label)) }, minLines = 2, maxLines = 6)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onRemove) { Text(stringResource(R.string.citation_remove)) }
                    if (citing.canGoTo(citation)) TextButton({ onDismiss(); citing.goTo(citation) }) { Text(stringResource(R.string.citation_go_to_message)) }
                }
            }
        },
        confirmButton = { TextButton({ onSave(citation.withCommentOrNull(comment)) }) { Text(stringResource(R.string.citation_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.citation_cancel)) } },
    )
}

private fun AssistantCitation.withCommentOrNull(comment: String): AssistantCitation = copy(comment = comment.trim().takeIf { it.isNotEmpty() })

/** What [CiteSheet] reports back when it asks the conversation to add the quote: why it was refused, or null once added. */
internal typealias CiteResult = CitationAddFailure?

/**
 * Cite an assistant response: its rendered text in a read-only, selectable field (whose selection gives exact offsets), an optional
 * comment, Add. [onAdd] returns why the quote could not be added, or null when it was (the caller then closes the sheet).
 */
@Composable
internal fun CiteSheet(rendered: String, onDismiss: () -> Unit, onAdd: (AssistantTextSelector, String?) -> CiteResult) {
    val c = Tf.colors
    var selectionStart by rememberSaveable { mutableIntStateOf(0) }
    var selectionEnd by rememberSaveable { mutableIntStateOf(0) }
    var comment by rememberSaveable { mutableStateOf("") }
    var failure by rememberSaveable { mutableStateOf<CitationAddFailure?>(null) }
    val from = minOf(selectionStart, selectionEnd).coerceIn(0, rendered.length)
    val to = maxOf(selectionStart, selectionEnd).coerceIn(0, rendered.length)
    val selected = to - from
    val selector = remember(rendered, from, to) { createAssistantTextSelector(rendered, from, to) }
    val tooLong = selected > ASSISTANT_CITATION_MAX_TEXT_LENGTH
    TfAdaptiveSheet(onDismiss = onDismiss, dialogMaxWidth = 560.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.citation_sheet_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.citation_sheet_hint), style = TrefoilType.meta, color = c.muted)
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(shape).background(c.s1).border(BorderStroke(1.dp, c.line), shape).verticalScroll(rememberScrollState())) {
                BasicTextField(
                    value = TextFieldValue(rendered, TextRange(selectionStart.coerceIn(0, rendered.length), selectionEnd.coerceIn(0, rendered.length))),
                    onValueChange = { next -> if (next.text == rendered) { selectionStart = next.selection.start; selectionEnd = next.selection.end; failure = null } },
                    readOnly = true, textStyle = TrefoilType.message.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            val error = when {
                tooLong -> stringResource(R.string.citation_error_too_long, ASSISTANT_CITATION_MAX_TEXT_LENGTH)
                failure == CitationAddFailure.TooLong -> stringResource(R.string.citation_error_too_long, ASSISTANT_CITATION_MAX_TEXT_LENGTH)
                failure == CitationAddFailure.CommentTooLong -> stringResource(R.string.citation_error_comment_too_long, ASSISTANT_CITATION_MAX_COMMENT_LENGTH)
                failure == CitationAddFailure.NoRoom -> stringResource(R.string.citation_error_no_room)
                else -> null
            }
            Text(error ?: stringResource(R.string.citation_selected_count, selected, ASSISTANT_CITATION_MAX_TEXT_LENGTH), style = TrefoilType.meta, color = if (error != null) c.red else c.muted)
            OutlinedTextField(comment, { comment = it.take(ASSISTANT_CITATION_MAX_COMMENT_LENGTH); failure = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.citation_comment_label)) }, minLines = 2, maxLines = 5)
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDismiss) { Text(stringResource(R.string.citation_cancel)) }
                Button(enabled = selector != null && !tooLong, onClick = { selector?.let { failure = onAdd(it, comment.trim().takeIf { text -> text.isNotEmpty() }) } }) { Text(stringResource(R.string.citation_add)) }
            }
        }
    }
}
