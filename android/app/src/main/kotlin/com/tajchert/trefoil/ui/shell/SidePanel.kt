package com.tajchert.trefoil.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.SideThreadKind
import com.tajchert.trefoil.ui.SubagentRoster
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.ThreadAgentsSheetContent
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.ShellPolicy
import com.tajchert.trefoil.ui.adaptive.SidePanelMode
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.agentRows
import kotlinx.coroutines.delay
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/**
 * Tabs of the side panel beside the conversation, in header order. Saved by name only. [Thread] is the read-only inspector of a subagent
 * or side chat; its pill is labelled by the inspected thread's kind ([threadTabLabel]) and the tab exists only while a target is set.
 * [Issue] exists while an issue opened from a thread's chip is held for the panel.
 */
internal enum class SidePanelTab(val label: String) { Preview("Preview"), Screens("Screens"), Agents("Agents"), Changes("Changes"), PullRequest("PR"), Thread("Thread"), Issue("Issue") }

/** The Thread tab's pill label: "Side chat" for a side chat, "Subagent" for any subagent thread. */
internal fun threadTabLabel(kind: SideThreadKind?): String = if (kind == SideThreadKind.SideChat) "Side chat" else "Subagent"

/**
 * Which tabs exist: Preview with dev servers for this machine, Screens with the rail's Screens entry, Agents with a roster, Changes with checkpoints,
 * PR with a linked pull request, Thread while an inspector target is set, and Issue while an issue opened from a thread's chip is held for the panel.
 */
internal fun sidePanelTabs(
    preview: Boolean, screens: Boolean, agents: Boolean, changes: Boolean = false, pullRequest: Boolean = false, thread: Boolean = false, issue: Boolean = false,
): List<SidePanelTab> = buildList {
    if (preview) add(SidePanelTab.Preview)
    if (screens) add(SidePanelTab.Screens)
    if (agents) add(SidePanelTab.Agents)
    if (changes) add(SidePanelTab.Changes)
    if (pullRequest) add(SidePanelTab.PullRequest)
    if (thread) add(SidePanelTab.Thread)
    if (issue) add(SidePanelTab.Issue)
}

/**
 * The tab actually shown for the [saved] one: itself while it exists, otherwise the first available tab that accepts taps, or closed
 * (null) when there is none. A disabled tab that is already open stays open (its screen shows why it cannot do anything).
 */
internal fun resolveSidePanelTab(saved: SidePanelTab?, available: List<SidePanelTab>, disabled: Set<SidePanelTab> = emptySet()): SidePanelTab? = when {
    saved == null -> null
    saved in available -> saved
    else -> available.firstOrNull { it !in disabled }
}

/** The tab the header toggle opens: the last used one while it exists and accepts taps, otherwise the first available one. */
internal fun sidePanelOpenTarget(last: SidePanelTab?, available: List<SidePanelTab>, disabled: Set<SidePanelTab> = emptySet()): SidePanelTab? =
    last?.takeIf { it in available && it !in disabled } ?: available.firstOrNull { it !in disabled }

/**
 * The side panel mode the shell actually uses: the policy's, except that compact height (where the keyboard layout lives) never docks a
 * panel, so the panel and the keyboard's temporary layout never meet.
 */
internal fun effectiveSidePanelMode(policy: ShellPolicy): SidePanelMode =
    if (policy.compactHeight || !policy.twoPane) SidePanelMode.FullScreen else policy.sidePanelMode

/**
 * How an open tab is presented: [docked] beside the conversation, or (window too narrow) mapped onto a destination, the agents sheet
 * or, for Changes, a full-screen route over the conversation ([changesRoute]), or, for the pull request, its sheet ([prSheet]).
 */
internal data class SidePanelPresentation(
    val destination: ShellDestination, val agentsSheet: Boolean, val docked: Boolean, val changesRoute: Boolean = false, val prSheet: Boolean = false,
)

/**
 * Narrow windows ([SidePanelMode.FullScreen]) have no docked panel, so an open tab is shown the way the narrow window shows that surface:
 * Preview as the Servers destination, Screens as the Screens destination, Agents as the agents sheet, Changes as a full-screen route, the pull request as a sheet. The saved tab is kept, so growing
 * back to a dockable width docks it again. Wider windows dock the tab beside the conversation of the Threads destination.
 * A screen viewer in full screen always takes the whole window (as the Screens destination); leaving full screen docks it again.
 */
internal fun sidePanelPresentation(destination: ShellDestination, tab: SidePanelTab?, mode: SidePanelMode, screenFullscreen: Boolean = false): SidePanelPresentation = when {
    tab == null || destination != ShellDestination.Threads -> SidePanelPresentation(destination, agentsSheet = false, docked = false)
    tab == SidePanelTab.Screens && screenFullscreen -> SidePanelPresentation(ShellDestination.Screens, agentsSheet = false, docked = false)
    mode != SidePanelMode.FullScreen -> SidePanelPresentation(destination, agentsSheet = false, docked = true)
    else -> when (tab) {
        SidePanelTab.Preview -> SidePanelPresentation(ShellDestination.Servers, agentsSheet = false, docked = false)
        SidePanelTab.Screens -> SidePanelPresentation(ShellDestination.Screens, agentsSheet = false, docked = false)
        SidePanelTab.Agents -> SidePanelPresentation(ShellDestination.Threads, agentsSheet = true, docked = false)
        SidePanelTab.Changes -> SidePanelPresentation(ShellDestination.Threads, agentsSheet = false, docked = false, changesRoute = true)
        SidePanelTab.PullRequest -> SidePanelPresentation(ShellDestination.Threads, agentsSheet = false, docked = false, prSheet = true)
        // The inspector has no narrow form: nothing docks and no sheet opens. The target survives (it lives in the shell), so docking
        // again reopens the inspected thread; the child itself is reached through select() from the Agents sheet.
        SidePanelTab.Thread -> SidePanelPresentation(ShellDestination.Threads, agentsSheet = false, docked = false)
        SidePanelTab.Issue -> SidePanelPresentation(ShellDestination.Issues, agentsSheet = false, docked = false)
    }
}

/**
 * Panel chrome: a 64dp header (the conversation header's height) with one pill per tab, the selected tab's [actions] (at most
 * [SidePanelHost.MAX_ACTIONS], left to right) and Close, then the selected tab's content filling the rest. [threadLabel] names the Thread tab.
 */
@Composable
internal fun SidePanel(
    tabs: List<SidePanelTab>, selected: SidePanelTab, disabled: Set<SidePanelTab>, onSelect: (SidePanelTab) -> Unit, onClose: () -> Unit,
    tabsFocus: FocusRequester, modifier: Modifier = Modifier, actions: List<PanelAction> = emptyList(), threadLabel: String = SidePanelTab.Thread.label,
    content: @Composable (SidePanelTab) -> Unit,
) {
    val c = Tf.colors
    fun labelOf(tab: SidePanelTab) = if (tab == SidePanelTab.Thread) threadLabel else tab.label
    Column(modifier.fillMaxSize().background(c.bg).paneGroup("${labelOf(selected)} side panel", PaneOrder.SidePanel)) {
        Row(Modifier.fillMaxWidth().height(64.dp).background(c.s1).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).focusRequester(tabsFocus).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab -> SidePanelTabPill(labelOf(tab), tab == selected, tab !in disabled) { onSelect(tab) } }
            }
            actions.take(SidePanelHost.MAX_ACTIONS).forEach { action ->
                TfIconButton(action.icon, action.description, action.onClick, Modifier.size(48.dp), enabled = action.enabled, iconSize = 22.dp, tint = if (action.enabled) c.text else c.muted)
                Spacer(Modifier.width(4.dp))
            }
            TfIconButton(TfIcons.Close, "Close side panel", onClose, Modifier.size(48.dp), iconSize = 22.dp)
        }
        HorizontalDivider(color = c.line)
        Box(Modifier.weight(1f).fillMaxWidth()) { content(selected) }
    }
}

@Composable
private fun SidePanelTabPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    Box(Modifier.heightIn(min = 48.dp).focusRing().handPointer(enabled).selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) c.s3 else c.s1).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, style = TrefoilType.meta.copy(color = when { !enabled -> c.muted.copy(alpha = .5f); selected -> c.text; else -> c.muted }), maxLines = 1)
        }
    }
}

/**
 * Header control that opens and closes the docked side panel (48dp, tooltip and description follow the state). While the panel is open the
 * icon is drawn active: accent on a 36dp soft-accent tile inside the 48dp target.
 */
@Composable
internal fun SidePanelToggle(open: Boolean, onClick: () -> Unit, focus: FocusRequester) {
    val c = Tf.colors
    val tile = c.accentSoft
    val active = if (open) Modifier.drawBehind {
        val side = 36.dp.toPx()
        drawRoundRect(tile, Offset((size.width - side) / 2f, (size.height - side) / 2f), Size(side, side), CornerRadius(10.dp.toPx()))
    } else Modifier
    TfIconButton(TfIcons.PanelRight, if (open) "Close side panel" else "Open side panel", onClick, Modifier.size(48.dp).focusRequester(focus).then(active),
        tint = if (open) c.accent else Color.Unspecified, iconSize = 22.dp, tooltip = tooltipWithShortcut(if (open) "Close side panel" else "Open side panel", ShortcutId.ToggleSidePanel))
}

/**
 * The Agents tab: the rows of the agents sheet, with the sheet's rules. Stop needs [canWrite] (the steady write flag the sheet gets) and
 * each agent can be asked to stop once while the panel shows it (plain remember, as in the sheet).
 */
@Composable
internal fun SidePanelAgents(
    roster: SubagentRoster, providers: List<ProviderInstance>, models: List<ModelOption>, canWrite: Boolean,
    onOpen: (String) -> Unit, onStop: (String) -> Unit, modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(roster.liveCount > 0) {
        now = System.currentTimeMillis()
        while (roster.liveCount > 0) { delay(1000); now = System.currentTimeMillis() }
    }
    var requested by remember { mutableStateOf(emptySet<String>()) }
    val rows = remember(roster, providers, models, now, canWrite, requested) {
        agentRows(roster, providers, models, now, canWrite).map { if (it.id in requested) it.copy(canStop = false) else it }
    }
    // The tab names the list already: no second "Agents" heading, and the first row starts 8dp under the tab bar.
    ThreadAgentsSheetContent(rows, onOpen, { id -> requested = requested + id; onStop(id) }, modifier.padding(top = 8.dp), showHeading = false)
}
