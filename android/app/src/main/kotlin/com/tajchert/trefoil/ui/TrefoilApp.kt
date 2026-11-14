package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.tajchert.trefoil.ui.screens.ScreenViewerState
import com.tajchert.trefoil.ui.screens.ScreenSessionsScreen

/** Detail-pane bottom insets: the composer rides above the navigation bar and the keyboard. */
private val BottomBarsAndIme @Composable get() = WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)

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
    machineProbes: Map<String, MachineProbe> = emptyMap(),
    onStopSubagent: (String) -> Unit = {},
    usage: UsageUiState = UsageUiState(),
    onOpenUsage: () -> Unit = {},
    onUsageRange: (UsageRange) -> Unit = {},
    onRefreshUsage: () -> Unit = {},
    onRetryUsage: () -> Unit = {},
    usageOpenRequest: Pair<Long, com.tajchert.trefoil.widget.UsageWidgetTab>? = null,
) {
    TrefoilTheme {
        val conversationState = rememberSaveableStateHolder()
        var adding by remember { mutableStateOf(false) }
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
        var showUsage by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(usageOpenRequest) {
            if (usageOpenRequest != null) { showUsage = true; choosingEnvironment = false; onOpenUsage() }
        }
        LaunchedEffect(state.environment?.id, state.canReadUsage) {
            if (showUsage && state.canReadUsage) onOpenUsage()
        }
        var screenFullscreen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state.environment?.id, screenViewer.target) { if (screenViewer.target == null) screenFullscreen = false }
        var showThread by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state.selectedThreadId, draft) {
            if (state.selectedThreadId == null && draft == null) showThread = false
        }
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
        var showAgents by rememberSaveable { mutableStateOf(false) }
        val selectedThread = state.threads.firstOrNull { it.id == state.selectedThreadId }
        val roster = remember(state.runs, state.subagents) { if (state.subagents.isEmpty()) null else subagentRoster(state.runs, state.subagents) }
        val pill = remember(roster) { roster?.let { subagentPill(it) } }
        // A subagent child goes back to its parent when that parent is loaded, otherwise to the list.
        val parentThread = if (draft != null || selectedThread?.relationshipToParent != "subagent") null
            else selectedThread.parentThreadId?.let { id -> state.threads.firstOrNull { it.id == id } }
        // Back only targets the parent when it can load; offline, an uncached parent would fail and trap back on the child.
        val backParent = parentThread?.takeIf { state.connection == ConnectionStatus.Connected }
        val openAgent: (String) -> Unit = { showAgents = false; select(it) }
        // A roster that disappears (rollback, thread change) closes the sheet instead of leaving it armed to reopen.
        LaunchedEffect(roster == null) { if (roster == null) showAgents = false }
        // Thread events briefly clear canWrite; the steady value keeps Stop and its dialog from flickering.
        val steadyWrite = rememberSteadyCanWrite(state)
        val connected = state.environment != null
        val shell = ShellActions(
            onMachines = { choosingEnvironment = true; showThread = false },
            onConnectMachine = { adding = true },
            onScreens = if (connected) ({ showScreens = true }) else null,
            onUsage = if (state.connection == ConnectionStatus.Connected) ({ showUsage = true; onOpenUsage() }) else null,
            onDisconnect = if (connected) ({ onDisconnect(); choosingEnvironment = true; showThread = false }) else null,
        )
        val needsYou = remember(state.threads) { threadSections(threadShelves(state.threads), ThreadFilter.NeedsYou).sumOf { it.threads.size } }
        val c = Tf.colors
        Scaffold(containerColor = c.bg, contentColor = c.text, contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding()) }) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                val layout = shellLayout(maxWidth.value)
                val twoPane = layout != ShellLayout.Phone
                val machines = !connected || choosingEnvironment
                BackHandler(showUsage) { showUsage = false }
                BackHandler(!showUsage && !showScreens && ((choosingEnvironment && connected) || (showThread && !twoPane && !machines) || (backParent != null && !machines && (twoPane || showThread)))) {
                    if (choosingEnvironment) choosingEnvironment = false
                    else if (backParent != null) select(backParent.id)
                    else { cancelDraft(); showThread = false }
                }
                val listWidth: Dp = if (layout == ShellLayout.Rail) 360.dp else (maxWidth * 0.5f).coerceIn(320.dp, 420.dp)
                Row(Modifier.fillMaxSize()) {
                    if (layout == ShellLayout.Rail && connected) {
                        MachineRail(state, savedEnvironments, needsYou, { choosingEnvironment = false; showThread = false; onConnect(it) }, shell, probes = machineProbes)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when {
                            showUsage -> key(usageOpenRequest?.first) {
                                UsageScreen(state, usage, { showUsage = false }, onRefreshUsage, onUsageRange, onRetryUsage,
                                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), initialTab = usageOpenRequest?.second?.ordinal ?: 0)
                            }
                            machines -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                                ConnectionBanner(state, onReconnect)
                                MachinesScreen(state, savedEnvironments, { choosingEnvironment = false; onConnect(it) }, onForget, { pairing = true }, { sshConnecting = true },
                                    { onDisconnect(); choosingEnvironment = true; showThread = false },
                                    onBack = if (connected) ({ choosingEnvironment = false }) else null,
                                    modifier = Modifier.weight(1f).windowInsetsPadding(BottomBarsAndIme), probes = machineProbes, onUsage = shell.onUsage)
                            }
                            showScreens -> Column(Modifier.fillMaxSize().then(if (screenFullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)))) {
                                if (!screenFullscreen) ConnectionBanner(state, onReconnect)
                                ScreenSessionsScreen(state, screenViewer, screenFullscreen, { screenFullscreen = it }, onRefreshScreens, onOpenScreen,
                                    { screenFullscreen = false; onCloseScreen() }, { screenFullscreen = false; showScreens = false; onCloseScreen() },
                                    Modifier.weight(1f), isAccessCurrent = isScreenAccessCurrent)
                            }
                            else -> {
                                val list: @Composable (Modifier) -> Unit = { modifier ->
                                    Box(modifier.statusBarsPadding()) {
                                        conversationState.SaveableStateProvider("thread-list:${state.environment?.id}") {
                                            ThreadList(state, select, { creating = true }, onThreadAction,
                                                selectedId = if (twoPane && draft == null) state.selectedThreadId else null,
                                                shell = shell, railLayout = layout == ShellLayout.Rail,
                                                banner = { ConnectionBanner(state, onReconnect) })
                                        }
                                    }
                                }
                                val detail: @Composable (Modifier) -> Unit = { modifier ->
                                    Column(modifier.statusBarsPadding().windowInsetsPadding(BottomBarsAndIme)) {
                                        if (!twoPane) ConnectionBanner(state, onReconnect)
                                        Box(Modifier.weight(1f)) {
                                            conversationState.SaveableStateProvider("${state.environment?.id}:${if (draft != null) "draft" else state.selectedThreadId}") {
                                                Conversation(state, onSend, onInterrupt, onRespond, onLoadActivity = onLoadActivity, fileDownloadUi = fileDownloadUi,
                                                    draft = draft, onSetRuntimeMode = onSetRuntimeMode, onSetModel = onSetModel, sendFailed = sendFailed,
                                                    onBack = if (backParent != null) ({ select(backParent.id) }) else if (twoPane) null else ({ cancelDraft(); showThread = false }),
                                                    onOpenParent = parentThread?.let { p -> { select(p.id) } },
                                                    onOpenAgents = if (roster != null) ({ showAgents = true }) else null,
                                                    subtitle = selectedThread?.takeIf { draft == null && it.relationshipToParent == "subagent" }?.let { t -> parentThread?.let { "Subagent of ${it.title}" } ?: "Subagent" },
                                                    actions = {
                                                        if (selectedThreadActionsVisible(twoPane, showThread, draft, state.selectedThreadId)) {
                                                            if (pill != null) AgentsChip(pill) { showAgents = true }
                                                            state.threads.firstOrNull { it.id == state.selectedThreadId }?.let { ThreadActionsMenu(state, it, onOpenAgents = if (roster != null) ({ showAgents = true }) else null, onAction = onThreadAction) }
                                                        }
                                                    },
                                                    seedPrompt = seedPrompt.takeIf { draft != null }, onSeedConsumed = { seedPrompt = null })
                                            }
                                        }
                                    }
                                }
                                if (twoPane) Row(Modifier.fillMaxSize()) {
                                    list(Modifier.width(listWidth).fillMaxHeight())
                                    VerticalDivider(color = c.line)
                                    detail(Modifier.weight(1f).fillMaxHeight())
                                } else if (showThread && (state.selectedThreadId != null || draft != null)) detail(Modifier.fillMaxSize())
                                else list(Modifier.fillMaxSize())
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
        if (showAgents && roster != null) ThreadAgentsSheet(roster, state.providers, state.models, steadyWrite, { showAgents = false }, openAgent, onStopSubagent)
        if (creating) NewThreadSheet(state, { creating = false }) { newDraft, prompt ->
            seedPrompt = prompt.trim().takeIf { it.isNotEmpty() }
            onStartDraft(newDraft); creating = false; showThread = true
        }
    }
}

/** Header entry point for the Agents sheet: people icon plus the pill label, for example "2/5". */
@Composable
internal fun AgentsChip(pill: SubagentPill, onClick: () -> Unit) {
    val c = Tf.colors
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text,
        modifier = Modifier.padding(end = 2.dp).semantics { contentDescription = pill.accessibilityLabel; role = Role.Button }) {
        Row(Modifier.height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(TfIcons.Agents, null, Modifier.size(16.dp))
            Text(pill.label, style = TrefoilType.meta.copy(color = c.text), maxLines = 1)
        }
    }
}
