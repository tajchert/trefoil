package com.tajchert.trefoil.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Issues chip is last in the header's chip order and is demoted to "Issues (N)" in the thread menu before the others. */
class HeaderBudgetIssuesTest {
    @Test fun noLinkedIssuesLeavesTheBudgetUnchanged() {
        assertEquals(headerBudget(1280f, 840f, 1f, true, true, true), headerBudget(1280f, 840f, 1f, true, true, true, issues = false))
        assertFalse(headerBudget(1280f, 840f, 1f, true, true, true).issuesChip)
        assertFalse(headerBudget(1280f, 336f, 1f, true, true, true).menuIssues)
    }

    @Test fun issuesChipFollowsThePaneWidth() {
        data class Row(val window: Float, val pane: Float, val font: Float, val agents: Boolean, val servers: Boolean, val expected: HeaderBudget)
        val rows = listOf(
            // Phone-width windows keep two chips: Issues is the third, so it moves to the menu.
            Row(400f, 400f, 1f, true, true, HeaderBudget(true, true, false, menuIssues = true)),
            Row(400f, 400f, 1f, true, false, HeaderBudget(true, false, false, issuesChip = true)),
            Row(400f, 400f, 1f, false, false, HeaderBudget(false, false, false, issuesChip = true)),
            Row(400f, 400f, 2f, false, false, HeaderBudget(false, false, false, issuesChip = true)),
            // Below 460dp everything leads the menu.
            Row(673f, 336f, 1f, true, true, HeaderBudget(false, false, true, menuAgents = true, menuServers = true, menuIssues = true)),
            Row(673f, 336f, 1f, false, false, HeaderBudget(false, false, true, menuIssues = true)),
            // 460-599dp: one chip, Agents first, then Servers, then Issues.
            Row(1000f, 520f, 1f, true, true, HeaderBudget(true, false, true, menuServers = true, menuIssues = true)),
            Row(1000f, 520f, 1f, false, true, HeaderBudget(false, true, true, menuIssues = true)),
            Row(1000f, 520f, 1f, false, false, HeaderBudget(false, false, true, issuesChip = true)),
            // 600-719dp: two chips.
            Row(1280f, 600f, 1f, true, true, HeaderBudget(true, true, true, menuIssues = true)),
            Row(1280f, 719f, 1f, true, false, HeaderBudget(true, false, true, issuesChip = true)),
            // From 720dp all three fit.
            Row(1280f, 720f, 1f, true, true, HeaderBudget(true, true, true, issuesChip = true)),
            Row(1920f, 940f, 1f, true, true, HeaderBudget(true, true, true, issuesChip = true)),
            // Large text demotes earlier.
            Row(1280f, 679f, 1.5f, false, false, HeaderBudget(false, false, true, menuIssues = true)),
            Row(1280f, 839f, 2f, true, false, HeaderBudget(false, false, true, menuAgents = true, menuIssues = true)),
            Row(1920f, 840f, 2f, true, true, HeaderBudget(true, true, true, issuesChip = true)),
        )
        rows.forEach { r -> assertEquals(r.toString(), r.expected, headerBudget(r.window, r.pane, r.font, r.agents, r.servers, true, issues = true)) }
    }

    @Test fun anOpenPanelDropsTheToggleButKeepsTheIssuesRules() {
        val closed = headerBudget(841f, 332f, 1f, true, true, true, issues = true)
        assertEquals(closed.copy(toggle = false), headerBudget(841f, 332f, 1f, true, true, true, panelOpen = true, issues = true))
    }

    @Test fun menuLabelCountsTheLinks() {
        assertEquals("Issues (1)", issuesMenuLabel(1))
        assertEquals("Issues (3)", issuesMenuLabel(3))
    }
}
