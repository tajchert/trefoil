/* Agents sheet layout adapted from T3 Code mobile thread agents UI (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ProviderInstance
import kotlinx.coroutines.delay

/** One prebuilt row of the Agents sheet; the content composable never touches the domain model. */
internal data class AgentRowUi(
    val id: String, val title: String, val statusLabel: String, val tone: SubagentTone,
    val elapsed: String? = null, val modelLine: String? = null, val detail: String? = null, val runsOn: String? = null,
    val openThreadId: String? = null, val canStop: Boolean = false,
)

/** Maps the roster onto row models. [canWrite] gates Stop on top of the subagent's own rule. */
internal fun agentRows(roster: SubagentRoster, providers: List<ProviderInstance>, models: List<ModelOption>, now: Long, canWrite: Boolean): List<AgentRowUi> =
    roster.subagents.map { s ->
        AgentRowUi(
            id = s.id, title = subagentRowTitle(s), statusLabel = subagentStatusLabel(s.status), tone = subagentTone(s.status),
            elapsed = subagentElapsedMs(s, now)?.let(::formatSubagentDuration),
            modelLine = subagentModelLine(s, providers, models),
            detail = subagentWorkflowSummary(s) ?: subagentDetail(s),
            runsOn = s.remoteChild?.let { it.label.ifBlank { it.environmentId } },
            openThreadId = subagentOpenThreadId(s), canStop = s.canStop && canWrite,
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadAgentsSheet(
    roster: SubagentRoster, providers: List<ProviderInstance>, models: List<ModelOption>, canWrite: Boolean,
    dismiss: () -> Unit, onOpen: (String) -> Unit, onStop: (String) -> Unit,
) {
    val c = Tf.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(roster.liveCount > 0) {
        now = System.currentTimeMillis()
        while (roster.liveCount > 0) { delay(1000); now = System.currentTimeMillis() }
    }
    val rows = remember(roster, providers, models, now, canWrite) { agentRows(roster, providers, models, now, canWrite) }
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
    ) { ThreadAgentsSheetContent(rows, onOpen, onStop, Modifier.navigationBarsPadding()) }
}

/** Sheet body, separate from the [ModalBottomSheet] chrome so it renders and tests standalone. */
@Composable
internal fun ThreadAgentsSheetContent(rows: List<AgentRowUi>, onOpen: (String) -> Unit, onStop: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    var stopping by remember { mutableStateOf<AgentRowUi?>(null) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        Text("Agents", Modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall, color = c.text)
        if (rows.isEmpty()) Text("No agents in this turn.", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp), style = TrefoilType.preview, color = c.muted)
        rows.forEach { row ->
            // Re-resolve the pending stop target from live rows so a settled agent cannot be stopped from a stale dialog.
            AgentRow(row, onOpen, { stopping = row })
            if (row !== rows.last()) HorizontalDivider(color = c.line)
        }
    }
    stopping?.let { target ->
        AlertDialog(
            onDismissRequest = { stopping = null }, title = { Text("Stop agent?") }, text = { Text("Stop ${target.title}?") },
            confirmButton = { TextButton(onClick = { stopping = null; onStop(target.id) }) { Text("Stop", color = c.red) } },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AgentRow(row: AgentRowUi, onOpen: (String) -> Unit, onStop: () -> Unit) {
    val c = Tf.colors
    val toneColor = when (row.tone) { SubagentTone.Working -> c.blue; SubagentTone.Completed -> c.accent; SubagentTone.Failed -> c.red; SubagentTone.Stopped -> c.muted }
    val openId = row.openThreadId
    Row(
        Modifier.fillMaxWidth().testTag("agent-row-${row.id}")
            .then(if (openId != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${row.title}") { onOpen(openId) } else Modifier)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(toneColor, Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, Modifier.weight(1f, fill = false), style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("· ${row.statusLabel}", style = TrefoilType.meta, color = toneColor, maxLines = 1)
                if (row.elapsed != null) Text(row.elapsed, style = TrefoilType.meta, color = c.muted, maxLines = 1)
            }
            if (row.modelLine != null) Text(row.modelLine, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.detail != null) Text(row.detail, style = TrefoilType.preview, color = if (row.tone == SubagentTone.Failed) c.red else c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (row.runsOn != null) Text("on ${row.runsOn}", style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.canStop) IconButton(onClick = onStop, Modifier.size(40.dp)) { Icon(TfIcons.Stop, "Stop ${row.title}", Modifier.size(20.dp), tint = c.text) }
        if (openId != null) Icon(TfIcons.ChevronRight, null, Modifier.padding(top = 2.dp).size(20.dp), tint = c.muted)
    }
}
