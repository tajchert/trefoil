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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.tajchert.trefoil.ui.screens.ScreenViewerState
import com.tajchert.trefoil.ui.screens.ScreenSessionsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrefoilApp(
    state: ClientState, savedEnvironments: List<SavedEnvironment>,
    onPair: (String, String, Boolean) -> Unit, onConnect: (SavedEnvironment) -> Unit,
    onSelectThread: (String) -> Unit, onSend: (String) -> Unit, onInterrupt: () -> Unit,
    onRespond: (String, String, Map<String, List<String>>) -> Unit,
    draft: ThreadDraft?, onStartDraft: (ThreadDraft) -> Unit, onCancelDraft: () -> Unit,
    onSetRuntimeMode: (RuntimeMode) -> Unit, onSetModel: (ModelOption) -> Unit,
    onReconnect: () -> Unit, onDisconnect: () -> Unit, onForget: (SavedEnvironment) -> Unit,
    onSshConnect: (SshConnectionInput) -> Unit = {},
    sshHostKey: SshHostKey? = null,
    onTrustSsh: () -> Unit = {},
    onCancelSsh: () -> Unit = {},
    sshLogin: SshConnectionInput? = null,
    onSshKeyPickerActive: (Boolean) -> Unit = {},
    connectionAttempt: ConnectionAttempt = ConnectionAttempt(),
    onLoadActivity: (String) -> Unit = {},
    screenViewer: ScreenViewerState = ScreenViewerState(),
    onRefreshScreens: () -> Unit = {},
    onOpenScreen: (ScreenTarget) -> Unit = {},
    onCloseScreen: () -> Unit = {},
    isScreenAccessCurrent: (ScreenAccess) -> Boolean = { false },
    fileDownloadUi: FileDownloadUi = FileDownloadUi(),
    sendFailed: Flow<Unit> = emptyFlow(),
    onThreadAction: (ThreadTarget, ThreadAction, String?) -> Unit = { _, _, _ -> },
    onUndoSettlement: (SettlementUndo) -> Unit = {},
) {
    TrefoilTheme {
        val conversationState = rememberSaveableStateHolder()
        var adding by remember { mutableStateOf(false) }
        var machinesOpen by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.settlementUndo) {
            state.settlementUndo?.let { undo ->
                if (snackbar.showSnackbar("Thread settled", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onUndoSettlement(undo)
            }
        }
        var sshConnecting by remember { mutableStateOf(false) }
        var pairing by rememberSaveable { mutableStateOf(false) }
        var choosingEnvironment by rememberSaveable { mutableStateOf(false) }
        var creating by rememberSaveable { mutableStateOf(false) }
        // First prompt typed in the new-thread sheet; the draft conversation sends it via its composer.
        var seedPrompt by rememberSaveable { mutableStateOf<String?>(null) }
        var showScreens by rememberSaveable(state.environment?.id) { mutableStateOf(false) }
        var screenFullscreen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state.environment?.id, screenViewer.target) { if (screenViewer.target == null) screenFullscreen = false }
        var showThread by rememberSaveable { mutableStateOf(false) }
        var submittedSsh by remember { mutableStateOf(false) }
        var submittedPair by remember { mutableStateOf(false) }
        LaunchedEffect(connectionAttempt) {
            if (connectionAttempt.phase == ConnectionPhase.Succeeded) {
                if (connectionAttempt.kind == ConnectionKind.Ssh && submittedSsh) { sshConnecting = false; submittedSsh = false; choosingEnvironment = false }
                if (connectionAttempt.kind == ConnectionKind.Pairing && submittedPair) { pairing = false; submittedPair = false; choosingEnvironment = false }
            }
        }
        val cancelDraft: () -> Unit = { if (draft != null) onCancelDraft() }
        val select: (String) -> Unit = { cancelDraft(); onSelectThread(it); showThread = true }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val wide = maxWidth >= 840.dp
                BackHandler(!showScreens && (showThread || choosingEnvironment) && !wide) { cancelDraft(); showThread = false; choosingEnvironment = false }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                    if (!showScreens) TopAppBar(title = {
                        Column {
                            Text("Trefoil", fontWeight = FontWeight.Bold)
                            Text(state.environment?.let { "${it.name} · ${state.connection.name}" } ?: "Your work, within reach", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showThread && !wide) IconButton(onClick = { cancelDraft(); showThread = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to threads") }
                            Box {
                                IconButton(onClick = { machinesOpen = true }) { Icon(Icons.Default.Menu, "App navigation") }
                                DropdownMenu(machinesOpen, { machinesOpen = false }) {
                                    DropdownMenuItem(text = { Text("Machines") }, onClick = { machinesOpen = false; choosingEnvironment = true; showThread = false })
                                    DropdownMenuItem(text = { Text("Connect machine") }, enabled = !state.busy, onClick = { machinesOpen = false; adding = true })
                                    if (state.environment != null) DropdownMenuItem(text = { Text("Disconnect machine") }, onClick = { machinesOpen = false; onDisconnect(); choosingEnvironment = true; showThread = false })
                                }
                            }
                        }
                    }, actions = {
                        if (state.environment != null) IconButton(onClick = { showScreens = true }, enabled = !state.busy) { Icon(Icons.Default.Devices, "Screen sessions") }
                        if (!choosingEnvironment && selectedThreadActionsVisible(wide, showThread, draft, state.selectedThreadId)) {
                            state.threads.firstOrNull { it.id == state.selectedThreadId }?.let { ThreadActionsMenu(state, it, onThreadAction) }
                        }
                    })
                }) { padding ->
                    Column(Modifier.padding(padding).imePadding()) {
                        if (!showScreens || !screenFullscreen) ConnectionBanner(state, onReconnect)
                        if (state.environment == null || choosingEnvironment) {
                            MachinesScreen(state, savedEnvironments, { choosingEnvironment = false; onConnect(it) }, onForget, { pairing = true }, { sshConnecting = true },
                                { onDisconnect(); choosingEnvironment = true; showThread = false },
                                onBack = if (state.environment != null) ({ choosingEnvironment = false }) else null, modifier = Modifier.weight(1f))
                        } else if (showScreens) {
                            ScreenSessionsScreen(state, screenViewer, screenFullscreen, { screenFullscreen = it }, onRefreshScreens, onOpenScreen,
                                { screenFullscreen = false; onCloseScreen() }, { screenFullscreen = false; showScreens = false; onCloseScreen() },
                                Modifier.weight(1f), isAccessCurrent = isScreenAccessCurrent)
                        } else Row(Modifier.weight(1f)) {
                            if (wide || !showThread) {
                                Box(if (wide) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                                    conversationState.SaveableStateProvider("thread-list:${state.environment?.id}") {
                                        ThreadList(state, select, { creating = true }, onThreadAction)
                                    }
                                }
                            }
                            if (wide) VerticalDivider()
                            if (wide || showThread) Box(Modifier.weight(1f).fillMaxHeight()) {
                                conversationState.SaveableStateProvider("${state.environment?.id}:${if (draft != null) "draft" else state.selectedThreadId}") {
                                    Conversation(state, onSend, onInterrupt, onRespond, onLoadActivity = onLoadActivity, fileDownloadUi = fileDownloadUi,
                                        draft = draft, onSetRuntimeMode = onSetRuntimeMode, onSetModel = onSetModel, sendFailed = sendFailed,
                                        seedPrompt = seedPrompt.takeIf { draft != null }, onSeedConsumed = { seedPrompt = null })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add environment") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { adding = false; sshConnecting = true }, modifier = Modifier.fillMaxWidth()) { Text("Connect over SSH") }
                OutlinedButton(onClick = { adding = false; pairing = true }, modifier = Modifier.fillMaxWidth()) { Text("Pair with link") }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
        if (sshConnecting || sshLogin != null) key(sshLogin?.savedEnvironmentId) {
            SshConnectionDialog(state.busy, sshLogin, { sshConnecting = false; submittedSsh = false; onCancelSsh() }, onSshKeyPickerActive, connectionAttempt.takeIf { it.kind == ConnectionKind.Ssh } ?: ConnectionAttempt()) {
                submittedSsh = true
                choosingEnvironment = false
                showThread = false
                onSshConnect(it)
            }
        }
        if (sshHostKey != null) SshHostKeyDialog(sshHostKey, onTrustSsh, onCancelSsh)
        if (pairing) PairDialog(state.busy, { pairing = false; submittedPair = false; onCancelSsh() }, connectionAttempt.takeIf { it.kind == ConnectionKind.Pairing } ?: ConnectionAttempt()) { url, name, insecure -> submittedPair = true; onPair(url, name, insecure) }
        if (creating) NewThreadSheet(state, { creating = false }) { newDraft, prompt ->
            seedPrompt = prompt.trim().takeIf { it.isNotEmpty() }
            onStartDraft(newDraft); creating = false; showThread = true
        }
    }
}

@Composable
private fun ConnectionBanner(state: ClientState, reconnect: () -> Unit) {
    if (state.connection == ConnectionStatus.Connected && state.error == null && !state.busy) return
    Surface(color = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.busy || state.connection == ConnectionStatus.Connecting || state.connection == ConnectionStatus.Reconnecting)
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(if (state.busy) "Connecting…" else state.connection.name, style = MaterialTheme.typography.labelLarge)
                Text(state.error ?: if (state.environment == null) "Connect a T3 environment to get started." else "Drafts stay here. Reconnect to continue.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.environment != null && !state.busy) TextButton(onClick = reconnect) { Text("Reconnect") }
        }
    }
}

