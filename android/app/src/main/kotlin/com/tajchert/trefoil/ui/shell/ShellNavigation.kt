package com.tajchert.trefoil.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.ui.InterFamily
import com.tajchert.trefoil.ui.MachineStatusIndicator
import com.tajchert.trefoil.ui.ShellActions
import com.tajchert.trefoil.ui.ShellMenuItems
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilMark
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.TfTooltip
import com.tajchert.trefoil.ui.backDescription
import com.tajchert.trefoil.ui.color
import com.tajchert.trefoil.ui.limitChipDescription
import com.tajchert.trefoil.ui.limitSeverity
import com.tajchert.trefoil.ui.machineStatus
import com.tajchert.trefoil.ui.tightestLimitPercent
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/** Everything the rail and the drawer show; both render the same destinations and machine actions. */
internal class ShellNavModel(
    val state: ClientState, val saved: List<SavedEnvironment>, val probes: Map<String, MachineProbe>, val actions: ShellActions,
    val destination: ShellDestination, val portBrowserOpen: Boolean, val needsYou: Int,
    val onThreads: () -> Unit, val onNewThread: () -> Unit, val onSelectMachine: (SavedEnvironment) -> Unit,
    /** Opens the keyboard shortcut cheat sheet; null leaves the overflow-menu entry out. */
    val onShortcuts: (() -> Unit)? = null,
) {
    val entries = navEntries(state, actions)
    val forwards = forwardCount(state)
    val limitPercent = tightestLimitPercent(state.providers)
    fun selected(d: ShellDestination) = when (d) {
        ShellDestination.Threads -> destination == d && !portBrowserOpen
        ShellDestination.Servers -> destination == d || (portBrowserOpen && destination != ShellDestination.Usage && destination != ShellDestination.Machines && destination != ShellDestination.Issues)
        else -> destination == d
    }
}

/** Rail insets: system bars and a display cutout on the rail side, top and bottom; never the IME. */
internal val RailInsets @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)

private fun badgeText(count: Int) = if (count > 99) "99+" else count.toString()

/** The text size a badge (11sp) stops at: 12dp of text, so a large font cannot swell the badge over the 22dp icon it sits on. */
internal val BadgeTextCap = 12.dp

/** Badge label that scales with the font up to [BadgeTextCap] (line height up to 16dp); at font scale 1.0 it is the badge's own label style. */
@Composable
internal fun BadgeText(text: String) {
    val base = MaterialTheme.typography.labelSmall
    val (size, line) = with(LocalDensity.current) { minOf(base.fontSize.toDp(), BadgeTextCap).toSp() to minOf(base.lineHeight.toDp(), BadgeTextCap + 4.dp).toSp() }
    Text(text, fontSize = size, lineHeight = line, maxLines = 1, softWrap = false)
}

/** 80dp navigation rail: brand, New thread, Threads / Screens / Servers / Usage, machine switcher and the app menu. */
@Composable
internal fun ShellNavigationRail(model: ShellNavModel, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val entries = model.entries
    Box(modifier.width(AdaptiveSpec.RailWidth.dp).fillMaxHeight().background(c.rail).paneGroup("Navigation", PaneOrder.Navigation)) {
        NavigationRail(
            modifier = Modifier.fillMaxSize(), containerColor = c.rail, contentColor = c.text, windowInsets = RailInsets,
            header = {
                TfTooltip("Threads") {
                    Box(Modifier.size(48.dp, 40.dp).clip(RoundedCornerShape(12.dp)).focusRing().handPointer().clickable(role = Role.Button, onClickLabel = "Show threads", onClick = model.onThreads)
                        .semantics { contentDescription = "Trefoil, show threads" }, contentAlignment = Alignment.Center) {
                        TrefoilMark(Modifier.size(28.dp), color = c.accent)
                    }
                }
                NewThreadRailButton(entries.newThreadEnabled, model.onNewThread, Modifier.padding(top = 8.dp, bottom = 8.dp))
            },
        ) {
            val colors = railItemColors()
            // The destinations scroll when the window (or a large font) leaves too little height; the header above and the
            // machine switcher below stay pinned, and the section takes whatever room is left.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NavigationRailItem(model.selected(ShellDestination.Threads), model.onThreads, colors = colors,
                    icon = { CountBadge(model.needsYou, c.amber) { Icon(TfIcons.Threads, null, Modifier.size(22.dp)) } },
                    label = { RailLabel("Threads") },
                    modifier = Modifier.semantics { if (model.needsYou > 0) stateDescription = "${model.needsYou} need you" })
                if (entries.screens) NavigationRailItem(model.selected(ShellDestination.Screens), { model.actions.onScreens?.invoke() }, enabled = entries.screensEnabled,
                    colors = colors, icon = { Icon(TfIcons.Screens, null, Modifier.size(22.dp)) }, label = { RailLabel("Screens") })
                if (entries.servers) NavigationRailItem(model.selected(ShellDestination.Servers), { model.actions.onDevServers?.invoke() }, enabled = entries.serversEnabled,
                    colors = colors, icon = { CountBadge(model.forwards, c.accent) { Icon(TfIcons.Globe, null, Modifier.size(22.dp)) } },
                    label = { RailLabel("Servers") },
                    modifier = Modifier.semantics { if (model.forwards > 0) stateDescription = "${model.forwards} forwarded" })
                if (entries.issues) NavigationRailItem(model.selected(ShellDestination.Issues), { model.actions.onIssues?.invoke() }, enabled = entries.issuesEnabled,
                    colors = colors, icon = { Icon(TfIcons.Issues, null, Modifier.size(22.dp)) }, label = { RailLabel("Issues") })
                if (entries.usage) NavigationRailItem(model.selected(ShellDestination.Usage), { model.actions.onUsage?.invoke() }, colors = colors,
                    icon = { UsageIcon(model.limitPercent, model.selected(ShellDestination.Usage)) }, label = { RailLabel("Usage") },
                    modifier = Modifier.semantics { stateDescription = limitChipDescription(model.limitPercent) })
            }
            HorizontalDivider(Modifier.width(32.dp).padding(bottom = 4.dp), color = c.line)
            MachineSwitcher(model)
            AppMenuButton(model)
        }
        VerticalDivider(Modifier.align(Alignment.CenterEnd), color = c.line)
    }
}

@Composable
private fun railItemColors(): NavigationRailItemColors {
    val c = Tf.colors
    return NavigationRailItemDefaults.colors(
        selectedIconColor = c.text, selectedTextColor = c.text, indicatorColor = c.accentSoft,
        unselectedIconColor = c.muted, unselectedTextColor = c.muted,
        disabledIconColor = c.muted.copy(alpha = .38f), disabledTextColor = c.muted.copy(alpha = .38f),
    )
}

@Composable
private fun RailLabel(text: String) {
    // The 80dp rail cannot widen with the font, so the scaled label stops at 14dp: "Threads", "Screens" and "Servers" stay whole at
    // font scale 2 instead of ellipsizing. Up to about 1.27x the label scales as usual.
    val density = LocalDensity.current
    val size = with(density) { minOf(11.sp.toDp(), 14.dp).toSp() }
    val line = with(density) { minOf(14.sp.toDp(), 20.dp).toSp() }
    Text(text, Modifier.widthIn(max = 72.dp), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = size, lineHeight = line, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CountBadge(count: Int, color: Color, content: @Composable () -> Unit) {
    val c = Tf.colors
    BadgedBox(badge = { if (count > 0) Badge(Modifier.clearAndSetSemantics {}, containerColor = color, contentColor = c.onAccent) { BadgeText(badgeText(count)) } }) { content() }
}

/** 56dp New thread button; disabled (muted) while threads cannot be created, for example while reconnecting. */
@Composable
private fun NewThreadRailButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    TfTooltip(tooltipWithShortcut("New thread", ShortcutId.NewThread)) {
        Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = if (enabled) c.accent else c.s2,
            contentColor = if (enabled) c.onAccent else c.muted, modifier = modifier.size(56.dp).focusRing(RoundedCornerShape(16.dp)).handPointer(enabled).semantics { contentDescription = "New thread" }) {
            Box(contentAlignment = Alignment.Center) { Icon(TfIcons.Plus, null, Modifier.size(24.dp)) }
        }
    }
}

/** Usage pill content: gauge icon without limit data, otherwise a ring in the severity colour plus "NN%" (ring only at large font scales). */
@Composable
private fun UsageIcon(percent: Int?, selected: Boolean) {
    val c = Tf.colors
    if (percent == null) { Icon(TfIcons.Gauge, null, Modifier.size(22.dp)); return }
    val color = limitSeverity(percent).color(c)
    val large = LocalDensity.current.fontScale >= 1.3f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        UsageRing(percent, color, c.line, if (large) 22.dp else 16.dp)
        if (!large) Text("$percent%", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp,
            color = if (selected) c.text else color, maxLines = 1)
    }
}

@Composable
private fun UsageRing(percent: Int, color: Color, track: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arc = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
        if (percent > 0) drawArc(color, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arc, style = stroke)
    }
}

/** Current machine (avatar with status, full name on up to two lines); opens the 280dp machine menu. */
@Composable
private fun MachineSwitcher(model: ShellNavModel) {
    val environment = model.state.environment ?: return
    val c = Tf.colors
    val status = machineStatus(model.state)
    val active = model.destination == ShellDestination.Machines
    val fontScale = LocalDensity.current.fontScale
    var open by remember { mutableStateOf(false) }
    // Ctrl+Shift+M opens this menu (the rail is the only place it lives); not again while it is open.
    RegisterShortcut(ShortcutId.MachineSwitcher, enabled = !open) { open = true }
    Box {
        TfTooltip(tooltipWithShortcut("${environment.name} · ${status.label}", ShortcutId.MachineSwitcher)) {
            Column(Modifier.width(72.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                .focusRing().handPointer().clickable(role = Role.Button, onClickLabel = "Switch machine") { open = true }
                .semantics(mergeDescendants = true) { contentDescription = environment.name; stateDescription = status.label }
                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp)) {
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.align(Alignment.Center).size(40.dp).clip(shape).background(if (active) c.accentSoft else c.s2)
                        .then(if (active) Modifier.border(1.5.dp, c.accent, shape) else Modifier), contentAlignment = Alignment.Center) {
                        Icon(if (environment.ssh != null) TfIcons.Server else TfIcons.Desktop, null, Modifier.size(20.dp), tint = c.text)
                    }
                    Box(Modifier.align(Alignment.BottomEnd).size(14.dp).background(c.rail, CircleShape), contentAlignment = Alignment.Center) {
                        MachineStatusIndicator(status, size = 8.dp)
                    }
                }
                if (fontScale < 2f) Text(environment.name.trim(), Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp, lineHeight = 13.sp, color = c.text, textAlign = TextAlign.Center, maxLines = if (fontScale >= 1.5f) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(open, { open = false }, Modifier.width(280.dp), offset = DpOffset(76.dp, 0.dp)) {
            MachineMenuItems(model) { open = false }
        }
    }
}

/** Saved machines (status, name, check on the current one), then Manage machines and Connect machine. Shared by the rail menu and the drawer. */
@Composable
internal fun ColumnScope.MachineMenuItems(model: ShellNavModel, close: () -> Unit) {
    val state = model.state
    val c = Tf.colors
    val active = state.environment
    val machines = if (active != null && model.saved.none { it.id == active.id }) listOf(active) + model.saved else model.saved
    machines.forEach { environment ->
        val current = environment.id == active?.id
        val status = if (current) machineStatus(state) else machineStatus(environment, state, model.probes)
        DropdownMenuItem(
            text = { Text(environment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { MachineStatusIndicator(status) } },
            trailingIcon = if (current) ({ Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.accent) }) else null,
            enabled = !state.busy || current,
            modifier = Modifier.semantics { stateDescription = if (current) "${status.label}, current machine" else status.label },
            onClick = { close(); if (!current) model.onSelectMachine(environment) },
        )
    }
    if (machines.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
    DropdownMenuItem(text = { Text("Manage machines & routes…") }, leadingIcon = { Icon(TfIcons.Desktop, null, Modifier.size(20.dp)) },
        onClick = { close(); model.actions.onMachines() })
    DropdownMenuItem(text = { Text("Connect machine") }, enabled = model.entries.connectEnabled, leadingIcon = { Icon(TfIcons.Plus, null, Modifier.size(20.dp)) },
        onClick = { close(); model.actions.onConnectMachine() })
}

@Composable
private fun AppMenuButton(model: ShellNavModel) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 4.dp)) {
        TfIconButton(TfIcons.More, "More", { open = true }, iconSize = 22.dp, tint = Tf.colors.muted)
        DropdownMenu(open, { open = false }) {
            ShellMenuItems(model.state, model.actions) { open = false }
            model.onShortcuts?.let { show ->
                DropdownMenuItem(text = { Text("Keyboard shortcuts") }, trailingIcon = { shortcutHint(ShortcutId.ShowShortcuts)?.let { Text(it, style = TrefoilType.meta, color = Tf.colors.muted) } },
                    onClick = { open = false; show() })
            }
        }
    }
}

/** 300dp modal drawer for two panes without a rail (600-779dp): the rail's destinations and machine actions. */
@Composable
internal fun ShellDrawerSheet(model: ShellNavModel, drawerState: DrawerState, close: () -> Unit) {
    val c = Tf.colors
    val entries = model.entries
    ModalDrawerSheet(drawerState, Modifier.width(300.dp).semantics { paneTitle = "Navigation" }, drawerContainerColor = c.sheet, drawerContentColor = c.text) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrefoilMark(Modifier.size(28.dp), color = c.accent)
                Text("Trefoil", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.semantics { heading() })
            }
            val colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = c.accentSoft, unselectedContainerColor = Color.Transparent,
                selectedIconColor = c.text, unselectedIconColor = c.muted, selectedTextColor = c.text, unselectedTextColor = c.text,
                selectedBadgeColor = c.text, unselectedBadgeColor = c.muted,
            )
            fun go(action: (() -> Unit)?): () -> Unit = { close(); action?.invoke() }
            DrawerItem("Threads", TfIcons.Threads, model.selected(ShellDestination.Threads), true, colors, go(model.onThreads),
                badge = model.needsYou.takeIf { it > 0 }?.let { n -> { DrawerCount(n, c.amber) } }, state = model.needsYou.takeIf { it > 0 }?.let { "$it need you" })
            if (entries.screens) DrawerItem("Screens", TfIcons.Screens, model.selected(ShellDestination.Screens), entries.screensEnabled, colors, go(model.actions.onScreens))
            if (entries.servers) DrawerItem("Servers", TfIcons.Globe, model.selected(ShellDestination.Servers), entries.serversEnabled, colors, go(model.actions.onDevServers),
                badge = model.forwards.takeIf { it > 0 }?.let { n -> { DrawerCount(n, c.accent) } }, state = model.forwards.takeIf { it > 0 }?.let { "$it forwarded" })
            if (entries.issues) DrawerItem("Issues", TfIcons.Issues, model.selected(ShellDestination.Issues), entries.issuesEnabled, colors, go(model.actions.onIssues))
            if (entries.usage) DrawerItem("Usage", TfIcons.Gauge, model.selected(ShellDestination.Usage), true, colors, go(model.actions.onUsage),
                badge = model.limitPercent?.let { p -> { Text("$p%", style = TrefoilType.meta, color = limitSeverity(p).color(c)) } }, state = limitChipDescription(model.limitPercent))
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = c.line)
            Text("Machines", style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp).semantics { heading() })
            MachineMenuItems(model, close)
            val actions = model.actions
            if (actions.onKeepConnectedChange != null || actions.onAgentNotificationsChange != null || actions.onDisconnect != null) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = c.line)
            if (actions.onKeepConnectedChange != null) DropdownMenuItem(text = { Text("Keep connected in background") },
                trailingIcon = { Checkbox(checked = actions.keepConnected, onCheckedChange = null) },
                onClick = { close(); actions.onKeepConnectedChange.invoke(!actions.keepConnected) })
            if (actions.onAgentNotificationsChange != null) DropdownMenuItem(text = { Text("Notify about agent activity") },
                trailingIcon = { Checkbox(checked = actions.agentNotifications, onCheckedChange = null) },
                onClick = { close(); actions.onAgentNotificationsChange.invoke(!actions.agentNotifications) })
            if (actions.onDisconnect != null) DropdownMenuItem(text = { Text("Disconnect machine") },
                leadingIcon = { Icon(TfIcons.Disconnect, null, Modifier.size(20.dp)) }, onClick = { close(); actions.onDisconnect.invoke() })
        }
    }
}

@Composable
private fun DrawerItem(
    label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, colors: NavigationDrawerItemColors, onClick: () -> Unit,
    badge: (@Composable () -> Unit)? = null, state: String? = null,
) {
    val c = Tf.colors
    // NavigationDrawerItem has no enabled flag: a disabled entry is dimmed and ignores taps.
    NavigationDrawerItem(
        label = { Text(label, color = if (enabled) Color.Unspecified else c.muted.copy(alpha = .38f)) },
        selected = selected, onClick = { if (enabled) onClick() }, colors = colors, badge = badge,
        icon = { Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) LocalContentColor.current else c.muted.copy(alpha = .38f)) },
        modifier = Modifier.semantics {
            if (state != null) stateDescription = state
            if (!enabled) disabled()
        },
    )
}

@Composable
private fun DrawerCount(count: Int, color: Color) {
    val c = Tf.colors
    Badge(Modifier.clearAndSetSemantics {}, containerColor = color, contentColor = c.onAccent) { Text(badgeText(count)) }
}

/** The conversation header's own back arrow (same icon, size and description), kept beside "Show thread list" for a subagent child. */
@Composable
internal fun ParentBackButton(otherNeedsYou: Int, onClick: () -> Unit) {
    val c = Tf.colors
    TfTooltip(backDescription(otherNeedsYou)) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            BadgedBox(badge = {
                if (otherNeedsYou > 0) Badge(Modifier.clearAndSetSemantics {}, containerColor = c.amber, contentColor = c.onAccent) { Text(if (otherNeedsYou > 9) "9+" else otherNeedsYou.toString()) }
            }) { Icon(TfIcons.Back, backDescription(otherNeedsYou), Modifier.size(22.dp)) }
        }
    }
}

/** Leading conversation-header control while the thread list is collapsed: shows the list again, with the needs-you count. */
@Composable
internal fun ShowThreadListButton(needsYou: Int, onClick: () -> Unit) {
    val c = Tf.colors
    val label = if (needsYou > 0) "Show thread list, $needsYou need you" else "Show thread list"
    TfTooltip(tooltipWithShortcut("Show thread list", ShortcutId.ToggleThreadList)) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            BadgedBox(badge = {
                if (needsYou > 0) Badge(Modifier.clearAndSetSemantics {}, containerColor = c.amber, contentColor = c.onAccent) { BadgeText(if (needsYou > 9) "9+" else needsYou.toString()) }
            }) { Icon(TfIcons.PanelLeft, label, Modifier.size(22.dp)) }
        }
    }
}
