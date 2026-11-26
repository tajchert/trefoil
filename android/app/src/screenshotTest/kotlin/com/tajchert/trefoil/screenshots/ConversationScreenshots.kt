package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.ui.AgentsChip
import com.tajchert.trefoil.ui.CompletedWork
import com.tajchert.trefoil.ui.Conversation
import com.tajchert.trefoil.ui.ConversationEntry
import com.tajchert.trefoil.ui.DownloadFileCard
import com.tajchert.trefoil.ui.InlineImageLoading
import com.tajchert.trefoil.ui.LoadedImageContent
import com.tajchert.trefoil.ui.LocalFileDownloadUi
import com.tajchert.trefoil.ui.MessageFileCards
import com.tajchert.trefoil.ui.QuickReplies
import com.tajchert.trefoil.ui.ReadOnlySubagentBar
import com.tajchert.trefoil.ui.RequestCard
import com.tajchert.trefoil.ui.ThreadAgentsSheetContent
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.LocalFixedClock
import com.tajchert.trefoil.ui.SubagentTimelineRowContent
import com.tajchert.trefoil.ui.ThinkingRow
import com.tajchert.trefoil.ui.ConversationComposer
import com.tajchert.trefoil.ui.MessageRow
import com.tajchert.trefoil.ui.joinComposerText
import com.tajchert.trefoil.ui.threadReferenceFor
import com.tajchert.trefoil.ui.WorkGroup
import com.tajchert.trefoil.ui.WorkingTurnHeader
import com.tajchert.trefoil.ui.subagentTimelineRow
import com.tajchert.trefoil.ui.agentRows
import com.tajchert.trefoil.ui.subagentPill
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ToolActivity

private val fx = ConversationFixtures

@Composable
private fun Frame(dark: Boolean = false, content: @Composable () -> Unit) {
    TrefoilTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalFixedClock provides fx.now) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

@Composable
private fun Screen(state: ClientState, dark: Boolean = false) {
    Frame(dark) { Conversation(state, send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = null, onOpenThread = {}) }
}

// ---------- Full conversation ----------

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_Chat_Light() = Screen(fx.chatState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_Chat_Dark() = Screen(fx.chatState(), dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_AgentWorking_Light() = Screen(fx.workingState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_AgentWorking_Dark() = Screen(fx.workingState(), dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_LiveSubagent_Light() = Screen(fx.liveSubagentState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_LiveSubagent_Dark() = Screen(fx.liveSubagentState(), dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_BackgroundWork_Light() = Frame {
    Conversation(fx.backgroundWorkState(), send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = {}, onOpenThread = {})
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_BackBadge_Light() = Frame {
    Conversation(fx.chatState(), send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = null, otherNeedsYou = 2)
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_BackBadge_Dark() = Frame(dark = true) {
    Conversation(fx.chatState(), send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = null, otherNeedsYou = 2)
}

/** Tall enough for the whole ten-turn window, so the list cannot scroll and the control row shows at the top. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 1400)
@Composable
fun Conversation_LoadEarlierTurns_Light() = Screen(fx.longThreadState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_LoadEarlierTurns_Dark() = Screen(fx.longThreadState(), dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_Disconnected_ReadOnly_Light() = Screen(fx.disconnectedState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_PendingApproval_Light() = Screen(fx.approvalState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_QuickReplies_Light() = Screen(fx.choiceState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 200)
@Composable
fun RequestCard_Approval_Light() {
    Frame { Column(Modifier.padding(16.dp)) { RequestCard(fx.approval, enabled = true) { _, _, _ -> } } }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 120)
@Composable
fun QuickReplies_Light() {
    Frame { QuickReplies(listOf(fx.choiceRequest), enabled = true) { _, _, _ -> } }
}

// ---------- Work log ----------

private const val W = 400

@Composable
private fun WorkFrame(dark: Boolean = false, content: @Composable () -> Unit) = Frame(dark) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 100)
@Composable
fun WorkGroup_MixedHeader_Light() = WorkFrame { WorkGroup(fx.commandReadActivities) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun WorkGroup_MixedHeader_Dark() = WorkFrame(dark = true) { WorkGroup(fx.commandReadActivities) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 260)
@Composable
fun WorkGroup_Expanded_Light() = WorkFrame { WorkGroup(fx.mixedActivities, initiallyExpanded = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 260, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun WorkGroup_Expanded_Dark() = WorkFrame(dark = true) { WorkGroup(fx.mixedActivities, initiallyExpanded = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 220)
@Composable
fun WorkGroup_FailedCommand_Light() = WorkFrame {
    WorkGroup(listOf(fx.failedCommand))
    WorkGroup(listOf(fx.failedCommand.copy(id = "a-fail-open")), initiallyExpanded = true)
}

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 220, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun WorkGroup_FailedCommand_Dark() = WorkFrame(dark = true) {
    WorkGroup(listOf(fx.failedCommand))
    WorkGroup(listOf(fx.failedCommand.copy(id = "a-fail-open")), initiallyExpanded = true)
}

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 220)
@Composable
fun WorkGroup_Reasoning_Light() = WorkFrame {
    WorkGroup(listOf(fx.reasoningActivity))
    WorkGroup(listOf(fx.reasoningActivity.copy(id = "a-think-open")), initiallyExpanded = true)
}

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 100)
@Composable
fun WorkGroup_LiveRunning_Light() = WorkFrame { WorkGroup(listOf(fx.commandActivity, fx.runningActivity), live = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun WorkGroup_LiveRunning_Dark() = WorkFrame(dark = true) { WorkGroup(listOf(fx.commandActivity, fx.runningActivity), live = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 80)
@Composable
fun CompletedWork_Collapsed_Light() = WorkFrame { CompletedWork(completedWork(), canLoadActivity = false, onLoadActivity = {}) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 80, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CompletedWork_Collapsed_Dark() = WorkFrame(dark = true) { CompletedWork(completedWork(), canLoadActivity = false, onLoadActivity = {}) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 260)
@Composable
fun CompletedWork_Expanded_Light() = WorkFrame { CompletedWork(completedWork(), canLoadActivity = false, onLoadActivity = {}, initiallyExpanded = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = W, heightDp = 260, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CompletedWork_Expanded_Dark() = WorkFrame(dark = true) { CompletedWork(completedWork(), canLoadActivity = false, onLoadActivity = {}, initiallyExpanded = true) }

private fun completedWork() = ConversationEntry.Work(
    runId = "run-1", label = "Worked for 2m",
    entries = listOf(
        ConversationEntry.Message(ConversationMessage(id = "m-mid", role = "assistant", text = "Let me check the preview setup first.", ordinal = 1)),
        ConversationEntry.Activities(fx.commandReadActivities),
        ConversationEntry.Activities(listOf(fx.reasoningActivity)),
    ),
)

// ---------- Subagents ----------

/** Working header, one inline row per agent state (running, completed, failed, running without detail), Thinking. */
@Composable
private fun LiveTurnRows(dark: Boolean) = WorkFrame(dark) {
    WorkingTurnHeader(startedAt = fx.now - 71_000)
    val now = java.time.Instant.parse("2026-01-01T10:00:43Z").toEpochMilli()
    fx.subagents.map { subagentTimelineRow(it.copy(startedAt = it.startedAt ?: "2026-01-01T10:00:00Z", progress = it.progress ?: "Reading ui/ThreadList.kt".takeIf { _ -> it.id == "s1" }), now) }
        .forEach { SubagentTimelineRowContent(it, onClick = {}) }
    ThinkingRow("Thinking")
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 340)
@Composable
fun LiveTurnRows_Light() = LiveTurnRows(dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 340, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LiveTurnRows_Dark() = LiveTurnRows(dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 120)
@Composable
fun ReadOnlySubagentBar_Light() {
    Frame { ReadOnlySubagentBar(fx.subagentThread, now = 0L, onOpenParent = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 80)
@Composable
fun AgentsChip_Light() {
    Frame { Column(Modifier.padding(16.dp)) { AgentsChip(subagentPill(fx.roster())!!, onClick = {}) } }
}

@Composable
private fun AgentsSheet(dark: Boolean) {
    val rows = agentRows(fx.roster(), providers = emptyList(), models = listOf(fx.model), now = 0L, canWrite = true)
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = com.tajchert.trefoil.ui.Tf.colors.sheet) {
            ThreadAgentsSheetContent(rows, onOpen = {}, onStop = {})
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560)
@Composable
fun ThreadAgentsSheet_Light() = AgentsSheet(dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadAgentsSheet_Dark() = AgentsSheet(dark = true)

// ---------- Files ----------

@Composable
private fun FileCard(dark: Boolean = false, ui: com.tajchert.trefoil.ui.FileDownloadUi, content: @Composable () -> Unit) {
    Frame(dark) { CompositionLocalProvider(LocalFileDownloadUi provides ui) { Column(Modifier.padding(16.dp)) { content() } } }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 120)
@Composable
fun DownloadFileCard_Idle_Light() = FileCard(ui = fx.downloadUi()) { DownloadFileCard(fx.apk) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 140)
@Composable
fun DownloadFileCard_Downloading_Light() = FileCard(ui = fx.downloadUi(fx.downloading(fx.apk))) { DownloadFileCard(fx.apk) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 160, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DownloadFileCard_Done_Dark() = FileCard(dark = true, ui = fx.downloadUi(fx.done(fx.report))) { DownloadFileCard(fx.report) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 160)
@Composable
fun DownloadFileCard_Failed_Light() = FileCard(ui = fx.downloadUi(fx.failed(fx.apk))) { DownloadFileCard(fx.apk) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 240)
@Composable
fun MessageFileCards_Light() = FileCard(ui = fx.downloadUi(fx.downloading(fx.apk))) { MessageFileCards(fx.fileMessage) }

// ---------- Inline images ----------

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 300)
@Composable
fun InlineImage_Loaded_Light() = FileCard(ui = fx.downloadUi()) { LoadedImageContent(fx.screenshot, fx.sampleImage()) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InlineImage_Loaded_Dark() = FileCard(dark = true, ui = fx.downloadUi()) { LoadedImageContent(fx.screenshot, fx.sampleImage()) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 300)
@Composable
fun InlineImage_Loading_Light() = FileCard(ui = fx.downloadUi()) { InlineImageLoading(canDownload = true) }

// ---------- System notices (T3 `system_notice`: warnings amber, `info` muted) ----------

@Composable
private fun SystemNotices() {
    val mcp = ToolActivity("notice-mcp", "MCP server RevenueCat failed to start", kind = "system_notice", status = "completed",
        detail = "The RevenueCat MCP server is not logged in. Run `codex mcp login RevenueCat`.")
    val info = ToolActivity("notice-info", "Branch renamed", kind = "system_notice", status = "completed", noticeLevel = "info",
        detail = "Renamed the worktree branch to feat/paywall.")
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkGroup(listOf(mcp))
        WorkGroup(listOf(info))
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Conversation_SystemNotices_Light() = Frame { SystemNotices() }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_SystemNotices_Dark() = Frame(dark = true) { SystemNotices() }

// ---------- Side chats and thread references ----------

private fun sideChat(id: String, title: String, createdAt: String, pendingRequestKind: String? = null) =
    ScreenshotFixtures.thread(id, title, pendingRequestKind = pendingRequestKind).copy(parentThreadId = fx.thread.id, sideChat = true, createdAt = createdAt, latestUserMessageAt = createdAt)

private val SideChats = listOf(
    sideChat("side-1", "Why the retry?", "2026-01-01T09:40:00Z"),
    sideChat("side-2", "Cap at three attempts", "2026-01-01T09:50:00Z", pendingRequestKind = "approval"),
    sideChat("side-3", "Mobile client limits", "2026-01-01T09:55:00Z", pendingRequestKind = "user_input"),
)

private fun sideChatsState() = fx.chatState().let { it.copy(sideChatsSupported = true, threads = it.threads + SideChats) }

/** The chips of the open thread's side chats above the composer, newest first; the two waiting ones carry an approval and an input dot. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_SideChatsRow_Light() = Screen(sideChatsState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_SideChatsRow_Dark() = Screen(sideChatsState(), dark = true)

private val References = listOf(threadReferenceFor("side-1", "Why the retry?"), threadReferenceFor("side-2", "Cap at three attempts"))

@Composable
private fun ReferenceComposer(dark: Boolean) = Frame(dark) {
    ConversationComposer(sideChatsState(), null, joinComposerText("Compare the two approaches.", emptyList(), References), {}, false, false, {}, {}, { _, _ -> }, stop = {}, onSend = {},
        onCancelQueued = {}, onSteerQueued = {}, onResumeQueue = {})
}

/** Thread references in the composer: one removable chip each above the words, in the same row style as the quotes. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Conversation_ReferenceChips_Light() = ReferenceComposer(dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_ReferenceChips_Dark() = ReferenceComposer(dark = true)

@Composable
private fun SentReferences(dark: Boolean) = Frame(dark) {
    val text = "Merge ${References[0].marker()} with ${References[1].marker()} and keep the shorter wording."
    Column(Modifier.padding(16.dp)) { MessageRow(ConversationMessage("user-ref", "user", text), stacked = false, showMetadata = false) }
}

/** A sent message that names threads: a chip with each label where the link was, never the raw `t3-context:` link. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Conversation_SentReferences_Light() = SentReferences(dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_SentReferences_Dark() = SentReferences(dark = true)
