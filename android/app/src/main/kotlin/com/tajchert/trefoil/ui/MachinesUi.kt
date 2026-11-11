package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*

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
) {
    Box(modifier) { EnvironmentScreen(saved, state.busy, onConnect, onForget, onPair, onSsh) }
}

@Composable
private fun EnvironmentScreen(saved: List<SavedEnvironment>, busy: Boolean, connect: (SavedEnvironment) -> Unit, forget: (SavedEnvironment) -> Unit, add: () -> Unit, ssh: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Default.Spa, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("A little closer\nto your next idea.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Connect directly to T3 Code. Follow your conversations and keep remote work moving.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = add, enabled = !busy) { Icon(Icons.Default.AddLink, null); Spacer(Modifier.width(8.dp)); Text("Pair with link") }
            OutlinedButton(onClick = ssh, enabled = !busy) { Text("Connect over SSH") }
        }
        if (saved.isNotEmpty()) item { Text("SAVED ENVIRONMENTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        items(saved, key = { it.id }) { environment ->
            ElevatedCard(onClick = { connect(environment) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) { Text(environment.name, fontWeight = FontWeight.SemiBold); Text(environment.ssh?.let { "${it.username}@${it.host}:${it.port}" } ?: environment.baseUrl, style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = { forget(environment) }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "Forget ${environment.name}") }
                }
            }
        }
    }
}

@Composable
internal fun PairDialog(busy: Boolean, dismiss: () -> Unit, attempt: ConnectionAttempt = ConnectionAttempt(), pair: (String, String, Boolean) -> Unit) {
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
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Default.AddLink, null) }, title = { Text("Pair an environment") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Paste the pairing link from your T3 server. Trefoil verifies the environment before connecting.")
            OutlinedTextField(name, { name = it; error = null }, enabled = !busy, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; error = null }, enabled = !busy, label = { Text("Pairing link") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Text("Pairing links contain credentials. Keep them private.", style = MaterialTheme.typography.bodySmall)
            if (privateHttp) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(insecure, { insecure = it; error = null }, enabled = !busy); Text("Allow unencrypted HTTP on this private network", Modifier.weight(1f)) }
                Text("HTTP exposes pairing credentials and conversations to others on the network. Use only a network you trust.", style = MaterialTheme.typography.bodySmall)
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(attempt.message.ifBlank { "Testing connection…" }) }
            shownError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.bringIntoViewRequester(reveal)) }
        }
    }, confirmButton = { Button(onClick = {
        error = when { name.isBlank() -> "Enter a display name."; target == null -> "Enter a valid pairing link."; privateHttp && !insecure -> "Choose whether to allow HTTP on this private network."; else -> null }
        if (error == null) pair(url.trim(), name.trim(), privateHttp && insecure)
    }, enabled = !busy) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (busy) "Testing connection…" else "Pair")
    } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

