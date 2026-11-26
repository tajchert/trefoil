package com.tajchert.trefoil.ui.shell

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.ui.ShellActions
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.canLaunchThreads

/**
 * Top-level destinations of the shell. Exactly one is current; it is saved across configuration changes.
 * [Screens] and [Servers] belong to one environment and fall back to [Threads] when the environment changes.
 * [Machines] is only a destination while connected; without an environment Machines owns the window anyway.
 */
internal enum class ShellDestination { Threads, Screens, Servers, Usage, Machines, Issues;
    /** Destinations scoped to the environment they were opened for. */
    val environmentScoped: Boolean get() = this == Screens || this == Servers || this == Issues
}

/** The destination [ShellStart] opens; usage wins over machines, machines over dev servers, dev servers over screens, screens over issues (as the old flags did). */
internal fun ShellStart.destination(): ShellDestination = when {
    showUsage -> ShellDestination.Usage
    choosingEnvironment -> ShellDestination.Machines
    showPorts -> ShellDestination.Servers
    showScreens -> ShellDestination.Screens
    showIssues -> ShellDestination.Issues
    else -> ShellDestination.Threads
}

/** What the shell's own Back handler does; null leaves Back to the screen on top (or the system). */
internal enum class ShellBack { ToThreads, CloseInspector, ClosePanel, ToParent, ToList, ToIssueList }

/**
 * Shell Back priority below dialogs, menus, the drawer and in-screen steps (spec 2 "Back / Escape Priority Stack"):
 * a secondary destination returns to Threads (the port browser, and Screens and Servers away from the rail, handle their own Back and
 * call back into the shell), then a docked Thread tab leaves the inspector, then a docked side panel closes (after its content's own Back: page back, viewer to catalog), then a subagent child goes to
 * its parent, then a phone conversation returns to the list.
 */
internal fun shellBack(
    destination: ShellDestination, connected: Boolean, portBrowserOpen: Boolean, twoPane: Boolean, showThread: Boolean, hasBackParent: Boolean,
    panelDocked: Boolean = false, besideRail: Boolean = false, inspectorOpen: Boolean = false, issueDetailOpen: Boolean = false,
): ShellBack? = when {
    destination == ShellDestination.Usage -> ShellBack.ToThreads
    destination == ShellDestination.Machines -> if (connected) ShellBack.ToThreads else null
    // Issues: on a phone an open detail returns to the list first, then the list returns to Threads. Two panes keep the detail beside the
    // list, so Back leaves the destination. Disconnected, the cached list can still be left.
    destination == ShellDestination.Issues -> if (issueDetailOpen && !twoPane) ShellBack.ToIssueList else ShellBack.ToThreads
    !connected -> null
    portBrowserOpen -> null
    // Beside the rail Screens and Dev servers have no leave arrow and claim Back only for their own steps (an open session, an open
    // page), so the shell returns to Threads as it does for Usage.
    destination == ShellDestination.Screens || destination == ShellDestination.Servers -> if (besideRail) ShellBack.ToThreads else null
    // The docked Thread tab leaves the inspector first (back to the tab it was opened from, or the closed panel), not the whole panel.
    panelDocked && inspectorOpen -> ShellBack.CloseInspector
    panelDocked -> ShellBack.ClosePanel
    hasBackParent && (twoPane || showThread) -> ShellBack.ToParent
    showThread && !twoPane -> ShellBack.ToList
    else -> null
}

/**
 * Which navigation entries exist and which accept taps. An entry is shown exactly when its overflow-menu entry point is shown
 * ([ShellActions] non-null). Screens, Dev servers and New thread also need a live connection: while reconnecting or offline the
 * rail keeps its place but these stay disabled; Threads and Usage keep working on cached data.
 */
internal data class NavEntries(
    val screens: Boolean, val screensEnabled: Boolean,
    val servers: Boolean, val serversEnabled: Boolean,
    val usage: Boolean, val newThreadEnabled: Boolean, val connectEnabled: Boolean,
    val issues: Boolean = false, val issuesEnabled: Boolean = false,
)

internal fun navEntries(state: ClientState, actions: ShellActions): NavEntries {
    val live = state.environment != null && state.connection == ConnectionStatus.Connected && !state.busy
    return NavEntries(
        screens = actions.onScreens != null, screensEnabled = actions.onScreens != null && live,
        servers = actions.onDevServers != null, serversEnabled = actions.onDevServers != null && live,
        usage = actions.onUsage != null,
        issues = actions.onIssues != null, issuesEnabled = actions.onIssues != null && live,
        newThreadEnabled = live && canCreateThread(state),
        connectEnabled = !state.busy,
    )
}

/** Same rule as the thread list's New button. */
internal fun canCreateThread(state: ClientState): Boolean = state.canLaunchThreads && state.projects.isNotEmpty() && state.models.isNotEmpty()

/** Forwards of the current environment, for the Servers badge (client state only, no server call). */
internal fun forwardCount(state: ClientState): Int = state.environment?.id?.let { id -> state.portForwards.count { it.environmentId == id } } ?: 0

/** How the shell offers navigation for a window: a rail, a modal drawer (two panes without a rail), or the phone header. */
internal enum class NavChrome { Rail, Drawer, Header }

internal fun navChrome(rail: Boolean, twoPane: Boolean, compactHeight: Boolean, connected: Boolean): NavChrome = when {
    !connected -> NavChrome.Header
    rail -> NavChrome.Rail
    twoPane && !compactHeight -> NavChrome.Drawer
    else -> NavChrome.Header
}
