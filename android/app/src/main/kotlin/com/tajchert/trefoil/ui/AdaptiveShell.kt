package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import kotlinx.coroutines.delay

/** App-level destinations reachable from the list header / rail settings menu. Null hides an entry. */
internal data class ShellActions(
    val onMachines: () -> Unit,
    val onConnectMachine: () -> Unit,
    val onScreens: (() -> Unit)? = null,
    /** Dev servers and port forwarding; null hides the entry (not connected, or the server does not support it). */
    val onDevServers: (() -> Unit)? = null,
    val onUsage: (() -> Unit)? = null,
    /** Cross-project issue list; null hides the entry (not connected, or the server has no issue support). */
    val onIssues: (() -> Unit)? = null,
    val onDisconnect: (() -> Unit)? = null,
    /** Opt-in foreground service that keeps the connection alive in the background; null hides the entry. */
    val keepConnected: Boolean = false,
    val onKeepConnectedChange: ((Boolean) -> Unit)? = null,
    /** Local notifications about agent activity while in the background; null hides the entry. */
    val agentNotifications: Boolean = false,
    val onAgentNotificationsChange: ((Boolean) -> Unit)? = null,
    /**
     * "Dock beside thread": moves the open in-app browser from the Servers destination into the side panel's Preview tab in one state
     * change (destination Threads and tab Preview together), keeping the page. Null without dev servers. Windows too narrow to dock show
     * the Preview tab as the Servers destination, so the call is harmless there; callers offer it only where the panel docks (840dp+).
     */
    val onDockBrowserBesideThread: (() -> Unit)? = null,
)

/** Live states (Online … Failed) describe the active connection; the rest come from background probes. */
internal enum class MachineStatus(val label: String) {
    Online("Connected"), Connecting("Connecting"), Offline("Offline"), Failed("Connection error"),
    Reachable("Online"), Checking("Checking"), Unreachable("Unreachable"), AuthFailed("Sign-in rejected"),
    NeedsAttention("Needs attention"), Unknown("Not checked");
    /** Amber spinner instead of a dot while work is in flight. */
    val inProgress: Boolean get() = this == Connecting || this == Checking
}

internal fun machineStatus(state: ClientState): MachineStatus = when {
    state.environment == null -> MachineStatus.Offline
    state.busy || state.connection == ConnectionStatus.Connecting || state.connection == ConnectionStatus.Reconnecting -> MachineStatus.Connecting
    state.connection == ConnectionStatus.Error -> MachineStatus.Failed
    state.connection == ConnectionStatus.Connected -> MachineStatus.Online
    else -> MachineStatus.Offline
}

internal fun MachineProbe.status(): MachineStatus = when (this) {
    MachineProbe.Unknown -> MachineStatus.Unknown
    MachineProbe.Checking -> MachineStatus.Checking
    MachineProbe.Online -> MachineStatus.Reachable
    MachineProbe.Unreachable -> MachineStatus.Unreachable
    MachineProbe.AuthFailed -> MachineStatus.AuthFailed
    MachineProbe.NeedsAttention -> MachineStatus.NeedsAttention
}

/**
 * Status of any saved machine: the current one follows the live connection while it is in use;
 * otherwise (including the current machine after a disconnect) the latest background probe.
 */
internal fun machineStatus(environment: SavedEnvironment, state: ClientState, probes: Map<String, MachineProbe>): MachineStatus =
    if (environment.id == state.environment?.id && (state.busy || state.connection != ConnectionStatus.Disconnected)) machineStatus(state)
    else (probes[environment.id] ?: MachineProbe.Unknown).status()

internal fun MachineStatus.color(c: TrefoilColors): Color = when (this) {
    MachineStatus.Online, MachineStatus.Reachable -> c.green
    MachineStatus.Connecting, MachineStatus.Checking -> c.amber
    MachineStatus.Failed, MachineStatus.Unreachable, MachineStatus.AuthFailed, MachineStatus.NeedsAttention -> c.red
    MachineStatus.Offline, MachineStatus.Unknown -> c.muted
}

/** Status dot, or an amber spinner while connecting or checking. */
@Composable
internal fun MachineStatusIndicator(status: MachineStatus, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val c = Tf.colors
    if (status.inProgress) WorkingSpinner(modifier, size = size * 1.6f, color = status.color(c)) else StatusDot(status.color(c), modifier, size = size)
}

/** Overflow (⋮) button with the app menu (screen sessions, limits & usage, machines, connect, disconnect). */
@Composable
internal fun ShellMenuButton(state: ClientState, actions: ShellActions, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        TfIconButton(TfIcons.More, "More", { open = true }, iconSize = 22.dp, tint = tint, tooltip = "App menu")
        DropdownMenu(open, { open = false }) { ShellMenuItems(state, actions) { open = false } }
    }
}

/** Items of the overflow menu; split out so previews can render them without a popup window. */
@Composable
internal fun ColumnScope.ShellMenuItems(state: ClientState, actions: ShellActions, close: () -> Unit) {
    if (actions.onScreens != null) DropdownMenuItem(text = { Text("Screen sessions") }, enabled = !state.busy,
        leadingIcon = { Icon(TfIcons.Screens, null, Modifier.size(20.dp)) }, onClick = { close(); actions.onScreens.invoke() })
    if (actions.onDevServers != null) DropdownMenuItem(text = { Text("Dev servers") }, enabled = !state.busy,
        leadingIcon = { Icon(TfIcons.Globe, null, Modifier.size(20.dp)) }, onClick = { close(); actions.onDevServers.invoke() })
    if (actions.onIssues != null) DropdownMenuItem(text = { Text("Issues") }, enabled = !state.busy,
        leadingIcon = { Icon(TfIcons.Issues, null, Modifier.size(20.dp)) }, onClick = { close(); actions.onIssues.invoke() })
    if (actions.onUsage != null) DropdownMenuItem(text = { Text("Limits & usage") },
        leadingIcon = { Icon(TfIcons.Gauge, null, Modifier.size(20.dp)) }, onClick = { close(); actions.onUsage.invoke() })
    DropdownMenuItem(text = { Text("Machines") }, leadingIcon = { Icon(TfIcons.Desktop, null, Modifier.size(20.dp)) },
        onClick = { close(); actions.onMachines() })
    DropdownMenuItem(text = { Text("Connect machine") }, enabled = !state.busy, leadingIcon = { Icon(TfIcons.Plus, null, Modifier.size(20.dp)) },
        onClick = { close(); actions.onConnectMachine() })
    if (actions.onKeepConnectedChange != null || actions.onAgentNotificationsChange != null) HorizontalDivider(Modifier.padding(vertical = 4.dp))
    if (actions.onKeepConnectedChange != null) DropdownMenuItem(text = { Text("Keep connected in background") },
        trailingIcon = { Checkbox(checked = actions.keepConnected, onCheckedChange = null) },
        onClick = { close(); actions.onKeepConnectedChange.invoke(!actions.keepConnected) })
    if (actions.onAgentNotificationsChange != null) DropdownMenuItem(text = { Text("Notify about agent activity") },
        trailingIcon = { Checkbox(checked = actions.agentNotifications, onCheckedChange = null) },
        onClick = { close(); actions.onAgentNotificationsChange.invoke(!actions.agentNotifications) })
    if (actions.onDisconnect != null) DropdownMenuItem(text = { Text("Disconnect machine") },
        leadingIcon = { Icon(TfIcons.Disconnect, null, Modifier.size(20.dp)) }, onClick = { close(); actions.onDisconnect.invoke() })
}

/** Slim connection notice under the list header: amber while connecting/offline, red on errors. */
@Composable
internal fun ConnectionBanner(state: ClientState, reconnect: () -> Unit, modifier: Modifier = Modifier) {
    val failed = state.error != null
    val connecting = state.connection == ConnectionStatus.Connecting || state.connection == ConnectionStatus.Reconnecting
    var delayed by remember(state.environment?.id, connecting) { mutableStateOf(false) }
    LaunchedEffect(state.environment?.id, connecting) {
        if (connecting) { delay(1_500); delayed = true }
    }
    if (!failed && (state.connection == ConnectionStatus.Connected || state.environment == null || (connecting && !delayed))) return
    val c = Tf.colors
    val tone = if (failed) c.red else c.amber
    val background = if (failed) c.red.copy(alpha = if (c.isDark) .16f else .10f).compositeOver(c.bg) else c.amberSoft
    val message = state.error ?: when {
        state.connection == ConnectionStatus.Reconnecting -> "Reconnecting…"
        state.connection == ConnectionStatus.Connecting -> "Connecting…"
        else -> "Offline. Drafts stay here."
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(background, RoundedCornerShape(12.dp))
        .height(48.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (connecting) WorkingSpinner(size = 16.dp, color = tone) else StatusDot(tone)
        Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = TrefoilType.meta, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.environment != null) TextButton(onClick = reconnect, enabled = !connecting && !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = tone),
            contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Reconnect", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
    }
}
