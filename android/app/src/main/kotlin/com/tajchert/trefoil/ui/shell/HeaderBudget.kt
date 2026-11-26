package com.tajchert.trefoil.ui.shell

import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec

/**
 * Which trailing conversation-header controls stay visible; the rest move to the top of the thread menu.
 * [toggle] is the side-panel toggle (only where a panel can dock); [menuAgents] / [menuServers] / [menuIssues] are the demoted chips.
 * Chips keep this priority: Agents, then Dev servers, then Issues.
 */
internal data class HeaderBudget(
    val agentsChip: Boolean, val serversChip: Boolean, val toggle: Boolean,
    val menuAgents: Boolean = false, val menuServers: Boolean = false,
    val issuesChip: Boolean = false, val menuIssues: Boolean = false,
)

/** Pane widths of spec 3.2: below the first only the toggle stays, below the second at most one chip, below the third at most two. */
internal const val HeaderChipsBelow = 460f
internal const val HeaderOneChipBelow = 600f
internal const val HeaderTwoChipsBelow = 720f

/** Windows narrower than 600dp keep two chips beside the title; a third one (always the Issues chip) leads the menu instead. */
internal const val HeaderCompactChipSlots = 2

/** Below this conversation-pane width an open panel's toggle is left out of the header. */
internal const val HeaderToggleOpenMinPane = 380f

/**
 * Spec 3.2 header table, decided from the conversation pane width (never the window). Windows narrower than 600dp keep today's header
 * (up to two chips that exist, no toggle). Large text demotes the chips earlier: from font scale 1.5 below 680dp, from 2.0 below 840dp.
 * The Agents chip wins the single slot of a 460-599dp pane. The Issues chip ([issues], the selected thread has linked issues) is last in line.
 */
internal fun headerBudget(
    windowWidth: Float, paneWidth: Float, fontScale: Float, agents: Boolean, servers: Boolean, dockable: Boolean, panelOpen: Boolean = false,
    issues: Boolean = false,
): HeaderBudget {
    if (windowWidth < AdaptiveSpec.MediumMinWidth) return chipSlots(HeaderCompactChipSlots, agents, servers, issues, toggle = false)
    // With the panel open the toggle would sit in a pane too narrow for it; the panel's own Close button is the way out.
    val toggle = dockable && !(panelOpen && paneWidth < HeaderToggleOpenMinPane)
    val chipsDemoted = paneWidth < HeaderChipsBelow || (fontScale >= 2f && paneWidth < AdaptiveSpec.ExpandedMinWidth) || (fontScale >= 1.5f && paneWidth < 680f)
    val slots = when {
        chipsDemoted -> 0
        paneWidth < HeaderOneChipBelow -> 1
        paneWidth < HeaderTwoChipsBelow -> 2
        else -> 3
    }
    return chipSlots(slots, agents, servers, issues, toggle)
}

/** The first [slots] chips that exist stay in the header, in priority order; the rest are demoted to the menu. */
private fun chipSlots(slots: Int, agents: Boolean, servers: Boolean, issues: Boolean, toggle: Boolean): HeaderBudget {
    var left = slots
    fun take(exists: Boolean): Boolean = exists && left > 0 && run { left--; true }
    val a = take(agents)
    val s = take(servers)
    val i = take(issues)
    return HeaderBudget(agentsChip = a, serversChip = s, toggle = toggle, menuAgents = agents && !a, menuServers = servers && !s, issuesChip = i, menuIssues = issues && !i)
}

/** Menu label of the demoted dev-servers chip: "Dev servers (:5173)" for one port, "Dev servers (3)" for several. */
internal fun serversMenuLabel(ports: List<Int>): String = if (ports.size == 1) "Dev servers (:${ports[0]})" else "Dev servers (${ports.size})"

/** Menu label of the demoted issues chip: "Issues (2)". */
internal fun issuesMenuLabel(count: Int): String = "Issues ($count)"
