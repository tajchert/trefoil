package com.tajchert.trefoil.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.SectionLabel
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import java.util.Locale

@Composable
fun ScreenSessionsScreen(
    state: ClientState,
    viewerState: ScreenViewerState,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (ScreenTarget) -> Unit,
    onClose: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    isAccessCurrent: (ScreenAccess) -> Boolean = { false },
    viewerContent: (@Composable (StreamController, (StreamViewerStatus) -> Unit) -> Unit)? = null,
) {
    val target = viewerState.target
    val access = viewerState.access
    val environment = state.environment
    val key = target?.key
    val controller = remember(key) { StreamController() }
    var status by remember(key, access) { mutableStateOf(StreamViewerStatus()) }
    var textEntry by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var showControls by rememberSaveable(key) { mutableStateOf(true) }
    val transportConnected = state.connection == ConnectionStatus.Connected && state.environment?.id == viewerState.environmentId
    val connected = transportConnected && (access == null || isAccessCurrent(access))
    val ready = connected && access?.interactive == true && status.phase == StreamPhase.Streaming
    val canInput = ready && status.inputConnected
    val view = LocalView.current
    DisposableEffect(fullscreen, view) {
        val activity = view.context.activity()
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else bars?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(view, target) {
        val previous = view.keepScreenOn
        if (target != null) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    BackHandler {
        when { fullscreen -> onFullscreen(false); target != null -> onClose(); else -> onLeave() }
    }
    LaunchedEffect(state.environment?.id, state.selectedThreadId, state.connection, state.canRead, state.canWrite) {
        if (target == null && connectedForCatalog(state)) onRefresh()
    }
    val c = Tf.colors
    Column(modifier.fillMaxSize().background(if (fullscreen) Color.Black else c.bg)) {
        if (!fullscreen) Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (target == null) onLeave() else onClose() }) { Icon(TfIcons.Back, if (target == null) "Back to conversations" else "Back to screen sessions", Modifier.size(22.dp), tint = c.text) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(target?.title ?: "Screen sessions", style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(target?.subtitle ?: "Browser tabs and attached devices", style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (target != null) IconButton(onClick = { onFullscreen(true) }, enabled = access != null) { Icon(Icons.Default.Fullscreen, "Enter full screen", tint = c.text) }
            else IconButton(onClick = onRefresh, enabled = connectedForCatalog(state) && !state.screens.loading) { Icon(TfIcons.Refresh, "Refresh screen sessions", Modifier.size(22.dp)) }
        }
        if (!fullscreen) HorizontalDivider(color = c.line)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (target == null) ScreenCatalogList(state.screens, viewerState.error, state.selectedThreadId != null, onOpen, Modifier.fillMaxSize())
            else {
                // This node stays at the same position when chrome/system bars change: fullscreen never reloads the viewer.
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (access != null && environment != null) {
                        if (viewerContent != null) viewerContent(controller) { status = it }
                        else StreamWebView(target, access, environment, connected, controller, { status = it }, Modifier.fillMaxSize())
                    }
                    val error = viewerState.error ?: if (!connected) "Reconnect to resume this screen." else when (status.phase) {
                        StreamPhase.Error, StreamPhase.Unauthorized, StreamPhase.Gone, StreamPhase.Stopped -> status.detail ?: "Screen stream stopped. Reconnect to try again."
                        else -> null
                    }
                    if (error != null) Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.VideocamOff, null, tint = Color.White)
                        Text(error, Modifier.padding(vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { onOpen(target) }, enabled = transportConnected && !viewerState.loading) { Text("Reconnect stream") }
                    } else if (viewerState.loading || access == null || status.phase == StreamPhase.Connecting) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Text("Connecting screen…", Modifier.padding(top = 12.dp), color = Color.White)
                        }
                    }
                    if (fullscreen) {
                        FilledTonalIconButton(onClick = { onFullscreen(false) }, Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Default.FullscreenExit, "Exit full screen") }
                        FilledTonalIconButton(onClick = { showControls = !showControls }, Modifier.align(Alignment.TopStart).padding(12.dp)) { Icon(if (showControls) Icons.Default.VisibilityOff else Icons.Default.Tune, if (showControls) "Hide screen controls" else "Show screen controls") }
                        if (showControls) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .94f)) {
                            ScreenControls(target, ready, canInput, status, controller) { textEntry = true }
                        }
                    }
                }
            }
        }
        if (target != null && !fullscreen) {
            HorizontalDivider(color = c.line)
            Surface(color = c.s1) {
                Column {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (status.phase == StreamPhase.Streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape))
                        Text(if (!connected) "Disconnected" else if (canInput) "Live · controls connected" else if (status.phase == StreamPhase.Streaming) "Live · view only" else "${status.phase}", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.labelSmall)
                        if (access != null) IconButton(onClick = { onOpen(target) }, enabled = transportConnected && !viewerState.loading) { Icon(Icons.Default.Refresh, "Reconnect stream") }
                    }
                    ScreenControls(target, ready, canInput, status, controller) { textEntry = true }
                }
            }
        }
    }
    if (textEntry) AlertDialog(onDismissRequest = { textEntry = false; keyboardText = "" }, title = { Text("Type on remote screen") }, text = {
        OutlinedTextField(keyboardText, { keyboardText = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
    }, confirmButton = { TextButton(onClick = { if (controller.send(StreamCommand.Text(keyboardText))) { keyboardText = ""; textEntry = false } }, enabled = canInput && keyboardText.isNotEmpty()) { Text("Send text") } }, dismissButton = { TextButton(onClick = { textEntry = false; keyboardText = "" }) { Text("Cancel") } })
}

private fun connectedForCatalog(state: ClientState) = state.connection == ConnectionStatus.Connected && (state.canRead || state.canWrite)
private val ScreenTarget.key: String get() = when (this) {
    is ScreenTarget.Browser -> "browser:$threadId:$tabId"
    is ScreenTarget.Device -> "device:$threadId:$hostId:$deviceId"
}
private val ScreenTarget.title: String get() = when (this) { is ScreenTarget.Browser -> title.ifBlank { "Browser" }; is ScreenTarget.Device -> name.ifBlank { "Device" } }
private val ScreenTarget.subtitle: String get() = when (this) { is ScreenTarget.Browser -> url; is ScreenTarget.Device -> "${platform.uppercase(Locale.ROOT)} · $hostId" }

@Composable
private fun ScreenCatalogList(catalog: ScreenCatalog, error: String?, hasThread: Boolean, open: (ScreenTarget) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (catalog.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        (catalog.error ?: error)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        if (!hasThread) item { Text("Choose a conversation first to see its browser tabs and attached devices.", Modifier.padding(vertical = 24.dp)) }
        if (catalog.loaded && !catalog.loading && catalog.targets.isEmpty() && hasThread) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Devices, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No screen sessions yet", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                Text("Open a browser or attach a device in T3 Code, then refresh here.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (catalog.browsers.isNotEmpty()) item { SectionLabel("Browsers", Modifier.padding(top = 12.dp, start = 4.dp)) }
        items(catalog.browsers, key = { "browser:${it.threadId}:${it.tabId}" }) { ScreenSessionRow(it, open) }
        if (catalog.devices.isNotEmpty()) item { SectionLabel("Devices", Modifier.padding(top = 18.dp, start = 4.dp)) }
        items(catalog.devices, key = { "device:${it.hostId}:${it.deviceId}" }) { ScreenSessionRow(it, open) }
    }
}

@Composable
private fun ScreenSessionRow(target: ScreenTarget, open: (ScreenTarget) -> Unit) {
    val c = Tf.colors
    Surface(onClick = { open(target) }, shape = RoundedCornerShape(16.dp), color = c.s1, border = BorderStroke(1.dp, c.line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(c.s2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(if (target is ScreenTarget.Browser) Icons.Default.Language else Icons.Default.Smartphone, null, Modifier.size(20.dp), tint = c.text)
            }
            Column(Modifier.weight(1f)) {
                Text(target.title, style = TrefoilType.rowTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(target.subtitle, style = if (target is ScreenTarget.Browser) TrefoilType.mono else TrefoilType.meta, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(TfIcons.ChevronRight, "View screen", Modifier.size(20.dp), tint = c.muted)
        }
    }
}

@Composable
private fun ScreenControls(target: ScreenTarget, ready: Boolean, canInput: Boolean, status: StreamViewerStatus, controller: StreamController, text: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (target is ScreenTarget.Device) {
            IconButton(onClick = { controller.send(StreamCommand.Back) }, enabled = canInput && target.platform == "android") { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Device back") }
            IconButton(onClick = { controller.send(StreamCommand.Home) }, enabled = canInput) { Icon(Icons.Default.Home, "Device home") }
            IconButton(onClick = { controller.send(StreamCommand.AppSwitcher) }, enabled = canInput) { Icon(Icons.Default.Apps, "Device app switcher") }
            IconButton(onClick = { controller.send(StreamCommand.Rotate) }, enabled = canInput && target.platform == "ios") { Icon(Icons.Default.ScreenRotation, "Rotate remote device") }
            IconButton(onClick = text, enabled = canInput && target.platform == "android" && !target.serveSimOnly) { Icon(Icons.Default.Keyboard, "Type on remote device") }
        } else {
            IconButton(onClick = { controller.send(StreamCommand.BrowserBack) }, enabled = canInput) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Browser back") }
            IconButton(onClick = { controller.send(StreamCommand.BrowserForward) }, enabled = canInput) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Browser forward") }
            IconButton(onClick = { controller.send(StreamCommand.Reload) }, enabled = canInput) { Icon(Icons.Default.Refresh, "Reload remote page") }
            IconButton(onClick = text, enabled = canInput) { Icon(Icons.Default.Keyboard, "Type on remote browser") }
            if (canInput) TextButton(onClick = { controller.send(StreamCommand.ReleaseControl) }) { Text("Release") }
            else TextButton(onClick = { controller.send(StreamCommand.TakeControl) }, enabled = ready && status.canOperate) { Text("Take control") }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
