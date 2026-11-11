package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*

/** Modes the model supports; an empty or missing list means every mode is allowed. */
internal fun allowedRuntimeModes(model: ModelOption?): List<RuntimeMode> =
    model?.supportedRuntimeModes?.takeIf { it.isNotEmpty() }?.let { supported -> RuntimeMode.entries.filter { it in supported } }?.takeIf { it.isNotEmpty() } ?: RuntimeMode.entries.toList()

/** Keeps [preferred] when allowed, otherwise falls back to the first allowed mode. */
internal fun resolveRuntimeMode(preferred: RuntimeMode?, allowed: List<RuntimeMode>): RuntimeMode =
    preferred?.takeIf { it in allowed } ?: allowed.first()

/**
 * New thread: pick project, model and mode, optionally type the first prompt.
 * [start] receives the draft and the prompt (blank when none); the shell starts the draft and
 * hands the prompt to the conversation, which sends it through its normal composer path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewThreadSheet(state: ClientState, dismiss: () -> Unit, start: (ThreadDraft, String) -> Unit) {
    val c = Tf.colors
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
    ) { NewThreadSheetContent(state, dismiss, start, Modifier.imePadding().navigationBarsPadding()) }
}

/** Sheet body, separate from the [ModalBottomSheet] chrome so it renders and tests standalone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewThreadSheetContent(state: ClientState, dismiss: () -> Unit, start: (ThreadDraft, String) -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    var projectId by rememberSaveable { mutableStateOf(state.projects.firstOrNull()?.id.orEmpty()) }
    var modelIndex by rememberSaveable { mutableIntStateOf(0) }
    var chosenMode by rememberSaveable { mutableStateOf((state.defaultRuntimeMode ?: RuntimeMode.ApprovalRequired).wire) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val model = state.models.getOrNull(modelIndex)
    val allowedModes = allowedRuntimeModes(model)
    val mode = resolveRuntimeMode(RuntimeMode.fromWire(chosenMode), allowedModes)
    val canStart = state.canWrite && !state.busy && projectId.isNotBlank() && model != null

    val filtered = state.projects.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    val limit = 8
    val shown = when {
        expanded || searching || filtered.size <= limit -> filtered
        else -> {
            val head = filtered.take(limit)
            val selected = filtered.firstOrNull { it.id == projectId }
            if (selected == null || selected in head) head else filtered.take(limit - 1) + selected
        }
    }
    val hidden = filtered.size - shown.size

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        Row(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New thread", style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = dismiss, Modifier.size(48.dp)) { Icon(TfIcons.Close, "Close", tint = c.text) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PROJECT", style = TrefoilType.section, color = c.muted, modifier = Modifier.weight(1f))
            if (state.projects.size > 1) IconButton(onClick = { searching = !searching; if (!searching) query = "" }, Modifier.size(40.dp)) {
                Icon(TfIcons.Search, "Search projects", Modifier.size(18.dp), tint = if (searching) c.accent else c.text)
            }
        }
        if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search projects") }, shape = TfFieldShape, colors = tfFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        if (state.projects.isEmpty()) Text("No projects reported by this environment.", style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { project -> ProjectChip(project, project.id == projectId) { projectId = project.id } }
            if (hidden > 0) Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClick = { expanded = true }).padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Show $hidden more projects" },
                contentAlignment = Alignment.Center,
            ) { Text("+$hidden", style = TrefoilType.meta.copy(fontSize = 14.sp), color = c.muted) }
        }

        Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(24.dp))) {
            TextField(
                prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), minLines = 5, maxLines = 10, enabled = !state.busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
                placeholder = { Text("What should the agent do?", fontSize = 17.sp, lineHeight = 24.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = c.accent, focusedPlaceholderColor = c.muted, unfocusedPlaceholderColor = c.muted,
                ),
            )
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = { model?.let { start(ThreadDraft(projectId, it, mode), prompt) } }, enabled = canStart, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.accent.copy(alpha = .35f), disabledContentColor = c.onAccent.copy(alpha = .7f)),
                ) { Icon(TfIcons.Send, "Start") }
            }
        }

        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var modelMenu by remember { mutableStateOf(false) }
            var modeMenu by remember { mutableStateOf(false) }
            Box {
                SheetPill(model?.label ?: "No models", TfIcons.Sparkle, "Model: ${model?.label ?: "not set"}", enabled = state.models.isNotEmpty()) { modelMenu = true }
                DropdownMenu(modelMenu, { modelMenu = false }, containerColor = c.sheet, shape = RoundedCornerShape(16.dp)) {
                    state.models.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Column { Text(option.label); Text(option.instanceId, style = TrefoilType.mono, color = c.muted) } },
                            trailingIcon = { if (index == modelIndex) Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.accent) },
                            onClick = { modelIndex = index; modelMenu = false })
                    }
                }
            }
            Box {
                SheetPill(mode.label, TfIcons.Shield, "Access: ${mode.label}") { modeMenu = true }
                DropdownMenu(modeMenu, { modeMenu = false }, containerColor = c.sheet, shape = RoundedCornerShape(16.dp)) {
                    allowedModes.forEach { option ->
                        DropdownMenuItem(
                            text = { Column { Text(option.label); Text(option.description, style = TrefoilType.meta, color = c.muted) } },
                            trailingIcon = { if (option == mode) Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.accent) },
                            onClick = { chosenMode = option.wire; modeMenu = false })
                    }
                }
            }
        }
        if (state.models.isEmpty()) Text("No models reported by this environment. Reconnect to refresh configuration.", style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
        Text("Workspace uses the server default.", style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
    }
}

@Composable
private fun ProjectChip(project: Project, selected: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.height(40.dp).widthIn(max = 280.dp).clip(shape).background(if (selected) c.accentSoft else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectTile(project.title, size = 24.dp, key = project.id)
        Text(project.title, style = TrefoilType.meta.copy(fontSize = 14.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SheetPill(label: String, icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.height(36.dp).clip(shape).background(c.s2, shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (enabled) c.text else c.muted)
        Text(label, style = TrefoilType.meta, color = if (enabled) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(TfIcons.ChevronDown, null, Modifier.size(14.dp), tint = c.muted)
    }
}
