package com.tajchert.trefoil.ui.shell

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.ui.ShellActions
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation
import com.tajchert.trefoil.ui.adaptive.HingeAt
import com.tajchert.trefoil.ui.adaptive.WindowSpec
import com.tajchert.trefoil.ui.adaptive.resolvePanes
import com.tajchert.trefoil.ui.adaptive.shellPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDestinationTest {
    private val environment = SavedEnvironment("e", "Workstation", "https://e.invalid", "t")
    private val actions = ShellActions(onMachines = {}, onConnectMachine = {}, onScreens = {}, onDevServers = {}, onUsage = {}, onDisconnect = {})

    @Test fun startMapsToOneDestinationWithTheOldPriority() {
        assertEquals(ShellDestination.Threads, ShellStart().destination())
        assertEquals(ShellDestination.Usage, ShellStart(showUsage = true, choosingEnvironment = true).destination())
        assertEquals(ShellDestination.Machines, ShellStart(choosingEnvironment = true, showPorts = true).destination())
        assertEquals(ShellDestination.Servers, ShellStart(showPorts = true, showScreens = true).destination())
        assertEquals(ShellDestination.Screens, ShellStart(showScreens = true).destination())
        assertTrue(ShellDestination.Screens.environmentScoped && ShellDestination.Servers.environmentScoped)
        assertFalse(ShellDestination.Usage.environmentScoped || ShellDestination.Machines.environmentScoped || ShellDestination.Threads.environmentScoped)
    }

    @Test fun backReturnsFromSecondaryDestinationsBeforeParentAndList() {
        fun back(d: ShellDestination, connected: Boolean = true, browser: Boolean = false, twoPane: Boolean = false, thread: Boolean = true, parent: Boolean = true) =
            shellBack(d, connected, browser, twoPane, thread, parent)
        assertEquals(ShellBack.ToThreads, back(ShellDestination.Usage))
        assertEquals(ShellBack.ToThreads, back(ShellDestination.Usage, connected = false))
        assertEquals(ShellBack.ToThreads, back(ShellDestination.Machines))
        // Without an environment Machines owns the window: Back leaves the app.
        assertNull(back(ShellDestination.Machines, connected = false))
        assertNull(back(ShellDestination.Threads, connected = false))
        // Screens, Dev servers and the port browser step back themselves and call back into the shell.
        assertNull(back(ShellDestination.Screens))
        // Beside the rail Screens and Dev servers have no leave arrow, so the shell returns to Threads; an open session or page still steps back first.
        assertEquals(ShellBack.ToThreads, shellBack(ShellDestination.Screens, true, false, twoPane = true, showThread = false, hasBackParent = false, besideRail = true))
        assertNull(shellBack(ShellDestination.Screens, false, false, twoPane = true, showThread = false, hasBackParent = false, besideRail = true))
        assertEquals(ShellBack.ToThreads, shellBack(ShellDestination.Servers, true, false, twoPane = true, showThread = false, hasBackParent = false, besideRail = true))
        assertNull(shellBack(ShellDestination.Servers, true, portBrowserOpen = true, twoPane = true, showThread = false, hasBackParent = false, besideRail = true))
        assertNull(back(ShellDestination.Servers))
        assertNull(back(ShellDestination.Threads, browser = true))
        assertEquals(ShellBack.ToParent, back(ShellDestination.Threads))
        assertEquals(ShellBack.ToParent, back(ShellDestination.Threads, twoPane = true, thread = false))
        assertEquals(ShellBack.ToList, back(ShellDestination.Threads, parent = false))
        // Two panes have no conversation-to-list step.
        assertNull(back(ShellDestination.Threads, twoPane = true, parent = false))
        assertNull(back(ShellDestination.Threads, thread = false, parent = false))
    }

    @Test fun aDockedInspectorLeavesBeforeThePanelAndTheParentStep() {
        fun back(inspector: Boolean, docked: Boolean = true, parent: Boolean = true, destination: ShellDestination = ShellDestination.Threads) =
            shellBack(destination, true, false, twoPane = true, showThread = true, hasBackParent = parent, panelDocked = docked, inspectorOpen = inspector)
        assertEquals(ShellBack.CloseInspector, back(inspector = true))
        assertEquals(ShellBack.CloseInspector, back(inspector = true, parent = false))
        assertEquals(ShellBack.ClosePanel, back(inspector = false))
        // A folded window shows no inspector, so Back is the thread's own again.
        assertEquals(ShellBack.ToParent, back(inspector = true, docked = false))
        // Usage and the other secondary destinations still go first.
        assertEquals(ShellBack.ToThreads, back(inspector = true, destination = ShellDestination.Usage))
    }

    @Test fun railEntriesFollowTheMenuAndDisableLiveOnlyEntriesWhileReconnecting() {
        val live = ClientState(environment = environment, connection = ConnectionStatus.Connected)
        val entries = navEntries(live, actions)
        assertTrue(entries.screens && entries.screensEnabled && entries.servers && entries.serversEnabled && entries.usage && entries.connectEnabled)
        listOf(ConnectionStatus.Reconnecting, ConnectionStatus.Disconnected, ConnectionStatus.Error).forEach { status ->
            val stale = navEntries(live.copy(connection = status), actions)
            assertTrue(stale.screens && stale.servers && stale.usage)
            assertFalse(stale.screensEnabled || stale.serversEnabled || stale.newThreadEnabled)
        }
        val busy = navEntries(live.copy(busy = true), actions)
        assertFalse(busy.screensEnabled || busy.serversEnabled || busy.connectEnabled)
        // Hidden exactly when the overflow menu hides the entry.
        val hidden = navEntries(live, actions.copy(onScreens = null, onDevServers = null, onUsage = null))
        assertFalse(hidden.screens || hidden.servers || hidden.usage || hidden.screensEnabled || hidden.serversEnabled)
    }

    @Test fun navigationChromeAtTheSevenFormFactors() {
        fun chrome(w: Float, h: Float, connected: Boolean = true) = shellPolicy(WindowSpec(w, h)).let { navChrome(it.rail, it.twoPane, it.compactHeight, connected) }
        assertEquals(NavChrome.Header, chrome(400f, 800f))
        assertEquals(NavChrome.Header, chrome(880f, 400f))
        assertEquals(NavChrome.Drawer, chrome(673f, 841f))
        assertEquals(NavChrome.Rail, chrome(841f, 673f))
        assertEquals(NavChrome.Rail, chrome(800f, 1280f))
        assertEquals(NavChrome.Rail, chrome(1280f, 800f))
        assertEquals(NavChrome.Rail, chrome(1920f, 1080f))
        // Not connected: Machines owns the window at every size.
        listOf(673f to 841f, 1280f to 800f, 1920f to 1080f).forEach { (w, h) -> assertEquals(NavChrome.Header, chrome(w, h, connected = false)) }
    }

    @Test fun bookPostureLeavesTheHingeEmptyAndLocksTheList() {
        val fold = Fold(FoldOrientation.Vertical, 330f, 343f, separating = true, halfOpened = true)
        val policy = shellPolicy(WindowSpec(673f, 841f, fold))
        val panes = resolvePanes(policy, 999f, listCollapsed = true, sidePanelOpen = false)
        assertEquals(HingeAt.BeforeConversation, panes.hingeAt)
        assertEquals(330f, panes.rail + panes.list)
        assertEquals(13f, panes.hinge)
        assertEquals(673f - 343f, panes.conversation)
        assertFalse(policy.listCollapsible)
        assertEquals(NavChrome.Drawer, navChrome(policy.rail, policy.twoPane, policy.compactHeight, connected = true))
    }

    @Test fun forwardCountUsesOnlyTheCurrentEnvironment() {
        val forwards = listOf(com.tajchert.trefoil.core.model.PortForward("e", 5173), com.tajchert.trefoil.core.model.PortForward("e", 3000),
            com.tajchert.trefoil.core.model.PortForward("other", 8080))
        assertEquals(2, forwardCount(ClientState(environment = environment, portForwards = forwards)))
        assertEquals(0, forwardCount(ClientState(portForwards = forwards)))
    }
}
