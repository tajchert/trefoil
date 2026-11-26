package com.tajchert.trefoil.ui.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

/** Which pane holds keyboard focus; the shell only needs to tell the list from the conversation. */
internal enum class PaneFocus { None, List, Conversation }

/**
 * A temporary list layout that wins over the saved list state and is never written back: the keyboard leaving almost no room at compact
 * height, or a docked side panel taking the list's place. The two never meet (the panel never docks at compact height).
 */
internal enum class TransientList {
    None,
    /** A side panel replaces the list while it is open ([com.tajchert.trefoil.ui.adaptive.SidePanelMode.ReplacesList]); "Show thread list" closes the panel. */
    PanelOpen,
    /** Focus is in the conversation: the list steps aside so the conversation gets the full width. */
    HideList,
    /** Focus is in the list (its search): the list gets the full width instead. */
    ListOnly,
}

/** Less than this many dp above the keyboard counts as "almost no room". */
internal const val ImeCompactRoomDp = 240f

/**
 * Compact height with the keyboard open: two flat panes are too cramped to type in, so the pane that has focus takes the full width.
 * Pure: depends only on the window and where focus is. A hinged window keeps its two regions (nothing may cross the hinge).
 */
internal fun transientList(
    twoPane: Boolean, split: Boolean, compactHeight: Boolean, imeVisible: Boolean, roomAboveImeDp: Float, focus: PaneFocus,
    panelReplacesList: Boolean = false,
): TransientList {
    // A hinged window lays the panel out in its own region (resolvePanes), so only a flat window needs the list stepped aside.
    if (twoPane && !split && panelReplacesList) return TransientList.PanelOpen
    if (!twoPane || split || !compactHeight || !imeVisible || roomAboveImeDp >= ImeCompactRoomDp) return TransientList.None
    return when (focus) {
        PaneFocus.Conversation -> TransientList.HideList
        PaneFocus.List -> TransientList.ListOnly
        PaneFocus.None -> TransientList.None
    }
}

/** Test seam for the keyboard height in dp; null reads the real IME inset. */
internal val LocalImeHeightOverride = staticCompositionLocalOf<Float?> { null }

/** One pane in the TalkBack reading order: the pane is announced by [title] and read as a unit, panes in [index] order. */
internal fun Modifier.paneGroup(title: String, index: Float): Modifier = semantics {
    paneTitle = title
    isTraversalGroup = true
    traversalIndex = index
}

/** TalkBack order of the shell's panes: navigation, then the thread list, then the detail, then the side panel. */
internal object PaneOrder {
    const val Navigation = 0f
    const val List = 1f
    const val Detail = 2f
    const val SidePanel = 3f
}

/** Pane title of the detail: the conversation when one is open, otherwise the destination's name. */
internal fun detailPaneTitle(destination: ShellDestination, conversationTitle: String?): String =
    if (destination == ShellDestination.Threads && conversationTitle != null) "$conversationTitle, conversation" else when (destination) {
        ShellDestination.Threads -> "Threads"
        ShellDestination.Screens -> "Screens"
        ShellDestination.Servers -> "Servers"
        ShellDestination.Usage -> "Usage"
        ShellDestination.Machines -> "Machines"
        ShellDestination.Issues -> "Issues"
    }
