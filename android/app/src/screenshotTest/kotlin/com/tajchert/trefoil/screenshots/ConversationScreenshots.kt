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
import com.tajchert.trefoil.ui.ActivityStack
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
import com.tajchert.trefoil.ui.SubagentWorkingRow
import com.tajchert.trefoil.ui.ThreadAgentsSheetContent
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.agentRows
import com.tajchert.trefoil.ui.subagentPill
import com.tajchert.trefoil.core.model.ClientState

private val fx = ConversationFixtures

@Composable
private fun Frame(dark: Boolean = false, content: @Composable () -> Unit) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable
private fun Screen(state: ClientState, dark: Boolean = false) {
    Frame(dark) { Conversation(state, send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = null) }
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

// ---------- Tool activity ----------

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 100)
@Composable
fun ActivityStack_Collapsed_Light() {
    Frame { Column(Modifier.padding(16.dp)) { ActivityStack(fx.mixedActivities) } }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 620)
@Composable
fun ActivityStack_Expanded_Light() {
    Frame { Column(Modifier.padding(16.dp)) { ActivityStack(fx.mixedActivities, initiallyExpanded = true) } }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 620, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ActivityStack_Expanded_Dark() {
    Frame(dark = true) { Column(Modifier.padding(16.dp)) { ActivityStack(fx.mixedActivities, initiallyExpanded = true) } }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 100)
@Composable
fun CompletedWork_Collapsed_Light() {
    Frame { Column(Modifier.padding(16.dp)) { CompletedWork(completedWork(), canLoadActivity = false, onLoadActivity = {}) } }
}

private fun completedWork() = ConversationEntry.Work(
    runId = "run-1", label = "Worked for 2m",
    entries = listOf(ConversationEntry.Activities(fx.mixedActivities)),
)

// ---------- Subagents ----------

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 100)
@Composable
fun SubagentWorkingRow_Light() {
    Frame { Column(Modifier.padding(16.dp)) { SubagentWorkingRow(fx.roster(), onOpenAgents = {}) } }
}

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
