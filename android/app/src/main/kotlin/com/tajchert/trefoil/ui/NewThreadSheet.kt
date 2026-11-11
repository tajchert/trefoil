package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*

/** Modes the model supports; an empty or missing list means every mode is allowed. */
internal fun allowedRuntimeModes(model: ModelOption?): List<RuntimeMode> =
    model?.supportedRuntimeModes?.takeIf { it.isNotEmpty() }?.let { supported -> RuntimeMode.entries.filter { it in supported } }?.takeIf { it.isNotEmpty() } ?: RuntimeMode.entries.toList()

/** Keeps [preferred] when allowed, otherwise falls back to the first allowed mode. */
internal fun resolveRuntimeMode(preferred: RuntimeMode?, allowed: List<RuntimeMode>): RuntimeMode =
    preferred?.takeIf { it in allowed } ?: allowed.first()

/** Compatibility wrapper for callers that start a draft without an initial prompt. */
@Composable
internal fun NewThreadDialog(state: ClientState, dismiss: () -> Unit, start: (ThreadDraft) -> Unit) =
    NewThreadSheet(state, dismiss) { draft, _ -> start(draft) }

/**
 * New thread: pick project, model and mode, optionally type the first prompt.
 * [start] receives the draft and the prompt (blank when none); the shell starts the draft and
 * hands the prompt to the conversation, which sends it through its normal composer path.
 */
@Composable
internal fun NewThreadSheet(state: ClientState, dismiss: () -> Unit, start: (ThreadDraft, String) -> Unit) {
    var projectId by rememberSaveable { mutableStateOf(state.projects.firstOrNull()?.id.orEmpty()) }
    var modelIndex by rememberSaveable { mutableIntStateOf(0) }
    var chosenMode by rememberSaveable { mutableStateOf((state.defaultRuntimeMode ?: RuntimeMode.ApprovalRequired).wire) }
    val model = state.models.getOrNull(modelIndex)
    val allowedModes = allowedRuntimeModes(model)
    val mode = resolveRuntimeMode(RuntimeMode.fromWire(chosenMode), allowedModes)
    AlertDialog(onDismissRequest = dismiss, title = { Text("New conversation") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PROJECT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            state.projects.forEach { project ->
                ChoiceRow(projectId == project.id, { projectId = project.id }) { Text(project.title) }
            }
            Text("PROVIDER / MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            if (state.models.isEmpty()) Text("No models reported by this environment. Reconnect to refresh configuration.")
            state.models.forEachIndexed { index, option ->
                ChoiceRow(modelIndex == index, { modelIndex = index }) { Column { Text(option.label); Text(option.instanceId, style = MaterialTheme.typography.labelSmall) } }
            }
            Text("MODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            allowedModes.forEach { option ->
                ChoiceRow(mode == option, { chosenMode = option.wire }) { Column { Text(option.label); Text(option.description, style = MaterialTheme.typography.labelSmall) } }
            }
            Text("Workspace uses the server default.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = {
        Button(onClick = { model?.let { start(ThreadDraft(projectId, it, mode), "") } },
            enabled = state.canWrite && !state.busy && projectId.isNotBlank() && model != null) { Text("Start") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}
