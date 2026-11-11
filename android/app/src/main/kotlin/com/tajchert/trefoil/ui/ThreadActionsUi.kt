/* Thread references and action semantics adapted from T3 Code mobile (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.canSnoozeThread
import java.net.URLEncoder
import java.time.*

internal fun threadCopyReference(target: ThreadTarget): String {
    fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace(".", "%2E").replace("*", "%2A")
    return "https://app.t3.codes/threads/${segment(target.environmentId)}/${segment(target.threadId)}"
}

internal fun selectedThreadActionsVisible(wide: Boolean, showThread: Boolean, draft: ThreadDraft?, threadId: String?): Boolean =
    (wide || showThread) && draft == null && threadId != null

/** Overflow button for a thread (conversation header). List rows open [ThreadActionsDropdown] on long-press instead. */
@Composable
internal fun ThreadActionsMenu(state: ClientState, thread: ThreadSummary, onOpenAgents: (() -> Unit)? = null, onAction: (ThreadTarget, ThreadAction, String?) -> Unit) {
    val environmentId = state.environment?.id ?: return
    key(environmentId, thread.id) {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(TfIcons.More, "Thread actions for ${thread.title}", Modifier.size(22.dp)) }
            ThreadActionsDropdown(state, thread, onAction, open, { open = it }, onOpenAgents = onOpenAgents)
        }
    }
}

/** Thread action menu plus its rename/snooze dialogs, anchored to the caller's enclosing Box. */
@Composable
internal fun ThreadActionsDropdown(
    state: ClientState, thread: ThreadSummary, onAction: (ThreadTarget, ThreadAction, String?) -> Unit,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, offset: DpOffset = DpOffset.Zero,
    /** Opens the Agents sheet; null hides the item (no agents in the selected thread). */
    onOpenAgents: (() -> Unit)? = null,
) {
    val environmentId = state.environment?.id ?: return
    val target = ThreadTarget(environmentId, thread.id)
    key(target) {
        var rename by remember { mutableStateOf(false) }
        var title by remember(thread.title) { mutableStateOf(thread.title) }
        var snooze by remember { mutableStateOf(false) }
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        val writable = state.canWriteThreads && state.connection == ConnectionStatus.Connected && !state.busy && thread.deletedAt == null && !thread.archived && thread.archivedAt == null
        val configured = writable && state.threadActionsConfigured
        fun close() = onExpandedChange(false)
        fun act(action: ThreadAction, value: String? = null) { close(); onAction(target, action, value) }
        DropdownMenu(expanded, { close() }, offset = offset) {
            if (onOpenAgents != null) {
                DropdownMenuItem(text = { Text("Agents") }, leadingIcon = { Icon(TfIcons.Agents, null, Modifier.size(18.dp)) }, onClick = { close(); onOpenAgents() })
                HorizontalDivider()
            }
            DropdownMenuItem(text = { Text(if (thread.settledOverride == "settled") "Un-settle" else "Settle") }, enabled = writable,
                onClick = { act(if (thread.settledOverride == "settled") ThreadAction.Unsettle else ThreadAction.Settle) })
            DropdownMenuItem(text = { Text(if (thread.pinnedAt != null) "Unpin" else "Pin") }, enabled = configured,
                onClick = { act(if (thread.pinnedAt != null) ThreadAction.Unpin else ThreadAction.Pin) })
            DropdownMenuItem(text = { Text("Rename") }, enabled = writable, onClick = { close(); rename = true })
            val sleeping = runCatching { Instant.parse(thread.snoozedUntil).isAfter(Instant.now()) }.getOrDefault(false)
            DropdownMenuItem(text = { Text(if (sleeping) "Wake" else "Snooze…") }, enabled = configured && (sleeping || canSnoozeThread(thread)),
                onClick = { if (sleeping) act(ThreadAction.Wake) else { close(); snooze = true } })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Copy thread") }, onClick = { clipboard.setText(AnnotatedString(threadCopyReference(target))); close() })
            DropdownMenuItem(text = { Text("Copy thread ID") }, onClick = { clipboard.setText(AnnotatedString(target.threadId)); close() })
        }
        if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Rename thread") }, text = {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
        }, confirmButton = { TextButton(enabled = writable && title.isNotBlank(), onClick = { rename = false; act(ThreadAction.Rename, title) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
        if (snooze) AlertDialog(onDismissRequest = { snooze = false }, title = { Text("Snooze thread") }, text = {
            Column {
                listOf("For one hour" to ZonedDateTime.now().plusHours(1), "Tomorrow at 9 AM" to LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()), "In one week" to ZonedDateTime.now().plusWeeks(1)).forEach { (label, time) ->
                    TextButton(enabled = configured, onClick = { snooze = false; act(ThreadAction.Snooze, time.toInstant().toString()) }) { Text(label) }
                }
                TextButton(enabled = configured, onClick = {
                    snooze = false
                    val now = LocalDateTime.now()
                    DatePickerDialog(context, { _, year, month, day ->
                        TimePickerDialog(context, { _, hour, minute ->
                            val time = LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant()
                            act(ThreadAction.Snooze, time.toString())
                        }, now.hour, now.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
                    }, now.year, now.monthValue - 1, now.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() }.show()
                }) { Text("Custom date and time…") }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { snooze = false }) { Text("Cancel") } })
    }
}
