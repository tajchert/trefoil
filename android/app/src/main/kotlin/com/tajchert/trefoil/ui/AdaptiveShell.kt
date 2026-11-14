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
import kotlinx.coroutines.delay

/** Width classes used by the shell: phone, two panes (foldable / tablet portrait), two panes + machine rail. */
internal enum class ShellLayout { Phone, TwoPane, Rail }

internal fun shellLayout(widthDp: Float): ShellLayout = when {
    widthDp >= 1200f -> ShellLayout.Rail
    widthDp >= 600f -> ShellLayout.TwoPane
    else -> ShellLayout.Phone
}

/** App-level destinations reachable from the list header / rail settings menu. Null hides an entry. */
internal data class ShellActions(
    val onMachines: () -> Unit,
    val onConnectMachine: () -> Unit,
    val onScreens: (() -> Unit)? = null,
    val onDisconnect: (() -> Unit)? = null,
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

/** Sliders button with the app menu (screen sessions, machines, connect, disconnect). */
@Composable
internal fun ShellMenuButton(state: ClientState, actions: ShellActions, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(TfIcons.Settings, "Settings", Modifier.size(22.dp), tint = tint) }
        DropdownMenu(open, { open = false }) {
            if (actions.onScreens != null) DropdownMenuItem(text = { Text("Screen sessions") }, enabled = !state.busy,
                leadingIcon = { Icon(TfIcons.Screens, null, Modifier.size(20.dp)) }, onClick = { open = false; actions.onScreens.invoke() })
            DropdownMenuItem(text = { Text("Machines") }, leadingIcon = { Icon(TfIcons.Desktop, null, Modifier.size(20.dp)) },
                onClick = { open = false; actions.onMachines() })
            DropdownMenuItem(text = { Text("Connect machine") }, enabled = !state.busy, leadingIcon = { Icon(TfIcons.Plus, null, Modifier.size(20.dp)) },
                onClick = { open = false; actions.onConnectMachine() })
            if (actions.onDisconnect != null) DropdownMenuItem(text = { Text("Disconnect machine") },
                leadingIcon = { Icon(TfIcons.Disconnect, null, Modifier.size(20.dp)) }, onClick = { open = false; actions.onDisconnect.invoke() })
        }
    }
}

/** Short rail label: the first word of the machine name, at most 8 characters. */
internal fun railLabel(name: String): String =
    name.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }?.take(8) ?: "Machine"

/** 80dp machine rail (≥1200dp): Trefoil mark, one item per saved environment, add, settings. */
@Composable
internal fun MachineRail(state: ClientState, saved: List<SavedEnvironment>, needsYou: Int, onSelect: (SavedEnvironment) -> Unit, actions: ShellActions, modifier: Modifier = Modifier,
    probes: Map<String, MachineProbe> = emptyMap()) {
    val c = Tf.colors
    Column(modifier.width(80.dp).fillMaxHeight().background(c.rail).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
        .padding(top = 16.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TrefoilMark(Modifier.padding(bottom = 18.dp).size(40.dp), color = c.accent)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val active = state.environment
            val list = if (active != null && saved.none { it.id == active.id }) listOf(active) + saved else saved
            list.forEach { environment ->
                val current = environment.id == state.environment?.id
                RailItem(environment, current, if (current) needsYou else 0, if (current) machineStatus(state) else machineStatus(environment, state, probes), enabled = !state.busy || current) {
                    if (current) actions.onMachines() else onSelect(environment)
                }
            }
            IconButton(onClick = actions.onConnectMachine, enabled = !state.busy) { Icon(TfIcons.Plus, "Add machine", Modifier.size(22.dp), tint = c.muted) }
        }
        ShellMenuButton(state, actions, c.muted)
    }
}

@Composable
private fun RailItem(environment: SavedEnvironment, current: Boolean, badge: Int, status: MachineStatus, enabled: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, contentColor = if (current) c.text else c.muted, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(72.dp).semantics {
            contentDescription = environment.name; selected = current; role = Role.Tab
            stateDescription = listOfNotNull(status.label, badge.takeIf { it > 0 }?.let { "$it need you" }).joinToString(", ")
        }) {
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(56.dp, 32.dp).background(if (current) c.accentSoft else Color.Transparent, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(if (environment.ssh != null) TfIcons.Server else TfIcons.Desktop, null, Modifier.size(22.dp), tint = if (current) c.text else c.muted)
                if (badge > 0) Text(badge.toString(), Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-2).dp).background(c.amber, RoundedCornerShape(8.dp))
                    .defaultMinSize(minWidth = 16.dp).padding(horizontal = 4.dp), color = Color(0xFF1A1205), fontFamily = InterFamily, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, lineHeight = 16.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                else MachineStatusIndicator(status, Modifier.align(Alignment.TopEnd).offset(x = if (status.inProgress) (-8).dp else (-10).dp, y = if (status.inProgress) 2.dp else 4.dp), size = 7.dp)
            }
            Text(railLabel(environment.name), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
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
