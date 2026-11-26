package com.tajchert.trefoil.ui.diff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.CheckpointStatus
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.DiffChange
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.ThreadCheckpoint
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfChip
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.TfTwoPane
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.rememberTwoPaneState
import kotlinx.coroutines.launch

/*
 * Changes screen: the turns of the selected thread (newest first), the files of the open turn in review order with a hide-test-files
 * filter, and the DiffViewer. Phone and narrow windows stack picker, file list and diff; from [TWO_PANE_MIN_WIDTH_DP] the turns and
 * files sit beside the diff. Completed turns never change, so a loaded diff stays usable offline; only (re)loading needs a live
 * connection. Diff text, paths and server messages are conversation content: never log them.
 */

/** Width from which the turn and file lists sit beside the diff instead of above it. */
internal const val TWO_PANE_MIN_WIDTH_DP = 560

/** What the app gives the Changes screen: the read, and the device-local hide-tests preference. */
@Immutable
class ChangesUi(
    /** Reads and parses one turn's diff of the selected thread (`TrefoilClient.loadTurnDiff`); throws with a user-readable message. */
    val loadTurnDiff: suspend (turnCount: Int) -> DiffDocument = { error("Turn diffs are unavailable") },
    val hideTests: Boolean = false,
    val onHideTestsChange: (Boolean) -> Unit = {},
)

/** Lets a conversation row open the Changes screen: provided only while the selected thread has checkpoints. */
@Immutable
class ChangesEntry(
    /** Opens Changes at the turn of the run [runId], or at the newest turn when the run has no checkpoint (or is null). */
    val open: (runId: String?) -> Unit,
)

val LocalChangesEntry = compositionLocalOf<ChangesEntry?> { null }

/**
 * Stateful Changes screen for the selected thread. [selectedTurn] is hoisted (the conversation can open a given turn); a turn that
 * does not exist falls back to the newest. [onClose] adds a back arrow (a full-screen route); null where a panel closes it.
 */
@Composable
internal fun ChangesScreen(
    state: ClientState, ui: ChangesUi, selectedTurn: Int?, onSelectTurn: (Int) -> Unit, onClose: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val load = rememberUpdatedState(ui.loadTurnDiff)
    val loader = remember(state.environment?.id, state.selectedThreadId) { TurnDiffLoader(scope, load = { turn -> load.value(turn) }) }
    DisposableEffect(loader) { onDispose { loader.cancelAll() } }
    val turns = remember(state.checkpoints) { state.checkpoints.newestFirst() }
    val current = resolveChangesTurn(turns, selectedTurn)
    val live = state.connection == ConnectionStatus.Connected
    LaunchedEffect(loader, current, live) { if (current != null && live) loader.ensure(current) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    ChangesContent(
        turns = turns, selectedTurn = current, onSelectTurn = onSelectTurn,
        diff = current?.let(loader::stateOf), offline = !live, onReload = { current?.let(loader::reload) },
        hideTests = ui.hideTests, onHideTestsChange = ui.onHideTestsChange, wrap = wrap, onWrapChange = { wrap = it },
        onClose = onClose, modifier = modifier,
    )
}

/** Stateless Changes screen (screenshots and tests drive it directly). [diff] null means the turn's diff is not loaded and cannot be asked for now. */
@Composable
internal fun ChangesContent(
    turns: List<ThreadCheckpoint>, selectedTurn: Int?, onSelectTurn: (Int) -> Unit,
    diff: TurnDiffState?, offline: Boolean, onReload: () -> Unit,
    hideTests: Boolean, onHideTestsChange: (Boolean) -> Unit,
    wrap: Boolean, onWrapChange: (Boolean) -> Unit,
    onClose: (() -> Unit)?, modifier: Modifier = Modifier, twoPane: Boolean? = null,
) {
    val c = Tf.colors
    val selected = turns.firstOrNull { it.turnCount == selectedTurn }
    val document = (diff as? TurnDiffState.Loaded)?.document
    val arranged = remember(document, hideTests) { document?.arrangedForReview(hideTests) }
    val listState = remember(arranged) { LazyListState() }
    val collapse = rememberDiffCollapseState(arranged ?: DiffDocument.Empty)
    val scope = rememberCoroutineScope()
    val jump: (Int) -> Unit = { fileIndex -> arranged?.let { doc -> scope.launch { listState.scrollToFile(doc, fileIndex, collapse) } } }
    BoxWithConstraints(modifier.fillMaxSize().background(c.bg)) {
        val sideBySide = twoPane ?: (maxWidth >= TWO_PANE_MIN_WIDTH_DP.dp)
        Column(Modifier.fillMaxSize()) {
            ChangesHeader(selected, onClose, reloadEnabled = !offline && selected != null, onReload = onReload)
            if (offline && diff is TurnDiffState.Loaded) {
                Text(
                    "Offline. Showing the loaded diff; reloading is unavailable.", style = TrefoilType.meta, color = c.muted,
                    modifier = Modifier.fillMaxWidth().background(c.s2).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (turns.isEmpty()) {
                MessageState("No changes yet", "File changes show up here once the agent completes a turn that edits files.")
            } else if (sideBySide) {
                val paneState = rememberTwoPaneState(240.dp)
                TfTwoPane(
                    state = paneState, defaultStartWidth = 240.dp,
                    start = { StartPane(turns, selectedTurn, onSelectTurn, document, arranged, hideTests, onHideTestsChange, jump) },
                    end = { DiffArea(diff, offline, arranged, document, hideTests, onHideTestsChange, wrap, onWrapChange, listState, collapse, onReload) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    minStartWidth = 200.dp, maxStartWidth = 360.dp, collapseBelow = null, minEndWidth = 280.dp, dividerLabel = "Resize file list",
                )
            } else {
                TurnPicker(turns, selectedTurn, onSelectTurn)
                HorizontalDivider(color = c.line)
                if (document != null && document.files.isNotEmpty()) {
                    FilesStrip(arranged?.files.orEmpty(), document.files.size, hideTests, onHideTestsChange, jump)
                    HorizontalDivider(color = c.line)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    DiffArea(diff, offline, arranged, document, hideTests, onHideTestsChange, wrap, onWrapChange, listState, collapse, onReload)
                }
            }
        }
    }
}

@Composable
private fun ChangesHeader(selected: ThreadCheckpoint?, onClose: (() -> Unit)?, reloadEnabled: Boolean, onReload: () -> Unit) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(c.s1).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onClose != null) TfIconButton(TfIcons.Back, "Close changes", onClose, Modifier.size(48.dp), iconSize = 22.dp, tint = c.text)
        else Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(if (onClose != null) "Changes" else selected?.let { "Turn ${it.turnCount}" } ?: "Changes", style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (onClose != null) "Turn ${selected.turnCount} · ${filesLabel(selected.fileCount)}" else filesLabel(selected.fileCount), style = TrefoilType.meta, color = c.muted, maxLines = 1)
                    Text("+${selected.additions}", style = TrefoilType.mono, color = c.green)
                    Text("−${selected.deletions}", style = TrefoilType.mono, color = c.red)
                }
            }
        }
        TfIconButton(TfIcons.Refresh, "Reload diff", onReload, Modifier.size(48.dp), enabled = reloadEnabled, iconSize = 22.dp, tint = if (reloadEnabled) c.text else c.muted)
    }
    HorizontalDivider(color = c.line)
}

/** Phone: turns as a row of cards, newest first. */
@Composable
private fun TurnPicker(turns: List<ThreadCheckpoint>, selectedTurn: Int?, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    val selectedIndex = turns.indexOfFirst { it.turnCount == selectedTurn }
    LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) state.animateScrollToItem(selectedIndex) }
    LazyRow(
        Modifier.fillMaxWidth().background(Tf.colors.bg), state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(turns, key = { it.turnCount }) { turn -> TurnCard(turn, turn.turnCount == selectedTurn) { onSelect(turn.turnCount) } }
    }
}

@Composable
private fun TurnCard(turn: ThreadCheckpoint, selected: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(132.dp).heightIn(min = 64.dp).clip(shape).background(if (selected) c.accentSoft else c.s2)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.line), shape)
            .focusRing(shape).handPointer().selectable(selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = turnDescription(turn) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Turn ${turn.turnCount}", style = TrefoilType.meta.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
        Text(filesLabel(turn.fileCount), style = TrefoilType.meta, color = c.muted, maxLines = 1)
        TurnStats(turn)
    }
}

/** Wide: one row per turn in the start pane. */
@Composable
private fun TurnRow(turn: ThreadCheckpoint, selected: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected) c.accentSoft else c.bg)
            .focusRing(RoundedCornerShape(0.dp)).handPointer().selectable(selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = turnDescription(turn) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Turn ${turn.turnCount}", style = TrefoilType.meta.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
            Text(filesLabel(turn.fileCount), style = TrefoilType.meta, color = c.muted, maxLines = 1)
        }
        TurnStats(turn)
    }
}

@Composable
private fun TurnStats(turn: ThreadCheckpoint) {
    val c = Tf.colors
    if (turn.status != CheckpointStatus.Ready) {
        Text(turn.status.name, style = TrefoilType.meta, color = c.amber, maxLines = 1)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("+${turn.additions}", style = TrefoilType.mono, color = c.green, maxLines = 1)
            Text("−${turn.deletions}", style = TrefoilType.mono, color = c.red, maxLines = 1)
        }
    }
}

private fun turnDescription(turn: ThreadCheckpoint): String =
    "Turn ${turn.turnCount}, ${filesLabel(turn.fileCount)}, ${turn.additions} added, ${turn.deletions} removed" +
        if (turn.status != CheckpointStatus.Ready) ", ${turn.status.name.lowercase()}" else ""

/** Phone: collapsible file list under the picker. Tapping a file jumps to it in the diff and folds the list. */
@Composable
private fun FilesStrip(files: List<DiffFile>, totalFiles: Int, hideTests: Boolean, onHideTests: (Boolean) -> Unit, onJump: (Int) -> Unit) {
    val c = Tf.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.s1)) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Hide file list" else "Show file list", role = Role.Button) { open = !open }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (open) TfIcons.ChevronDown else TfIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.muted)
                Text(fileCountLabel(files.size, totalFiles, hideTests), style = TrefoilType.meta, color = c.text, maxLines = 1)
            }
            TfChip("Hide tests", onClick = { onHideTests(!hideTests) }, selected = hideTests, dense = true)
        }
        if (open && files.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(files, key = { it.index }) { file -> FileRow(file) { open = false; onJump(file.index) } }
            }
        }
    }
}

private fun fileCountLabel(shown: Int, total: Int, hideTests: Boolean): String =
    if (hideTests && shown != total) "Files · $shown of $total" else "Files · $total"

/** Wide: turns, then files, in the start pane. */
@Composable
private fun StartPane(
    turns: List<ThreadCheckpoint>, selectedTurn: Int?, onSelectTurn: (Int) -> Unit, document: DiffDocument?, arranged: DiffDocument?,
    hideTests: Boolean, onHideTests: (Boolean) -> Unit, onJump: (Int) -> Unit,
) {
    val c = Tf.colors
    LazyColumn(Modifier.fillMaxSize().background(c.bg)) {
        item(key = "turns-heading", contentType = "heading") { SectionLabel("Turns") }
        items(turns, key = { "turn-${it.turnCount}" }, contentType = { "turn" }) { turn -> TurnRow(turn, turn.turnCount == selectedTurn) { onSelectTurn(turn.turnCount) } }
        if (document != null && document.files.isNotEmpty()) {
            item(key = "files-heading", contentType = "files-heading") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(fileCountLabel(arranged?.files?.size ?: 0, document.files.size, hideTests), Modifier.weight(1f))
                    TfChip("Hide tests", onClick = { onHideTests(!hideTests) }, selected = hideTests, dense = true)
                }
            }
            items(arranged?.files.orEmpty(), key = { "file-${it.index}" }, contentType = { "file" }) { file -> FileRow(file) { onJump(file.index) } }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = TrefoilType.section, color = Tf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun FileRow(file: DiffFile, onClick: () -> Unit) {
    val c = Tf.colors
    val name = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', "")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRing(RoundedCornerShape(0.dp)).handPointer()
            .clickable(onClickLabel = "Show in diff", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${file.path}, ${changeLabel(file.change)}" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChangeLetter(file.change)
        Column(Modifier.weight(1f)) {
            Text(name, style = TrefoilType.mono, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (directory.isNotEmpty()) Text(directory, style = TrefoilType.mono.copy(fontSize = 11.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (file.change != DiffChange.Binary) {
            Text("+${file.additions}", style = TrefoilType.mono, color = c.green, maxLines = 1)
            Text("−${file.deletions}", style = TrefoilType.mono, color = c.red, maxLines = 1)
        }
    }
}

private fun changeLabel(change: DiffChange): String = when (change) {
    DiffChange.Added -> "added"
    DiffChange.Deleted -> "deleted"
    DiffChange.Modified -> "modified"
    DiffChange.Renamed -> "renamed"
    DiffChange.Binary -> "binary"
}

@Composable
private fun ChangeLetter(change: DiffChange) {
    val c = Tf.colors
    val (letter, color) = when (change) {
        DiffChange.Added -> "A" to c.green
        DiffChange.Deleted -> "D" to c.red
        DiffChange.Modified -> "M" to c.blue
        DiffChange.Renamed -> "R" to c.amber
        DiffChange.Binary -> "B" to c.muted
    }
    Text(letter, style = TrefoilType.mono.copy(fontWeight = FontWeight.SemiBold), color = color, modifier = Modifier.width(14.dp), textAlign = TextAlign.Center)
}

/** The diff itself, or why there is none: loading, error, offline, an empty turn, or everything filtered out. */
@Composable
private fun DiffArea(
    diff: TurnDiffState?, offline: Boolean, arranged: DiffDocument?, document: DiffDocument?, hideTests: Boolean, onHideTests: (Boolean) -> Unit,
    wrap: Boolean, onWrapChange: (Boolean) -> Unit, listState: LazyListState, collapse: DiffCollapseState, onReload: () -> Unit,
) {
    val c = Tf.colors
    when {
        diff == null -> MessageState("Not connected", "Reconnect to load this turn's diff. Diffs you already opened stay available.")
        diff is TurnDiffState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 3.dp)
                Text("Loading diff…", style = TrefoilType.meta, color = c.muted)
            }
        }
        diff is TurnDiffState.Failed -> MessageState(
            "Couldn't load this diff", diff.message.takeIf { it != CHANGES_FALLBACK_ERROR },
            action = { OutlinedButton(onClick = onReload, enabled = !offline) { Text("Retry") } },
        )
        document == null || arranged == null -> Unit
        document.files.isEmpty() -> MessageState("No file changes", "This turn did not change any files.")
        arranged.files.isEmpty() -> MessageState(
            "Only test files changed", "Turn off “Hide tests” to see the ${filesLabel(document.files.size)}.",
            action = { TextButton(onClick = { onHideTests(false) }) { Text("Show tests") } },
        )
        else -> DiffViewer(arranged, Modifier.fillMaxSize(), wrap = wrap, onWrapChange = onWrapChange, state = listState, collapseState = collapse)
    }
}

@Composable
private fun MessageState(title: String, body: String?, action: (@Composable () -> Unit)? = null) {
    val c = Tf.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(TfIcons.Changes, null, Modifier.size(28.dp), tint = c.muted)
            Text(title, style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
            if (body != null) Text(body, style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
            if (action != null) action()
        }
    }
}

/** The button of a file_change row that opens the turn in Changes. */
@Composable
internal fun OpenInChangesButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(TfIcons.Changes, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Open in Changes")
    }
}
