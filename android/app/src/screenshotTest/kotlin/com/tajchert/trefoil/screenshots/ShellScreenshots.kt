package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionAttempt
import com.tajchert.trefoil.core.model.ConnectionKind
import com.tajchert.trefoil.core.model.ConnectionPhase
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ModelDefault
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.ui.ConnectionBanner
import com.tajchert.trefoil.ui.MachinesScreen
import com.tajchert.trefoil.ui.NewThreadSheetContent
import com.tajchert.trefoil.ui.PairDialog
import com.tajchert.trefoil.ui.SshConnectionDialog
import com.tajchert.trefoil.ui.SshHostKeyDialog
import com.tajchert.trefoil.ui.ShellMenuItems
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.ThreadListEmpty
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.UsageUiState
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation
import com.tajchert.trefoil.ui.adaptive.LocalFold
import com.tajchert.trefoil.ui.adaptive.ThreadListChrome
import com.tajchert.trefoil.ui.shell.ShellDestination
import com.tajchert.trefoil.ui.shell.ShellNavModel
import com.tajchert.trefoil.ui.shell.ShellNavigationRail

private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun Phone(dark: Boolean = false, content: @Composable () -> Unit) {
    TrefoilTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().background(Tf.colors.bg)) { content() } }
}

@Composable
private fun BannerList(status: ConnectionStatus, error: String? = null, dark: Boolean = false) = Phone(dark) {
    val state = ShellFixtures.withConnection(status, error)
    ThreadList(state, select = {}, create = {}, banner = { ConnectionBanner(state, reconnect = {}) })
}

@Composable
private fun Machines(saved: List<SavedEnvironment>, state: ClientState, dark: Boolean = false) = Phone(dark) {
    MachinesScreen(state, saved, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = {}, probes = ShellFixtures.probes)
}

// ThreadList states

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_Empty_Light() = Phone { ThreadList(ShellFixtures.noThreads(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun ThreadList_Empty_Dark() = Phone(true) { ThreadList(ShellFixtures.noThreads(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 400)
@Composable fun ThreadList_SearchNoResults_Light() = Phone { ThreadListEmpty(searching = true) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_NeedsApproval_Light() = Phone { ThreadList(ShellFixtures.needsApproval(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun ThreadList_NeedsApproval_Dark() = Phone(true) { ThreadList(ShellFixtures.needsApproval(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_BannerDisconnected_Light() = BannerList(ConnectionStatus.Disconnected)

// No Reconnecting/Connecting banner preview: ConnectionBanner hides itself for 1.5 s after connecting
// starts (LaunchedEffect delay), so a static render never shows it.

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_BannerError_Light() = BannerList(ConnectionStatus.Error, "Could not reach the server. Check the network.")

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun ThreadList_BannerError_Dark() = BannerList(ConnectionStatus.Error, "Could not reach the server. Check the network.", dark = true)

// Machines

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Machines_Mixed_Light() = Machines(ShellFixtures.saved, ShellFixtures.connected())

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun Machines_Mixed_Dark() = Machines(ShellFixtures.saved, ShellFixtures.connected(), dark = true)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Machines_Connecting_Light() = Machines(ShellFixtures.saved, ShellFixtures.withConnection(ConnectionStatus.Connecting))

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Machines_Empty_Light() = Machines(emptyList(), ShellFixtures.nothingConnected())

// Dialogs (content rendered directly; layoutlib does not host dialog windows)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun PairDialog_Idle_Light() = Phone { PairDialog(busy = false, dismiss = {}, pair = { _, _, _ -> }) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun PairDialog_Failed_Light() = Phone {
    PairDialog(busy = false, dismiss = {}, attempt = ConnectionAttempt(1, ConnectionKind.Pairing, ConnectionPhase.Failed, "The pairing link has expired."), pair = { _, _, _ -> })
}

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun SshConnectionDialog_Empty_Light() = Phone { SshConnectionDialog(busy = false, initial = null, dismiss = {}, pickerActive = {}, connect = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun SshConnectionDialog_Empty_Dark() = Phone(true) { SshConnectionDialog(busy = false, initial = null, dismiss = {}, pickerActive = {}, connect = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun SshHostKeyDialog_Light() = Phone { SshHostKeyDialog(ShellFixtures.hostKey, trust = {}, cancel = {}) }

// Sheet and tablet

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun NewThreadSheet_Light() = Phone { Column(Modifier.fillMaxWidth()) { NewThreadSheetContent(ScreenshotFixtures.connectedState().copy(models = ScreenshotFixtures.reasoningModels), dismiss = {}, start = { _, _ -> }) } }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun NewThreadSheet_Collapsed_Light() = Phone { Column(Modifier.fillMaxWidth()) { NewThreadSheetContent(ScreenshotFixtures.connectedState().copy(models = ScreenshotFixtures.reasoningModels), dismiss = {}, start = { _, _ -> }, initiallyCollapsed = true) } }

private val manyProjects = listOf("api-gateway", "trefoil", "website", "infra", "docs", "mobile-ios", "billing", "design-system", "analytics")
    .map { Project(id = "p-$it", title = it, path = "/home/dev/work/$it") }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun NewThreadSheet_ManyProjects_Light() = Phone { Column(Modifier.fillMaxWidth()) {
    val threads = listOf("trefoil" to 3L, "billing" to 50L, "docs" to 60L * 30, "website" to 60L * 24 * 3).map { (name, minutes) ->
        ScreenshotFixtures.thread("t-$name", "Work in $name", project = manyProjects.first { it.title == name }, updatedMinutesAgo = minutes)
    }
    NewThreadSheetContent(ScreenshotFixtures.connectedState(threads).copy(projects = manyProjects, models = ScreenshotFixtures.reasoningModels,
        defaultModelSelection = ModelDefault("claudeAgent", "claude-opus", mapOf("effort" to "max"))), dismiss = {}, start = { _, _ -> })
} }

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_RailAndList_Light() = Phone {
    val state = ShellFixtures.connected()
    Row(Modifier.fillMaxSize()) {
        ShellNavigationRail(ShellNavModel(state, ShellFixtures.saved, ShellFixtures.probes, ShellFixtures.noActions, ShellDestination.Threads,
            portBrowserOpen = false, needsYou = 1, onThreads = {}, onNewThread = {}, onSelectMachine = {}))
        Box(Modifier.width(420.dp)) { ThreadList(state, select = {}, create = {}, shell = ShellFixtures.noActions, chrome = ThreadListChrome(railPresent = true)) }
    }
}

@Composable
private fun KeepConnectedMenu(dark: Boolean, checked: Boolean) = Phone(dark) {
    val actions = ShellFixtures.noActions.copy(keepConnected = checked, onKeepConnectedChange = {}, agentNotifications = !checked, onAgentNotificationsChange = {})
    Box(Modifier.fillMaxSize()) { Surface(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp), color = Tf.colors.sheet,
        shape = RoundedCornerShape(16.dp), shadowElevation = 3.dp, tonalElevation = 3.dp) {
        androidx.compose.foundation.layout.Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp, max = 280.dp).padding(vertical = 8.dp)) {
            ShellMenuItems(ShellFixtures.connected(), actions, close = {})
        }
    } }
}

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 460)
@Composable fun Shell_KeepConnectedMenu_On_Light() = KeepConnectedMenu(dark = false, checked = true)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 460, uiMode = NIGHT)
@Composable fun Shell_KeepConnectedMenu_Off_Dark() = KeepConnectedMenu(dark = true, checked = false)

// Whole-app adaptive shell (TrefoilApp at tablet and foldable sizes): rail, drawer, collapsed list, book posture, reconnecting,
// and a secondary destination beside the rail.

@Composable
private fun App(
    state: ClientState = FormFactorFixtures.conversationState(), start: ShellStart = ShellStart(), fold: Fold? = null,
    usage: UsageUiState = UsageUiState(),
) = CompositionLocalProvider(LocalFold provides fold) {
    TrefoilApp(state, listOf(ScreenshotFixtures.workstation), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {},
        usage = usage, start = start)
}

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun Tablet_App_RailPortrait_Light() = App()

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_RailLandscape_Light() = App()

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = NIGHT)
@Composable fun Tablet_App_RailLandscape_Dark() = App()

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Tablet_App_DrawerOpen_Light() = App(start = ShellStart(navigationOpen = true))

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_ListCollapsed_Light() = App(start = ShellStart(listCollapsed = true))

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Tablet_App_BookPosture_Light() = App(fold = Fold(FoldOrientation.Vertical, 330f, 343f, separating = true, halfOpened = true))

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_Offline_Light() = App(FormFactorFixtures.conversationState().copy(connection = ConnectionStatus.Disconnected))

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_UsageBesideRail_Light() = App(FormFactorFixtures.usageState(), ShellStart(showUsage = true), usage = FormFactorFixtures.usage())

// Round 2 polish: destinations beside a book posture hinge, the empty detail pane, and a large font on the rail.

private val bookFold = Fold(FoldOrientation.Vertical, 330f, 343f, separating = true, halfOpened = true)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Tablet_App_BookPosture_Usage_Light() = App(FormFactorFixtures.usageState(), ShellStart(showUsage = true), bookFold, FormFactorFixtures.usage())

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Tablet_App_BookPosture_Servers_Light() = App(FormFactorFixtures.portsState(), ShellStart(showPorts = true), bookFold)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Tablet_App_BookPosture_NotConnected_Light() = App(ShellFixtures.nothingConnected(), fold = bookFold)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_EmptyDetail_Light() = App(FormFactorFixtures.listState())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = NIGHT)
@Composable fun Tablet_App_EmptyDetail_Dark() = App(FormFactorFixtures.listState())

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun Tablet_App_EmptyDetail_FoldLandscape_Light() = App(ShellFixtures.quiet())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, fontScale = 2f)
@Composable fun Tablet_App_RailLandscape_FontScale2_Light() = App(FormFactorFixtures.usageState())

// Side panel beside the conversation (fake data only): Preview, Screens and Agents tabs, docked by width and around a hinge.
private val bookFoldLandscape = Fold(FoldOrientation.Vertical, 414f, 427f, separating = true, halfOpened = true)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_SidePanel_Preview_Light() = App(ShellFixtures.sidePanel(), ShellStart(showThread = true, sidePanelTab = "Preview"))

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = NIGHT)
@Composable fun Tablet_App_SidePanel_Preview_Dark() = App(ShellFixtures.sidePanel(), ShellStart(showThread = true, sidePanelTab = "Preview"))

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_SidePanel_Agents_Light() = App(ShellFixtures.sidePanel(), ShellStart(showThread = true, sidePanelTab = "Agents"))

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun Tablet_App_SidePanel_Screens_FoldLandscape_Light() = App(ShellFixtures.sidePanel(), ShellStart(showThread = true, sidePanelTab = "Screens"))

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun Tablet_App_SidePanel_BookPosture_Light() = App(ShellFixtures.sidePanel(), ShellStart(showThread = true, sidePanelTab = "Agents"), bookFoldLandscape)

@PreviewTest @Preview(showBackground = true, widthDp = 1920, heightDp = 1080)
@Composable fun Tablet_App_SidePanel_ThreePane_Desktop_Light() = App(ShellFixtures.sidePanel(), ShellStart(showThread = true, sidePanelTab = "Agents"))

private val inspectThread = ShellStart(showThread = true, sidePanelTab = "Thread", inspectorThreadId = ConversationFixtures.subagentThread.id)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_App_SidePanel_Thread_Light() = App(ShellFixtures.inspectorThread(), inspectThread)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = NIGHT)
@Composable fun Tablet_App_SidePanel_Thread_Dark() = App(ShellFixtures.inspectorThread(), inspectThread)

@PreviewTest @Preview(showBackground = true, widthDp = 1920, heightDp = 1080)
@Composable fun Tablet_App_SidePanel_Thread_ThreePane_Desktop_Light() = App(ShellFixtures.inspectorThread(), inspectThread)

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun Tablet_App_SidePanel_Thread_BookPosture_Light() = App(ShellFixtures.inspectorThread(), inspectThread, bookFoldLandscape)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Tablet_App_HeaderBudget_FoldPortrait_Light() = App(ShellFixtures.sidePanel(), ShellStart(showThread = true))
