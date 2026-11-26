package com.tajchert.trefoil.ui.pr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.PendingPrAction
import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrActionKind
import com.tajchert.trefoil.core.model.PrActionOutcome
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PullRequestKey
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer

/*
 * Actions section of the pull request panel. A button is enabled only by [PrActionsModel], which forwards the session's own rule
 * (`canRun` / `canComment`). Every tap is sent once: nothing here retries or resends, even after an unknown outcome.
 */

/** What the Actions section asks of its host. [onRun] and [onComment] each send once. */
internal class PrActionHandlers(
    val onRun: (PrAction, PrMergeMethod?) -> Unit,
    val onComment: (String) -> Unit,
)

/**
 * Actions section content. [busy] is true while a send for this pull request is in flight: every button and the merge confirmation are off.
 * The merge dialog and the comment draft are per [prKey]: they never carry over to another pull request or thread.
 */
@Composable
internal fun PullRequestActionsSection(
    model: PrActionsModel, pending: PendingPrAction?, localError: String?, busy: Boolean, handlers: PrActionHandlers,
    modifier: Modifier = Modifier, initialMergeDialog: Boolean = false, prKey: PullRequestKey? = null,
) {
    key(prKey) { ActionsSectionBody(model, pending, localError, busy, handlers, modifier, initialMergeDialog) }
}

@Composable
private fun ActionsSectionBody(
    model: PrActionsModel, pending: PendingPrAction?, localError: String?, busy: Boolean, handlers: PrActionHandlers,
    modifier: Modifier, initialMergeDialog: Boolean,
) {
    val c = Tf.colors
    val pendingText = pendingText(pending)
    val sendOk = !busy
    var confirmMerge by rememberSaveable { mutableStateOf(initialMergeDialog) }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pendingText != null) PendingBanner(pendingText)
        if (localError != null) Text(localError, style = TrefoilType.meta, color = c.red)
        if (model.disabledReason != null) Text(model.disabledReason, style = TrefoilType.meta, color = c.muted,)
        ActionButton("Merge", model.merge && sendOk, primary = true) { confirmMerge = true }
        ActionButton("Update branch", model.updateBranch && sendOk) { handlers.onRun(PrAction.UpdateBranch, null) }
        if (model.showReady) ActionButton("Ready for review", model.ready && sendOk) { handlers.onRun(PrAction.Ready, null) }
        else ActionButton("Convert to draft", model.draft && sendOk) { handlers.onRun(PrAction.Draft, null) }
        CommentComposer(enabled = model.comment && sendOk, pending = pending, onSend = handlers.onComment)
    }
    if (confirmMerge && model.merge) MergeDialog(model, confirmEnabled = sendOk, onDismiss = { confirmMerge = false }) { method -> confirmMerge = false; handlers.onRun(PrAction.Merge, method) }
    else if (confirmMerge) confirmMerge = false
}

@Composable
private fun PendingBanner(text: PrPendingText) {
    val c = Tf.colors
    val tone = if (text.error) c.amber else c.blue
    Row(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tone.copy(alpha = .5f)), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (text.progress) CircularProgressIndicator(Modifier.size(18.dp), color = tone, strokeWidth = 2.dp)
        Text(text.text, style = TrefoilType.meta, color = c.text)
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    if (primary) Button(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = shape) { Text(label) }
    else OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = shape) { Text(label) }
}

@Composable
private fun CommentComposer(enabled: Boolean, pending: PendingPrAction?, onSend: (String) -> Unit) {
    val c = Tf.colors
    var text by rememberSaveable { mutableStateOf("") }
    // The draft leaves once the session has taken the comment (sending, done, or uncertain: a resend would double-post). A rejected or
    // unsent comment keeps its text so it can be fixed and sent again by the user.
    LaunchedEffect(pending) {
        val outcome = pending?.outcome
        if (pending?.kind == PrActionKind.Comment && outcome != null && outcome !is PrActionOutcome.Rejected) text = ""
    }
    val tooLong = text.length > PR_COMMENT_MAX_CHARS
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("COMMENT", style = TrefoilType.section, color = c.muted)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("Write a comment") }, isError = tooLong, minLines = 3, maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (tooLong) Text("A comment can be at most $PR_COMMENT_MAX_CHARS characters (now ${text.length}).", style = TrefoilType.meta, color = c.red)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onSend(text) },
                enabled = enabled && isValidComment(text), modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
            ) { Text("Comment") }
        }
    }
}

@Composable
private fun MergeDialog(model: PrActionsModel, confirmEnabled: Boolean, onDismiss: () -> Unit, onConfirm: (PrMergeMethod) -> Unit) {
    val c = Tf.colors
    val methods = model.mergeMethods
    var picked by rememberSaveable { mutableStateOf(defaultMergeMethod(methods)?.wire) }
    val method = methods.firstOrNull { it.wire == picked } ?: defaultMergeMethod(methods)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (model.number != null) "Merge #${model.number}?" else "Merge pull request?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (model.baseRef != null) "This merges the pull request into ${model.baseRef} on the code host. It cannot be undone from here."
                    else "This merges the pull request on the code host. It cannot be undone from here.",
                    style = TrefoilType.preview, color = c.text,
                )
                Column(Modifier.selectableGroup()) {
                    methods.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRing(RoundedCornerShape(8.dp)).handPointer()
                                .selectable(m == method, role = Role.RadioButton, onClick = { picked = m.wire }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = m == method, onClick = null)
                            Text(mergeMethodLabel(m), Modifier.padding(start = 12.dp), style = TrefoilType.preview, color = c.text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { method?.let(onConfirm) }, enabled = method != null && confirmEnabled) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
