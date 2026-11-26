package com.tajchert.trefoil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.InspectorError
import com.tajchert.trefoil.core.model.InspectorThread
import com.tajchert.trefoil.core.model.RuntimeRequest
import com.tajchert.trefoil.core.model.SideThreadKind

/** Icons only the inspector uses (the shared set lives in [TfIcons]). */
internal object InspectorIcons {
    /** Arrows pointing out of the corners: open the inspected thread full size. */
    val Maximize: ImageVector by lazy {
        ImageVector.Builder("maximize", 24.dp, 24.dp, 24f, 24f).apply {
            listOf("M14 4h6v6", "M10 20H4v-6", "M20 4l-7 7", "M4 20l7-7").forEach { d ->
                addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null)
            }
        }.build()
    }
}

/** What the inspector pane shows for the inspected thread. */
internal enum class InspectorPaneMode { Loading, Rows, TooLarge, RefreshFailed, Gone }

/**
 * The pane's state for [threadId]: a snapshot of another thread (the stream was just retargeted) counts as no snapshot. A refresh failure
 * with rows still on screen keeps the rows (the pane adds a retry banner); without rows it is the whole pane. Not ready and no rows is loading.
 */
internal fun inspectorPaneMode(inspector: InspectorThread?, threadId: String): InspectorPaneMode {
    val own = inspector?.takeIf { it.threadId == threadId } ?: return InspectorPaneMode.Loading
    val hasRows = own.messages.isNotEmpty() || own.activities.isNotEmpty() || own.requests.isNotEmpty()
    return when {
        own.error == InspectorError.Gone -> InspectorPaneMode.Gone
        own.error == InspectorError.TooLarge -> InspectorPaneMode.TooLarge
        own.error == InspectorError.RefreshFailed && !hasRows -> InspectorPaneMode.RefreshFailed
        !own.ready && !hasRows && own.error == null -> InspectorPaneMode.Loading
        else -> InspectorPaneMode.Rows
    }
}

/** The bar that replaces the composer under a read-only inspected thread. */
internal fun inspectorNotice(kind: SideThreadKind): String = when (kind) {
    SideThreadKind.SideChat -> "Read-only here · maximize to reply"
    SideThreadKind.Subagent -> "Read-only here · maximize to open it"
    SideThreadKind.ProviderSubagent -> "Runs on its own · read-only here"
}

/**
 * The selected-thread view of the inspected thread, for the shared [Conversation]: its rows replace the selected thread's, and everything
 * that belongs to the selected thread's own run (queue, steering, stop, suggestion, model picks) is cleared. Never writable.
 */
internal fun inspectorConversationState(state: ClientState, inspector: InspectorThread?, threadId: String): ClientState {
    val own = inspector?.takeIf { it.threadId == threadId }
    return state.copy(
        selectedThreadId = threadId,
        messages = own?.messages.orEmpty(), activities = own?.activities.orEmpty(), requests = own?.requests.orEmpty(),
        runs = own?.runs.orEmpty(), attempts = own?.attempts.orEmpty(), subagents = own?.subagents.orEmpty(), activeRunId = own?.activeRunId,
        backgroundWork = emptyList(), queuedMessages = emptyList(), queueHeld = false, canSteer = false, canStop = false,
        promptSuggestion = null, selectedRuntimeMode = null, selectedModel = null, selectedModelOptions = emptyMap(), selectedProviderLocked = false,
        busy = false, canWrite = false, canWriteThreads = false, settlementUndo = null,
    )
}

/**
 * The side panel's Thread tab: a subagent or side chat of the selected thread, read-only. [state] is the shell's state; the inspected rows
 * come from [ClientState.inspector]. [onRetry] reopens the stream, [onMaximize] opens the thread as the selected one and [onClose] leaves the
 * inspector (Gone). Nothing here writes: no composer, no approval buttons, no Stop.
 */
@Composable
internal fun InspectorThreadPane(
    state: ClientState, threadId: String, kind: SideThreadKind, onRetry: () -> Unit, onMaximize: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inspector = state.inspector?.takeIf { it.threadId == threadId }
    val subject = if (kind == SideThreadKind.SideChat) "side chat" else "subagent"
    when (inspectorPaneMode(state.inspector, threadId)) {
        InspectorPaneMode.Rows -> Column(modifier.fillMaxSize().testTag("inspector-pane")) {
            if (inspector?.error == InspectorError.RefreshFailed) InspectorRefreshBanner(onRetry)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Conversation(inspectorConversationState(state, inspector, threadId), send = {}, interrupt = {}, respond = { _, _, _ -> }, readOnlyNotice = inspectorNotice(kind))
            }
        }
        InspectorPaneMode.Loading -> InspectorStatus("inspector-loading", modifier) {
            CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "Loading $subject" }, strokeWidth = 3.dp)
            Text("Loading $subject…", style = TrefoilType.preview, color = Tf.colors.muted)
        }
        InspectorPaneMode.TooLarge -> InspectorStatus("inspector-too-large", modifier) {
            Text("This $subject is too large to preview here.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onMaximize) { Text("Maximize") }
        }
        InspectorPaneMode.RefreshFailed -> InspectorStatus("inspector-refresh-failed", modifier) {
            Text("Couldn't load this $subject.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
        InspectorPaneMode.Gone -> InspectorStatus("inspector-gone", modifier) {
            Text("This $subject is no longer available.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun InspectorStatus(tag: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) { content() }
}

@Composable
private fun InspectorRefreshBanner(onRetry: () -> Unit) {
    val c = Tf.colors
    Surface(Modifier.fillMaxWidth().testTag("inspector-refresh-banner"), color = c.s1, contentColor = c.text) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Couldn't refresh · showing the last copy", Modifier.weight(1f).padding(vertical = 10.dp), style = TrefoilType.meta, color = c.muted)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** The read-only bar of the inspector, in the composer's place. Stateless, like [ReadOnlySubagentBar]. */
@Composable
internal fun ReadOnlyInspectorBar(text: String) {
    if (LocalConversationLayout.current.wide) ReadingDock(top = 4.dp, bottom = 16.dp) { ReadOnlyInspectorBarCard(text, Modifier) }
    else ReadOnlyInspectorBarCard(text, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp, top = 4.dp))
}

@Composable
private fun ReadOnlyInspectorBarCard(text: String, modifier: Modifier) {
    val c = Tf.colors
    Surface(modifier.fillMaxWidth().testTag("inspector-readonly-bar"), shape = RoundedCornerShape(24.dp), color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = TrefoilType.meta, color = c.muted)
    }
}

/** A pending request of the inspected thread: shown, never answerable from here. */
@Composable
internal fun ReadOnlyRequestNotice(request: RuntimeRequest) {
    val c = Tf.colors
    Surface(Modifier.fillMaxWidth().testTag("inspector-request:${request.id}"), shape = RoundedCornerShape(16.dp), color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Needs you", dot = c.amber)
            Text(request.title, style = MaterialTheme.typography.titleSmall)
            Text("Maximize this thread to respond.", style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}
