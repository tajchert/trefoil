package com.tajchert.trefoil.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*

/** Text-field styling shared by the connect flows: accent focus, line border, s1 container. */
@Composable
internal fun tfFieldColors(): TextFieldColors {
    val c = Tf.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.line, disabledBorderColor = c.line.copy(alpha = .5f),
        errorBorderColor = c.red, focusedLabelColor = c.accent, unfocusedLabelColor = c.muted, cursorColor = c.accent,
        focusedContainerColor = c.s1, unfocusedContainerColor = c.s1, disabledContainerColor = c.s1,
    )
}

internal val TfFieldShape = RoundedCornerShape(14.dp)

/**
 * Machines: saved T3 environments, the current connection, and ways to add one.
 * [onBack] is null when there is nothing to go back to (no environment connected yet).
 */
@Composable
internal fun MachinesScreen(
    state: ClientState, saved: List<SavedEnvironment>,
    onConnect: (SavedEnvironment) -> Unit, onForget: (SavedEnvironment) -> Unit,
    onPair: () -> Unit, onSsh: () -> Unit, onDisconnect: () -> Unit,
    onBack: (() -> Unit)?, modifier: Modifier = Modifier,
    probes: Map<String, MachineProbe> = emptyMap(),
    onUsage: (() -> Unit)? = null,
) {
    val c = Tf.colors
    var forgetting by remember { mutableStateOf<SavedEnvironment?>(null) }
    Column(modifier.background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, Modifier.size(48.dp)) { Icon(TfIcons.Back, "Back", tint = c.text) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)) {
            item {
                TrefoilMark(Modifier.size(44.dp).offset(x = (-6).dp), color = Tf.colors.accent)
                Spacer(Modifier.height(12.dp))
                Text("Machines", style = TrefoilType.display, color = c.text)
                if (state.environment == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Follow your T3 Code conversations and keep remote work moving from anywhere.", style = TrefoilType.preview, color = c.muted)
                }
            }
            if (saved.isNotEmpty()) {
                item { Text("SAVED", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp)) }
                items(saved, key = { it.id }) { environment ->
                    MachineCard(environment, state.environment?.id == environment.id, state, machineStatus(environment, state, probes), onConnect = { onConnect(environment) },
                        onForget = { forgetting = environment }, onDisconnect = onDisconnect, modifier = Modifier.padding(bottom = 10.dp), onUsage = onUsage)
                }
            }
            item {
                Text("ADD", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = if (saved.isEmpty()) 28.dp else 18.dp, bottom = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddTile("Link", "Pair with link", TfIcons.Link, primary = true, enabled = !state.busy, onClick = onPair, modifier = Modifier.weight(1f))
                    AddTile("SSH", "Connect over SSH", TfIcons.Terminal, primary = false, enabled = !state.busy, onClick = onSsh, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    forgetting?.let { environment ->
        AlertDialog(onDismissRequest = { forgetting = null }, containerColor = c.sheet, shape = RoundedCornerShape(28.dp),
            title = { Text("Forget ${environment.name}?") },
            text = { Text("Saved credentials for this machine are removed from this device. You can pair it again later.", color = c.muted) },
            confirmButton = { TextButton(onClick = { forgetting = null; onForget(environment) }) { Text("Forget", color = c.red) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } })
    }
}

private fun machineSubtitle(environment: SavedEnvironment): String =
    environment.ssh?.let { "${it.username}@${it.host}:${it.port}" }
        ?: runCatching { java.net.URI(environment.baseUrl).let { uri -> uri.host?.let { host -> if (uri.port > 0) "$host:${uri.port}" else host } } }.getOrNull()
        ?: environment.baseUrl

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MachineCard(
    environment: SavedEnvironment, current: Boolean, state: ClientState, status: MachineStatus,
    onConnect: () -> Unit, onForget: () -> Unit, onDisconnect: () -> Unit, modifier: Modifier = Modifier,
    onUsage: (() -> Unit)? = null,
) {
    val c = Tf.colors
    var menu by remember { mutableStateOf(false) }
    val icon = if (environment.ssh != null) TfIcons.Server else TfIcons.Desktop
    val live = current && state.connection == ConnectionStatus.Connected
    val shape = RoundedCornerShape(20.dp)
    val working = if (live) state.threads.count { !it.archived && threadIsWorking(it) } else 0
    val needsYou = if (live) state.threads.count { !it.archived && threadIndicator(it).kind in setOf("attention", "input") } else 0
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.s1).border(if (current) 2.dp else 1.dp, if (current) c.accent else c.line, shape)
            .combinedClickable(enabled = !state.busy, role = Role.Button, onClick = onConnect, onLongClick = { menu = true })
            .semantics { stateDescription = status.label }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp)) {
            Box(Modifier.fillMaxSize().background(c.s2, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.text) }
            Box(Modifier.align(Alignment.BottomEnd).offset(if (status.inProgress) 4.dp else 2.dp, if (status.inProgress) 4.dp else 2.dp)
                .size(if (status.inProgress) 18.dp else 12.dp).background(c.s1, CircleShape), contentAlignment = Alignment.Center) {
                MachineStatusIndicator(status, size = 8.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(environment.name, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(machineSubtitle(environment), style = TrefoilType.mono, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (live && onUsage != null) Text(usageSummaryLine(state.providers), style = TrefoilType.meta, color = c.accent,
                modifier = Modifier.padding(top = 6.dp).clickable(role = Role.Button, onClick = onUsage).semantics { contentDescription = "Open usage" })
            if (!live && status != MachineStatus.Unknown && status != MachineStatus.Offline) {
                Text(if (status.inProgress) "${status.label}…" else status.label, style = TrefoilType.meta,
                    color = if (status.inProgress) c.muted else status.color(c), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
            if (working > 0 || needsYou > 0) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (working > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        WorkingSpinner(size = 14.dp); Text("$working", style = TrefoilType.meta, color = c.blue)
                    }
                    if (needsYou > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(c.amber); Text("$needsYou", style = TrefoilType.meta, color = c.amber)
                    }
                }
            }
        }
        if (current) Icon(TfIcons.Check, "Current machine", tint = c.accent)
        Box {
            IconButton(onClick = { menu = true }, Modifier.size(40.dp)) { Icon(TfIcons.More, "Options for ${environment.name}", Modifier.size(20.dp), tint = c.muted) }
            DropdownMenu(menu, { menu = false }, containerColor = c.sheet, shape = RoundedCornerShape(16.dp)) {
                if (live && onUsage != null) DropdownMenuItem(text = { Text("Usage") }, onClick = { menu = false; onUsage() })
                if (current) DropdownMenuItem(
                    text = { Text("Disconnect") }, leadingIcon = { Icon(TfIcons.Disconnect, null, Modifier.size(20.dp)) },
                    onClick = { menu = false; onDisconnect() })
                DropdownMenuItem(
                    text = { Text("Forget", color = c.red) }, enabled = !state.busy, leadingIcon = { Icon(TfIcons.Trash, null, Modifier.size(20.dp), tint = c.red) },
                    onClick = { menu = false; onForget() })
            }
        }
    }
}

@Composable
private fun AddTile(label: String, description: String, icon: ImageVector, primary: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val shape = RoundedCornerShape(20.dp)
    val fg = (if (primary) c.onAccent else c.text).let { if (enabled) it else it.copy(alpha = .5f) }
    Column(
        modifier.heightIn(min = 88.dp).clip(shape).background(if (primary) c.accent else Color.Transparent)
            .border(1.dp, if (primary) c.accent else c.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = fg)
        Text(label, style = TrefoilType.rowTitle.copy(fontSize = 15.sp), color = fg)
    }
}

@Composable
internal fun PairDialog(busy: Boolean, dismiss: () -> Unit, attempt: ConnectionAttempt = ConnectionAttempt(), pair: (String, String, Boolean) -> Unit) {
    val c = Tf.colors
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var insecure by remember(url) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val target = remember(url) { runCatching { parsePairingLink(url) }.getOrNull() }
    val uri = target?.let { runCatching { java.net.URI(it.baseUrl) }.getOrNull() }
    val privateHttp = uri?.scheme == "http" && isPrivateLanAddress(uri.host.orEmpty())
    val shownError = error ?: attempt.message.takeIf { attempt.phase == ConnectionPhase.Failed }
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(shownError) { if (shownError != null) reveal.bringIntoView() }
    AlertDialog(onDismissRequest = dismiss, containerColor = c.sheet, shape = RoundedCornerShape(28.dp),
        icon = { Icon(TfIcons.Link, null, tint = c.accent) }, title = { Text("Pair an environment") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Paste the pairing link from your T3 server. Trefoil verifies the environment before connecting.", color = c.muted)
            OutlinedTextField(name, { name = it; error = null }, enabled = !busy, label = { Text("Display name") }, singleLine = true, shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; error = null }, enabled = !busy, label = { Text("Pairing link") }, visualTransformation = PasswordVisualTransformation(),
                textStyle = TrefoilType.mono.copy(fontSize = 14.sp, color = c.text), shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Text("Pairing links contain credentials. Keep them private.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            if (privateHttp) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(insecure, { insecure = it; error = null }, enabled = !busy); Text("Allow unencrypted HTTP on this private network", Modifier.weight(1f)) }
                Text("HTTP exposes pairing credentials and conversations to others on the network. Use only a network you trust.", style = MaterialTheme.typography.bodySmall, color = c.amber)
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(attempt.message.ifBlank { "Testing connection…" }) }
            shownError?.let { Text(it, color = c.red, modifier = Modifier.bringIntoViewRequester(reveal)) }
        }
    }, confirmButton = { Button(onClick = {
        error = when { name.isBlank() -> "Enter a display name."; target == null -> "Enter a valid pairing link."; privateHttp && !insecure -> "Choose whether to allow HTTP on this private network."; else -> null }
        if (error == null) pair(url.trim(), name.trim(), privateHttp && insecure)
    }, enabled = !busy) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onAccent); Spacer(Modifier.width(8.dp)) }
        Text(if (busy) "Testing connection…" else "Pair")
    } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel", color = c.muted) } })
}
