package com.tajchert.trefoil.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.InspectorError
import com.tajchert.trefoil.core.model.InspectorThread
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.SideThreadKind
import com.tajchert.trefoil.ui.InspectorPaneMode
import com.tajchert.trefoil.ui.inspectorConversationState
import com.tajchert.trefoil.ui.inspectorNotice
import com.tajchert.trefoil.ui.inspectorPaneMode
import com.tajchert.trefoil.ui.adaptive.SidePanelMode
import com.tajchert.trefoil.ui.adaptive.WindowSpec
import com.tajchert.trefoil.ui.adaptive.shellPolicy
import com.tajchert.trefoil.ui.shell.SidePanelTab.Agents
import com.tajchert.trefoil.ui.shell.SidePanelTab.Changes
import com.tajchert.trefoil.ui.shell.SidePanelTab.Preview
import com.tajchert.trefoil.ui.shell.SidePanelTab.Screens
import com.tajchert.trefoil.ui.shell.SidePanelTab.Thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the side panel: which tabs exist, which one is shown, how a narrow window presents it, and the header budget. */
class SidePanelTest {
    @Test fun tabsFollowWhatTheMachineAndThreadOffer() {
        assertEquals(listOf(Preview, Screens, Agents), sidePanelTabs(preview = true, screens = true, agents = true))
        assertEquals(listOf(Screens), sidePanelTabs(preview = false, screens = true, agents = false))
        assertEquals(listOf(Preview, Agents), sidePanelTabs(preview = true, screens = false, agents = true))
        assertEquals(emptyList<SidePanelTab>(), sidePanelTabs(preview = false, screens = false, agents = false))
    }

    @Test fun changesIsTheLastTabAndOnlyExistsWithCheckpoints() {
        assertEquals(listOf(Preview, Screens, Agents, Changes), sidePanelTabs(preview = true, screens = true, agents = true, changes = true))
        assertEquals(listOf(Changes), sidePanelTabs(preview = false, screens = false, agents = false, changes = true))
        assertEquals(emptyList<SidePanelTab>(), sidePanelTabs(preview = false, screens = false, agents = false, changes = false))
        // Without checkpoints a saved Changes tab falls back to what is available.
        assertEquals(Preview, resolveSidePanelTab(Changes, listOf(Preview)))
    }

    @Test fun changesIsAFullScreenRouteInANarrowWindowAndDockedOtherwise() {
        val t = ShellDestination.Threads
        assertEquals(SidePanelPresentation(t, agentsSheet = false, docked = false, changesRoute = true), sidePanelPresentation(t, Changes, SidePanelMode.FullScreen))
        assertEquals(SidePanelPresentation(t, agentsSheet = false, docked = true), sidePanelPresentation(t, Changes, SidePanelMode.ThirdPane))
        assertFalse(sidePanelPresentation(t, Agents, SidePanelMode.FullScreen).changesRoute)
    }

    @Test fun aSavedTabThatWentAwayFallsBackToTheFirstUsableOneOrCloses() {
        assertNull(resolveSidePanelTab(null, listOf(Preview, Screens)))
        assertEquals(Agents, resolveSidePanelTab(Agents, listOf(Preview, Screens, Agents)))
        assertEquals(Preview, resolveSidePanelTab(Agents, listOf(Preview, Screens)))
        // A disabled Screens tab is no fallback, but an open one stays open.
        assertNull(resolveSidePanelTab(Agents, listOf(Screens), disabled = setOf(Screens)))
        assertEquals(Screens, resolveSidePanelTab(Screens, listOf(Screens), disabled = setOf(Screens)))
        assertNull(resolveSidePanelTab(Preview, emptyList()))
    }

    @Test fun theToggleOpensTheLastUsedTabOrTheFirstOne() {
        assertEquals(Agents, sidePanelOpenTarget(Agents, listOf(Preview, Screens, Agents)))
        assertEquals(Preview, sidePanelOpenTarget(null, listOf(Preview, Screens, Agents)))
        assertEquals(Screens, sidePanelOpenTarget(Agents, listOf(Screens)))
        assertEquals(Agents, sidePanelOpenTarget(Screens, listOf(Screens, Agents), disabled = setOf(Screens)))
        assertNull(sidePanelOpenTarget(null, emptyList()))
    }

    @Test fun aNarrowWindowMapsTheOpenTabInsteadOfDroppingIt() {
        val t = ShellDestination.Threads
        assertEquals(SidePanelPresentation(ShellDestination.Servers, false, false), sidePanelPresentation(t, Preview, SidePanelMode.FullScreen))
        assertEquals(SidePanelPresentation(ShellDestination.Screens, false, false), sidePanelPresentation(t, Screens, SidePanelMode.FullScreen))
        assertEquals(SidePanelPresentation(t, true, false), sidePanelPresentation(t, Agents, SidePanelMode.FullScreen))
        // Growing back docks the same tab again.
        for (tab in SidePanelTab.entries) {
            assertEquals(SidePanelPresentation(t, false, true), sidePanelPresentation(t, tab, SidePanelMode.ReplacesList))
            assertEquals(SidePanelPresentation(t, false, true), sidePanelPresentation(t, tab, SidePanelMode.ThirdPane))
        }
        assertEquals(SidePanelPresentation(t, false, false), sidePanelPresentation(t, null, SidePanelMode.ThirdPane))
        assertEquals(SidePanelPresentation(ShellDestination.Usage, false, false), sidePanelPresentation(ShellDestination.Usage, Agents, SidePanelMode.ThirdPane))
        // A full-screen viewer takes the window at every width.
        assertEquals(SidePanelPresentation(ShellDestination.Screens, false, false), sidePanelPresentation(t, Screens, SidePanelMode.ThirdPane, screenFullscreen = true))
        assertEquals(SidePanelPresentation(t, false, true), sidePanelPresentation(t, Agents, SidePanelMode.ThirdPane, screenFullscreen = true))
    }

    @Test fun backClosesADockedPanelBeforeLeavingTheThread() {
        assertEquals(ShellBack.ClosePanel, shellBack(ShellDestination.Threads, true, false, true, false, hasBackParent = true, panelDocked = true))
        assertEquals(ShellBack.ToParent, shellBack(ShellDestination.Threads, true, false, true, false, hasBackParent = true, panelDocked = false))
        // The page or viewer inside handles its own Back first.
        assertNull(shellBack(ShellDestination.Threads, true, portBrowserOpen = true, twoPane = true, showThread = false, hasBackParent = false, panelDocked = true))
    }

    @Test fun thePanelAndTheKeyboardLayoutNeverMeet() {
        // The keyboard layout needs compact height, where no panel ever docks.
        for (w in listOf(600f, 720f, 880f, 1280f, 1920f)) for (h in listOf(320f, 400f, 479f)) {
            val policy = shellPolicy(WindowSpec(w, h))
            assertTrue("compact height ${w}x$h", policy.compactHeight)
            assertEquals("no docked panel at ${w}x$h", SidePanelMode.FullScreen, effectiveSidePanelMode(policy))
        }
        assertEquals(SidePanelMode.ReplacesList, effectiveSidePanelMode(shellPolicy(WindowSpec(1280f, 800f))))
        assertEquals(SidePanelMode.ThirdPane, effectiveSidePanelMode(shellPolicy(WindowSpec(1920f, 1080f))))
        assertEquals(TransientList.PanelOpen, transientList(true, false, false, false, 800f, PaneFocus.Conversation, panelReplacesList = true))
        assertEquals(TransientList.None, transientList(true, split = true, compactHeight = false, imeVisible = false, roomAboveImeDp = 800f, focus = PaneFocus.None, panelReplacesList = true))
        assertEquals(TransientList.HideList, transientList(true, false, true, true, 100f, PaneFocus.Conversation, panelReplacesList = false))
    }

    @Test fun headerBudgetFollowsTheConversationPaneWidth() {
        data class Row(val window: Float, val pane: Float, val font: Float, val agents: Boolean, val servers: Boolean, val dockable: Boolean, val expected: HeaderBudget)
        val rows = listOf(
            // Phones keep today's header whatever the font scale.
            Row(400f, 400f, 1f, true, true, false, HeaderBudget(agentsChip = true, serversChip = true, toggle = false)),
            Row(400f, 400f, 2f, true, true, false, HeaderBudget(agentsChip = true, serversChip = true, toggle = false)),
            // FoldPortrait (no dock), TabletLandscape with the panel open (420 conversation).
            Row(673f, 336f, 1f, true, true, false, HeaderBudget(false, false, false, menuAgents = true, menuServers = true)),
            Row(1280f, 420f, 1f, true, true, true, HeaderBudget(false, false, true, menuAgents = true, menuServers = true)),
            Row(1280f, 420f, 1f, false, true, true, HeaderBudget(false, false, true, menuServers = true)),
            // One chip at 460-599, Agents first.
            Row(1000f, 520f, 1f, true, true, true, HeaderBudget(true, false, true, menuServers = true)),
            Row(1000f, 520f, 1f, false, true, true, HeaderBudget(false, true, true)),
            Row(1000f, 520f, 1f, true, false, true, HeaderBudget(true, false, true)),
            // Both from 600.
            Row(1280f, 600f, 1f, true, true, true, HeaderBudget(true, true, true)),
            Row(1920f, 940f, 1f, true, true, true, HeaderBudget(true, true, true)),
            // Large text demotes earlier: 1.5 below 680, 2.0 below 840.
            Row(1280f, 679f, 1.5f, true, true, true, HeaderBudget(false, false, true, menuAgents = true, menuServers = true)),
            Row(1280f, 680f, 1.5f, true, true, true, HeaderBudget(true, true, true)),
            Row(1280f, 839f, 2f, true, true, true, HeaderBudget(false, false, true, menuAgents = true, menuServers = true)),
            Row(1920f, 840f, 2f, true, true, true, HeaderBudget(true, true, true)),
            Row(1280f, 700f, 1.3f, true, true, true, HeaderBudget(true, true, true)),
        )
        rows.forEach { r -> assertEquals(r.toString(), r.expected, headerBudget(r.window, r.pane, r.font, r.agents, r.servers, r.dockable)) }
    }

    @Test fun anOpenPanelDropsTheToggleOnlyBelow380dp() {
        assertEquals(false, headerBudget(841f, 332f, 1f, true, true, true, panelOpen = true).toggle)
        assertEquals(false, headerBudget(841f, 379f, 1f, true, true, true, panelOpen = true).toggle)
        assertEquals(true, headerBudget(841f, 380f, 1f, true, true, true, panelOpen = true).toggle)
        // Closed, the toggle that opens the panel stays however narrow the pane is.
        assertEquals(true, headerBudget(841f, 332f, 1f, true, true, true, panelOpen = false).toggle)
        // Chips are unaffected by the panel state.
        assertEquals(headerBudget(841f, 332f, 1f, true, true, true).copy(toggle = false), headerBudget(841f, 332f, 1f, true, true, true, panelOpen = true))
    }

    @Test fun demotedServersLabelNamesThePort() {
        assertEquals("Dev servers (:5173)", serversMenuLabel(listOf(5173)))
        assertEquals("Dev servers (3)", serversMenuLabel(listOf(3000, 5173, 8080)))
        assertFalse(serversMenuLabel(listOf(1)).isEmpty())
    }

    @Test fun theThreadTabExistsOnlyWhileATargetIsSetAndComesLast() {
        assertEquals(listOf(Preview, Screens, Agents, Thread), sidePanelTabs(preview = true, screens = true, agents = true, thread = true))
        assertEquals(listOf(Thread), sidePanelTabs(preview = false, screens = false, agents = false, thread = true))
        assertEquals(listOf(Preview, Screens, Agents), sidePanelTabs(preview = true, screens = true, agents = true))
        assertEquals("Side chat", threadTabLabel(SideThreadKind.SideChat))
        assertEquals("Subagent", threadTabLabel(SideThreadKind.Subagent))
        assertEquals("Subagent", threadTabLabel(SideThreadKind.ProviderSubagent))
        assertEquals("Subagent", threadTabLabel(null))
        // Without its target the saved Thread tab falls back like any tab that went away; disabled tabs are no fallback.
        assertEquals(Preview, resolveSidePanelTab(Thread, listOf(Preview, Screens)))
        assertNull(resolveSidePanelTab(Thread, listOf(Screens), disabled = setOf(Screens)))
        assertEquals(Thread, resolveSidePanelTab(Thread, listOf(Agents, Thread)))
        // The toggle never opens a Thread tab by itself.
        assertEquals(Agents, sidePanelOpenTarget(null, listOf(Agents, Thread)))
    }

    @Test fun aNarrowWindowNeitherDocksNorOpensASheetForTheThreadTab() {
        val t = ShellDestination.Threads
        // The target survives in the shell; the presentation just shows the thread itself.
        assertEquals(SidePanelPresentation(t, false, false), sidePanelPresentation(t, Thread, SidePanelMode.FullScreen))
        assertEquals(SidePanelPresentation(t, false, true), sidePanelPresentation(t, Thread, SidePanelMode.ReplacesList))
        assertEquals(SidePanelPresentation(ShellDestination.Usage, false, false), sidePanelPresentation(ShellDestination.Usage, Thread, SidePanelMode.ThirdPane))
    }

    @Test fun backLeavesTheInspectorBeforeClosingThePanel() {
        assertEquals(ShellBack.CloseInspector, shellBack(ShellDestination.Threads, true, false, true, false, hasBackParent = true, panelDocked = true, inspectorOpen = true))
        // Not docked (folded), the inspector is not on screen: Back is the thread's own.
        assertEquals(ShellBack.ToParent, shellBack(ShellDestination.Threads, true, false, true, false, hasBackParent = true, panelDocked = false, inspectorOpen = true))
        assertEquals(ShellBack.ClosePanel, shellBack(ShellDestination.Threads, true, false, true, false, hasBackParent = true, panelDocked = true, inspectorOpen = false))
        // The page or viewer inside and the secondary destinations keep their priority.
        assertNull(shellBack(ShellDestination.Threads, true, portBrowserOpen = true, twoPane = true, showThread = false, hasBackParent = false, panelDocked = true, inspectorOpen = true))
        assertEquals(ShellBack.ToThreads, shellBack(ShellDestination.Usage, true, false, true, false, hasBackParent = false, panelDocked = true, inspectorOpen = true))
    }

    private val icon = ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()

    @Test fun theHeaderShowsTheListFormOrTheSingleActionAndAtMostTwo() {
        val host = SidePanelHost()
        assertEquals(emptyList<PanelAction>(), host.shown)
        val single = PanelAction(icon, "Refresh", true) {}
        host.action = single
        assertEquals(listOf(single), host.shown)
        val a = PanelAction(icon, "Maximize", true) {}
        val b = PanelAction(icon, "Add to chat", true) {}
        val c = PanelAction(icon, "Third", true) {}
        host.actionList = listOf(a, b, c)
        assertEquals(listOf(a, b), host.shown)
        host.actionList = emptyList()
        assertEquals(listOf(single), host.shown)
    }

    @Test fun theInspectorPaneStateFollowsTheSnapshotAndItsError() {
        val row = ConversationMessage("m", "assistant", "text")
        fun inspector(ready: Boolean = true, error: InspectorError? = null, rows: Boolean = true, id: String = "child") =
            InspectorThread(id, SideThreadKind.Subagent, ready = ready, error = error, messages = if (rows) listOf(row) else emptyList())
        assertEquals(InspectorPaneMode.Loading, inspectorPaneMode(null, "child"))
        // A snapshot of another thread is no snapshot of this one (the stream was just retargeted).
        assertEquals(InspectorPaneMode.Loading, inspectorPaneMode(inspector(id = "other"), "child"))
        assertEquals(InspectorPaneMode.Loading, inspectorPaneMode(inspector(ready = false, rows = false), "child"))
        assertEquals(InspectorPaneMode.Rows, inspectorPaneMode(inspector(), "child"))
        // Cached rows repaint while the stream catches up.
        assertEquals(InspectorPaneMode.Rows, inspectorPaneMode(inspector(ready = false), "child"))
        assertEquals(InspectorPaneMode.Rows, inspectorPaneMode(inspector(rows = false), "child"))
        assertEquals(InspectorPaneMode.TooLarge, inspectorPaneMode(inspector(error = InspectorError.TooLarge), "child"))
        assertEquals(InspectorPaneMode.Gone, inspectorPaneMode(inspector(error = InspectorError.Gone), "child"))
        assertEquals(InspectorPaneMode.RefreshFailed, inspectorPaneMode(inspector(error = InspectorError.RefreshFailed, rows = false), "child"))
        assertEquals(InspectorPaneMode.Rows, inspectorPaneMode(inspector(error = InspectorError.RefreshFailed), "child"))
    }

    @Test fun theInspectedConversationNeverWritesAndLeavesTheSelectedThreadsOwnStateBehind() {
        val own = InspectorThread("child", SideThreadKind.SideChat, ready = true, messages = listOf(ConversationMessage("m", "assistant", "inspected")), activeRunId = "run")
        val selected = ClientState(selectedThreadId = "parent", canWrite = true, canWriteThreads = true, busy = true, canStop = true, canSteer = true, queueHeld = true,
            messages = listOf(ConversationMessage("p", "user", "parent row")), inspector = own)
        val derived = inspectorConversationState(selected, own, "child")
        assertEquals("child", derived.selectedThreadId)
        assertEquals(listOf("m"), derived.messages.map { it.id })
        assertEquals("run", derived.activeRunId)
        assertTrue(!derived.canWrite && !derived.canWriteThreads && !derived.busy && !derived.canStop && !derived.canSteer && !derived.queueHeld)
        // A snapshot of another thread contributes no rows.
        assertEquals(emptyList<ConversationMessage>(), inspectorConversationState(selected, own, "other").messages)
        assertEquals("Read-only here · maximize to reply", inspectorNotice(SideThreadKind.SideChat))
    }
}
