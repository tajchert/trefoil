package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.tajchert.trefoil.ui.screens.ScreenViewerState
import com.tajchert.trefoil.ui.screens.ScreenSessionsScreen
import com.tajchert.trefoil.notifications.OpenThreadRequest
import com.tajchert.trefoil.notifications.shellFresh
import com.tajchert.trefoil.ui.ports.PortBrowserState
import com.tajchert.trefoil.ui.ports.PortsActions
import com.tajchert.trefoil.ui.ports.DevServersPane
import com.tajchert.trefoil.ui.ports.StoppedForwardNotice
import com.tajchert.trefoil.ui.ports.PortsUiState
import com.tajchert.trefoil.ui.ports.ThreadPortsChip
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.LocalFold
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.ThreadListChrome
import com.tajchert.trefoil.ui.adaptive.WindowSpec
import com.tajchert.trefoil.ui.adaptive.resolvePanes
import com.tajchert.trefoil.ui.adaptive.shellPolicy
import androidx.compose.ui.focus.FocusRequester
import com.tajchert.trefoil.ui.adaptive.HingeAt
import com.tajchert.trefoil.ui.adaptive.SidePanelMode
import com.tajchert.trefoil.ui.ports.chipPorts
import com.tajchert.trefoil.ui.shell.SidePanel
import com.tajchert.trefoil.ui.shell.PanelAction
import com.tajchert.trefoil.ui.shell.PanelActionsEffect
import com.tajchert.trefoil.ui.shell.SidePanelAgents
import com.tajchert.trefoil.ui.shell.threadTabLabel
import com.tajchert.trefoil.ui.shell.SidePanelTab
import com.tajchert.trefoil.ui.shell.SidePanelHost
import com.tajchert.trefoil.ui.shell.SidePanelToggle
import com.tajchert.trefoil.ui.shell.headerBudget
import com.tajchert.trefoil.ui.shell.navEntries
import com.tajchert.trefoil.ui.shell.rememberMovableSurface
import com.tajchert.trefoil.ui.shell.resolveSidePanelTab
import com.tajchert.trefoil.ui.shell.serversMenuLabel
import com.tajchert.trefoil.ui.shell.sidePanelOpenTarget
import com.tajchert.trefoil.ui.shell.sidePanelPresentation
import com.tajchert.trefoil.ui.diff.ChangesEntry
import com.tajchert.trefoil.ui.diff.ChangesScreen
import com.tajchert.trefoil.ui.diff.ChangesUi
import com.tajchert.trefoil.ui.diff.LocalChangesEntry
import com.tajchert.trefoil.ui.shell.effectiveSidePanelMode
import com.tajchert.trefoil.ui.shell.sidePanelTabs
import com.tajchert.trefoil.ui.shell.BrandPanel
import com.tajchert.trefoil.ui.shell.EmptyDetail
import com.tajchert.trefoil.ui.shell.LocalImeHeightOverride
import com.tajchert.trefoil.ui.shell.NavChrome
import com.tajchert.trefoil.ui.shell.PaneFocus
import com.tajchert.trefoil.ui.shell.PaneOrder
import com.tajchert.trefoil.ui.shell.ParentBackButton
import com.tajchert.trefoil.ui.shell.detailPaneTitle
import com.tajchert.trefoil.ui.shell.paneGroup
import com.tajchert.trefoil.ui.shell.transientList
import com.tajchert.trefoil.ui.shell.ShellBack
import com.tajchert.trefoil.ui.shell.ShellDestination
import com.tajchert.trefoil.ui.shell.ShellDrawerSheet
import com.tajchert.trefoil.ui.shell.ShellNavModel
import com.tajchert.trefoil.ui.shell.ShellNavigationRail
import com.tajchert.trefoil.ui.shell.ShellPanes
import com.tajchert.trefoil.ui.shell.ShowThreadListButton
import com.tajchert.trefoil.ui.shell.destination
import com.tajchert.trefoil.ui.shell.navChrome
import com.tajchert.trefoil.ui.shell.rememberShellListState
import com.tajchert.trefoil.ui.shell.shellBack
import com.tajchert.trefoil.ui.shell.KeyboardShortcutsDialog
import com.tajchert.trefoil.ui.shell.ShortcutHost
import com.tajchert.trefoil.ui.shell.ShortcutId
import com.tajchert.trefoil.ui.shell.LocalShortcutRegistry
import com.tajchert.trefoil.ui.shell.registeredShortcutVisible
import com.tajchert.trefoil.ui.shell.adjacentThreadId
import com.tajchert.trefoil.ui.shell.listedThreadIds
import com.tajchert.trefoil.ui.shell.nextNeedsYouId
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/** Detail-pane bottom insets: the composer rides above the navigation bar and the keyboard. */
private val BottomBarsAndIme @Composable get() = WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)

/** Initial navigation state for previews and tests: opens destinations that otherwise need UI interaction. */
data class ShellStart(
    val showThread: Boolean = false, val showUsage: Boolean = false, val usageTab: Int = 0,
    val showScreens: Boolean = false, val showPorts: Boolean = false, val choosingEnvironment: Boolean = false,
    /** Opens the navigation drawer (two panes without a rail); ignored where there is no drawer. */
    val navigationOpen: Boolean = false,
    /** Starts with the thread list collapsed where the window allows collapsing. */
    val listCollapsed: Boolean = false,
    /** Opens this side panel tab by name ("Preview", "Screens", "Agents"); docked or mapped as the window allows. */
    val sidePanelTab: String? = null,
    /** Starts with this side thread (a child of the selected thread) as the inspector target; pair it with sidePanelTab = "Thread". */
    val inspectorThreadId: String? = null,
    /** The side panel tab (by name) Back returns to from the inspector; null closes the panel. */
    val inspectorFrom: String? = null,
)

/** How long a notification tap's selected thread may take to reach this composition before it is shown. */
private const val OPENED_THREAD_WAIT_MS = 2_000L

@Composable
fun TrefoilApp(
    state: ClientState, savedEnvironments: List<SavedEnvironment>,
    onPair: (String, String, Boolean) -> Unit, onConnect: (SavedEnvironment) -> Unit,
    onSelectThread: (String) -> Unit, onSend: (String) -> Unit, onStop: () -> Unit,
    onRespond: (String, String, Map<String, List<String>>) -> Unit,
    draft: ThreadDraft?, onStartDraft: (ThreadDraft) -> Unit, onCancelDraft: () -> Unit,
    onSetRuntimeMode: (RuntimeMode) -> Unit, onSetModel: (ModelOption) -> Unit,
    onReconnect: () -> Unit, onDisconnect: () -> Unit, onForget: (SavedEnvironment) -> Unit,
    onThreadShown: (Boolean) -> Unit = {},
    onSubmitMessage: (ComposerSubmission, FollowUpMode?) -> Unit = { submission, _ -> onSend(submission.text) },
    sendOutcomes: Map<String, SendOutcome> = emptyMap(), onConsumeSendOutcome: (String) -> Unit = {}, stopPendingKeys: Set<String> = emptySet(),
    sendsInFlight: Set<String> = emptySet(), queueOperationKeys: Set<String> = emptySet(),
    onCancelQueued: (String) -> Unit = {}, onSteerQueued: (String) -> Unit = {}, onResumeQueue: () -> Unit = {},
    keepConnected: Boolean = false, onSetModelOption: (String, Any) -> Unit = { _, _ -> }, onSetModelFavorite: (ModelKey, Boolean) -> Unit = { _, _ -> },
    onKeepConnectedChange: (Boolean) -> Unit = {},
    agentNotifications: Boolean = false, onAgentNotificationsChange: (Boolean) -> Unit = {},
    openedThread: OpenThreadRequest? = null, onOpenedThreadShown: () -> Unit = {},
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
    /** Streams a side thread of the selected thread into [ClientState.inspector]; the shell closes it again when the Thread tab is not shown. */
    onOpenInspector: (String) -> Unit = {},
    onCloseInspector: () -> Unit = {},
    usage: UsageUiState = UsageUiState(),
    onOpenUsage: () -> Unit = {},
    onUsageRange: (UsageRange) -> Unit = {},
    onRefreshUsage: () -> Unit = {},
    onRetryUsage: () -> Unit = {},
    usageOpenRequest: Pair<Long, com.tajchert.trefoil.widget.UsageWidgetTab>? = null,
    pairLinkRequest: Pair<Long, String>? = null,
    onAllowLocalNetworkHttp: (String, Boolean) -> Unit = { _, _ -> },
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    composerDrafts: ComposerDraftUi = ComposerDraftUi(),
    promptSuggestions: PromptSuggestionUi = PromptSuggestionUi(),
    sideChat: SideChatUi = SideChatUi(),
    portBrowser: PortBrowserState? = null,
    onOpenPortInApp: (Int) -> Unit = {},
    onOpenPortExternal: (Int) -> Unit = {},
    onStopPortForward: (Int) -> Unit = {},
    onForwardPort: (Int) -> Unit = {},
    onClosePortBrowser: () -> Unit = {},
    stoppedForwards: Map<String, StoppedForwardNotice> = emptyMap(),
    onDismissStoppedForwards: (environmentId: String) -> Unit = {},
    changes: ChangesUi = ChangesUi(),
    start: ShellStart = ShellStart(),
) {
    TrefoilTheme {
        val conversationState = rememberSaveableStateHolder()
        // Folded timelines of recently shown threads, for this environment only (memory only; see TimelineFoldMemo).
        val timelineFolds = remember(state.environment?.id) { TimelineFoldMemo() }
        val shellContentState = rememberSaveableStateHolder()
        var adding by rememberSaveable { mutableStateOf(false) }
        var shortcutsOpen by rememberSaveable { mutableStateOf(false) }
        // The root key handler reads this at key time; the bindings are rebuilt after each composition (below) from the same actions the buttons call.
        val shortcutHost = remember { ShortcutHost() }
        // Key events only reach a window whose Compose tree holds focus, so the root takes focus once when nothing else has (a tap, Tab or a field keeps its own).
        val rootFocus = remember { FocusRequester() }
        var rootHasFocus by remember { mutableStateOf(false) }
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.settlementUndo) {
            state.settlementUndo?.let { undo ->
                if (snackbar.showSnackbar("Thread settled", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onUndoSettlement(undo)
            }
        }
        var sshConnecting by rememberSaveable { mutableStateOf(false) }
        var pairing by rememberSaveable { mutableStateOf(false) }
        // One saved destination. Screens and Dev servers belong to the environment they were opened for and fall back to Threads on a switch.
        val environmentId = state.environment?.id
        var savedDestination by rememberSaveable { mutableStateOf(start.destination()) }
        var destinationEnvironment by rememberSaveable { mutableStateOf(environmentId) }
        val destination = if (savedDestination.environmentScoped && destinationEnvironment != environmentId) ShellDestination.Threads else savedDestination
        var screenFullscreen by rememberSaveable { mutableStateOf(false) }
        // The turn the Changes screen shows (null: the newest); it belongs to the selected thread.
        var changesTurn by rememberSaveable(state.selectedThreadId) { mutableStateOf<Int?>(null) }
        // The side panel beside the conversation: the open tab and the last used one, saved by enum name only. It belongs to one machine.
        var sidePanelTabName by rememberSaveable { mutableStateOf(start.sidePanelTab) }
        var lastPanelTabName by rememberSaveable { mutableStateOf(start.sidePanelTab) }
        var panelEnvironment by rememberSaveable { mutableStateOf(environmentId) }
        // The inspected side thread behind the Thread tab: its id, the tab it was opened from (Back returns there) and the selected thread it
        // belongs to. Saved with the panel; the stream itself is opened and closed by the effect below, never saved.
        var inspectorThreadId by rememberSaveable { mutableStateOf(start.inspectorThreadId) }
        var inspectorFrom by rememberSaveable { mutableStateOf(start.inspectorFrom) }
        // "Add to chat" from the Thread tab: a reference for the selected thread's composer, until that composer has taken it.
        var referenceInsert by remember { mutableStateOf<ThreadReferenceInsert?>(null) }
        var inspectorParent by rememberSaveable { mutableStateOf(start.inspectorThreadId?.let { state.selectedThreadId }) }
        val dropInspector: () -> Unit = { inspectorThreadId = null; inspectorFrom = null; if (sidePanelTabName == SidePanelTab.Thread.name) sidePanelTabName = null }
        if (environmentId != null && panelEnvironment != environmentId) { panelEnvironment = environmentId; sidePanelTabName = null; dropInspector() }
        // The target belongs to the thread that was selected when it was set (and never to a draft): another selection or a draft ends it.
        if (inspectorThreadId != null && (draft != null || (state.selectedThreadId != null && state.selectedThreadId != inspectorParent))) dropInspector()
        val savedPanelTab = SidePanelTab.entries.firstOrNull { it.name == sidePanelTabName }
        // Closing the panel ends what only the panel showed, whichever tab is in front: a page in Preview, a viewer in Screens and the
        // inspected thread. Nothing live (a tunnel page, a screen stream) stays open behind a closed panel; switching tabs keeps all of it.
        val closePanel: () -> Unit = {
            if (portBrowser != null) onClosePortBrowser()
            screenFullscreen = false; onCloseScreen()
            sidePanelTabName = null
            inspectorThreadId = null; inspectorFrom = null
        }
        // Every destination change (rail, drawer, menus, Back out of a destination) closes the side panel.
        val navigate: (ShellDestination) -> Unit = { if (sidePanelTabName != null) closePanel(); savedDestination = it; destinationEnvironment = environmentId }
        // The Thread tab is never the "last used" tab: it only exists while its target does.
        val openPanel: (SidePanelTab) -> Unit = { sidePanelTabName = it.name; if (it != SidePanelTab.Thread) lastPanelTabName = it.name; panelEnvironment = environmentId }
        // "Dock beside thread": the open browser leaves the Servers destination for the Preview tab in one state change (same composition).
        val dockBrowserBesideThread: () -> Unit = {
            savedDestination = ShellDestination.Threads; destinationEnvironment = environmentId; openPanel(SidePanelTab.Preview)
        }
        // No environment (restoring, reconnecting) keeps the owner, as the side panel does: the same machine coming back restores its destination.
        LaunchedEffect(environmentId) {
            if (environmentId != null && destinationEnvironment != environmentId) { if (savedDestination.environmentScoped) savedDestination = ShellDestination.Threads; destinationEnvironment = environmentId }
        }
        val choosingEnvironment = destination == ShellDestination.Machines
        val showUsage = destination == ShellDestination.Usage
        val showScreens = destination == ShellDestination.Screens
        val showPorts = destination == ShellDestination.Servers
        val drawerState = rememberDrawerState(if (start.navigationOpen) DrawerValue.Open else DrawerValue.Closed)
        // A t3code: link opens the pair dialog pre-filled and never submits. Plain remember: the link is not persisted.
        var pairLink by remember { mutableStateOf<Pair<Long, String>?>(null) }
        LaunchedEffect(pairing) { if (!pairing) pairLink = null }
        var creating by rememberSaveable { mutableStateOf(false) }
        // Project of the draft row that opened the sheet; null when opened from "+".
        var creatingProject by rememberSaveable { mutableStateOf<String?>(null) }
        val uiScope = rememberCoroutineScope()
        val discardDraft: (com.tajchert.trefoil.core.session.ComposerDraft) -> Unit = { discarded ->
            // The token restores only while nothing replaced the draft meanwhile.
            val undo = composerDrafts.discard(discarded.key)
            if (undo != null) uiScope.launch { if (snackbar.showSnackbar("Draft discarded", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) composerDrafts.restore(undo) }
        }
        // First prompt typed in the new-thread sheet; the draft conversation sends it via its composer.
        var seedPrompt by rememberSaveable { mutableStateOf<String?>(null) }
        // A widget request picks the tab; the header chip and menu always open Limits.
        var usageTab by rememberSaveable { mutableIntStateOf(start.usageTab) }
        LaunchedEffect(usageOpenRequest) {
            if (usageOpenRequest != null) { usageTab = usageOpenRequest.second.ordinal; navigate(ShellDestination.Usage); if (state.canReadUsage) onOpenUsage() }
        }
        LaunchedEffect(state.environment?.id, state.canReadUsage) {
            if (showUsage && state.canReadUsage) onOpenUsage()
        }
        // Dev servers need a live environment whose server answers discovery; an older server hides the feature.
        val portsAvailable = state.environment != null && state.localServers.supported
        LaunchedEffect(portsAvailable) { if (!portsAvailable && savedDestination == ShellDestination.Servers) savedDestination = ShellDestination.Threads }
        // Leaving for the machine list drops the browser page; the forward itself stays open until stopped or disconnected.
        LaunchedEffect(choosingEnvironment) { if (choosingEnvironment) onClosePortBrowser() }
        val portsUi = PortsUiState(
            servers = state.localServers.servers, forwards = state.portForwards,
            threadTitles = remember(state.threads) { state.threads.associate { it.id to it.title } },
            live = state.localServers.live, supported = state.localServers.supported, tunnelsDisabled = state.previewTunnelDisabled,
            canWrite = state.canWrite && state.connection == ConnectionStatus.Connected, selectedThreadId = state.selectedThreadId,
            stoppedNotice = state.environment?.id?.let { stoppedForwards[it] }?.message)
        // "Back to conversations" can sit beside an open page (a wide window without a rail), so leaving closes the page too.
        val portsActions = PortsActions({ if (portBrowser != null) onClosePortBrowser(); navigate(ShellDestination.Threads) }, onOpenPortInApp, onOpenPortExternal, onStopPortForward, onForwardPort) { state.environment?.id?.let(onDismissStoppedForwards) }
        LaunchedEffect(state.environment?.id, screenViewer.target) { if (screenViewer.target == null) screenFullscreen = false }
        var showThread by rememberSaveable { mutableStateOf(start.showThread) }
        LaunchedEffect(state.selectedThreadId, draft) {
            if (state.selectedThreadId == null && draft == null) showThread = false
        }
        var submittedSsh by remember { mutableStateOf(false) }
        var submittedPair by remember { mutableStateOf(false) }
        LaunchedEffect(pairLinkRequest) {
            if (pairLinkRequest != null) {
                // Replace the dialog's link rather than stack another one; a never-submitted SSH form is closed like its Cancel button,
                // other SSH prompts keep the screen and the pair dialog waits behind them (nothing is cancelled).
                adding = false
                if (sshConnecting && !submittedSsh && !state.busy && sshLogin == null && sshHostKey == null) { sshConnecting = false; submittedSsh = false; onCancelSsh() }
                pairLink = pairLinkRequest; submittedPair = false; pairing = true; navigate(ShellDestination.Machines); showThread = false
            }
        }
        LaunchedEffect(connectionAttempt) {
            if (connectionAttempt.phase == ConnectionPhase.Succeeded) {
                if (connectionAttempt.kind == ConnectionKind.Ssh && submittedSsh) { sshConnecting = false; submittedSsh = false; if (choosingEnvironment) navigate(ShellDestination.Threads) }
                if (connectionAttempt.kind == ConnectionKind.Pairing && submittedPair) { pairing = false; submittedPair = false; if (choosingEnvironment) navigate(ShellDestination.Threads) }
            }
        }
        val cancelDraft: () -> Unit = { if (draft != null) onCancelDraft() }
        val select: (String) -> Unit = { cancelDraft(); onSelectThread(it); showThread = true }
        var showAgents by rememberSaveable { mutableStateOf(false) }
        val selectedThread = state.threads.firstOrNull { it.id == state.selectedThreadId }
        val roster = remember(state.runs, state.subagents) { if (state.subagents.isEmpty()) null else subagentRoster(state.runs, state.subagents) }
        val pill = remember(roster) { roster?.let { subagentPill(it) } }
        // A subagent child or side chat goes back to its parent when that parent is loaded, otherwise to the list.
        val parentThread = if (draft != null || selectedThread == null || (selectedThread.relationshipToParent != "subagent" && !selectedThread.sideChat)) null
            else selectedThread.parentThreadId?.let { id -> state.threads.firstOrNull { it.id == id } }
        // Back only targets the parent when it can load; offline, an uncached parent would fail and trap back on the child.
        val backParent = parentThread?.takeIf { state.connection == ConnectionStatus.Connected }
        val openAgent: (String) -> Unit = { showAgents = false; select(it) }
        // A roster that disappears (rollback, thread change) closes the sheet instead of leaving it armed to reopen.
        LaunchedEffect(roster == null) { if (roster == null) showAgents = false }
        // Thread events briefly clear canWrite; the steady value keeps Stop and its dialog from flickering.
        val steadyWrite = rememberSteadyCanWrite(state)
        val connected = state.environment != null
        val threadServers = remember(state.localServers.servers, state.selectedThreadId) {
            state.selectedThreadId?.let { id -> state.localServers.servers.filter { it.threadId == id } }.orEmpty()
        }
        val shell = ShellActions(
            onMachines = { navigate(ShellDestination.Machines); showThread = false },
            onConnectMachine = { adding = true },
            onScreens = if (connected) ({ navigate(ShellDestination.Screens) }) else null,
            onDevServers = if (portsAvailable) ({ navigate(ShellDestination.Servers) }) else null,
            // Offline the screen shows cached limits; only a connection that can read usage fetches (the effect above covers a later connect).
            onUsage = if (connected) ({ usageTab = 0; navigate(ShellDestination.Usage); if (state.canReadUsage) onOpenUsage() }) else null,
            onDisconnect = if (connected) ({ onDisconnect(); navigate(ShellDestination.Machines); showThread = false }) else null,
            keepConnected = keepConnected,
            onKeepConnectedChange = onKeepConnectedChange,
            agentNotifications = agentNotifications,
            onAgentNotificationsChange = onAgentNotificationsChange,
            onDockBrowserBesideThread = if (portsAvailable) dockBrowserBesideThread else null,
        )
        val needsYouThreads = remember(state.threads) { needsYouThreads(state.threads) }
        val needsYou = needsYouThreads.size
        // The conversation counts as open only while it is on screen; the list, machines, usage and screens hide it.
        var wideLayout by remember { mutableStateOf(false) }
        // Set by the layout below: a tab mapped onto a destination, or a browser page, covers the conversation; a page docked in Preview does not.
        var conversationCovered by remember { mutableStateOf<Boolean?>(null) }
        val openThreadId = state.selectedThreadId.takeIf {
            draft == null && it != null && (wideLayout || showThread) && connected &&
                !(conversationCovered ?: (destination != ShellDestination.Threads || portBrowser != null))
        }
        // Visit tracking follows what is on screen, not merely which thread is selected.
        LaunchedEffect(openThreadId != null) { onThreadShown(openThreadId != null) }
        DisposableEffect(Unit) { onDispose { onThreadShown(false) } }
        val otherNeedsYou = if (state.connection == ConnectionStatus.Connected) needsYouThreads.count { it.id != openThreadId } else 0
        // A thread that enters Needs you while another screen is open surfaces as a snackbar. Detection only queues the
        // message; one long-lived collector shows them in turn so a state update never dismisses a snackbar on screen.
        val attention = remember { Channel<AttentionMessage>(Channel.UNLIMITED) }
        var attentionTrack by remember { mutableStateOf(AttentionTrack()) }
        // Fresh is the live shell (shared with the notification driver), not canRead, which also waits for the open conversation's detail.
        val shellFresh = shellFresh(state)
        LaunchedEffect(state.threads, state.environment?.id, state.connection, shellFresh, openThreadId) {
            val step = attentionStep(attentionTrack, state.environment?.id, state.connection == ConnectionStatus.Connected, shellFresh, state.threads, openThreadId)
            attentionTrack = step.track
            attentionMessage(step.announce)?.let { attention.trySend(it) }
        }
        val openAttention by rememberUpdatedState<(String?) -> Unit> { id ->
            navigate(ShellDestination.Threads); onClosePortBrowser()
            if (id != null && state.threads.any { it.id == id }) select(id) else { cancelDraft(); showThread = false }
        }
        // A tap on an agent notification: the view model switched machines and selected the thread (checked against the machine it
        // names); this only brings that thread on screen, once. A request of another machine or thread than the current one is just consumed.
        val latestState by rememberUpdatedState(state)
        LaunchedEffect(openedThread) {
            val opened = openedThread ?: return@LaunchedEffect
            // The selection is in the client state already; this composition may just not have seen it yet.
            val selected = withTimeoutOrNull(OPENED_THREAD_WAIT_MS) {
                snapshotFlow { latestState.environment?.id == opened.environmentId && latestState.selectedThreadId == opened.threadId }.first { it }
            } != null
            if (selected) { navigate(ShellDestination.Threads); onClosePortBrowser(); showThread = true }
            onOpenedThreadShown()
        }
        val attentionRelevant by rememberUpdatedState<(AttentionMessage) -> Boolean> {
            state.connection == ConnectionStatus.Connected && attentionStillRelevant(it, needsYouThreads, openThreadId)
        }
        // Messages belong to one environment: switching or losing it drops what is queued and dismisses what is showing.
        LaunchedEffect(attention, state.environment?.id) {
            while (attention.tryReceive().isSuccess) { /* drop messages queued for the previous environment */ }
            for (message in attention) {
                if (!attentionRelevant(message)) continue
                // Only this message's presentation is cancelled when it stops being relevant, queued or showing; the Undo snackbar is its own coroutine.
                coroutineScope {
                    val shown = async { snackbar.showSnackbar(message.text, "Open", duration = SnackbarDuration.Short) }
                    val guard = launch { snapshotFlow { attentionRelevant(message) }.first { !it }; shown.cancel() }
                    shown.join(); guard.cancel()
                    if (!shown.isCancelled && shown.getCompleted() == SnackbarResult.ActionPerformed) openAttention(message.threadId)
                }
            }
        }
        // The dev-servers and screen-sessions surfaces exist once each: the destination or the side panel calls the same composition,
        // so moving between them (dock, undock, rotate, fold) never rebuilds the browser's WebView or reconnects a screen stream.
        // Beside the rail or in the docked panel these screens have no leave arrow: the rail, the panel's close button and the shell's Back leave them.
        var besideChrome by remember { mutableStateOf(false) }
        // "Dock beside thread" is offered where the panel docks, a conversation is open and the page is not in the panel already.
        var canDockBrowser by remember { mutableStateOf(false) }
        // Inside the docked panel these surfaces hand their one header action (Forward a port, Refresh) to the panel's tab bar.
        val panelHost = remember { SidePanelHost() }
        var inPanel by remember { mutableStateOf(false) }
        val devServersSurface = rememberMovableSurface { m ->
            DevServersPane(portsUi, portsActions, portBrowser, onClosePortBrowser, { portBrowser?.let { onOpenPortExternal(it.remotePort) } }, hosted = besideChrome, modifier = m,
                browserActions = if (canDockBrowser) ({ TfIconButton(TfIcons.PanelRight, "Dock beside thread", dockBrowserBesideThread, tint = Tf.colors.text, iconSize = 22.dp) }) else null,
                panel = if (inPanel) panelHost else null)
        }
        val screensSurface = rememberMovableSurface { m ->
            ScreenSessionsScreen(state, screenViewer, screenFullscreen, { screenFullscreen = it }, onRefreshScreens, onOpenScreen,
                { screenFullscreen = false; onCloseScreen() },
                if (besideChrome) null else ({ if (sidePanelTabName != null) closePanel() else { screenFullscreen = false; navigate(ShellDestination.Threads); onCloseScreen() } }),
                m, isAccessCurrent = isScreenAccessCurrent, panel = if (inPanel) panelHost else null)
        }
        val toggleFocus = remember { FocusRequester() }
        val panelTabsFocus = remember { FocusRequester() }
        // Opening the panel moves focus to its tab row, closing returns it to the header toggle (after the frame that composes it).
        var focusRequest by remember { mutableStateOf<FocusRequester?>(null) }
        LaunchedEffect(focusRequest) {
            val target = focusRequest ?: return@LaunchedEffect
            // The target may not be attached yet (or at all, like a toggle the header leaves out): keep the request for a few frames, never throw.
            var attempts = 0
            do {
                withFrameNanos { }
                val focused = runCatching { target.requestFocus() }.getOrDefault(false)
            } while (!focused && ++attempts < 3)
            focusRequest = null
        }
        val c = Tf.colors
        // The host wraps the snackbar unscaled; its bounds count only while a message is current, so floating actions start back down as it fades.
        var snackbarHostBounds by remember { mutableStateOf<Rect?>(null) }
        val snackbarBounds = remember { derivedStateOf { snackbarHostBounds?.takeIf { snackbar.currentSnackbarData != null } } }
        CompositionLocalProvider(LocalSnackbarBounds provides snackbarBounds, LocalShortcutRegistry provides shortcutHost.registry) {
        // The snackbar host is drawn inside the window box below, last, so it can follow a hinge.
        Scaffold(containerColor = c.bg, contentColor = c.text, contentWindowInsets = WindowInsets(0)) { padding ->
            // The window decides how many panes exist; every screen sizes itself from the container it is given.
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize().onPreviewKeyEvent(shortcutHost::dispatch)
                .onFocusChanged { rootHasFocus = it.hasFocus }.focusRequester(rootFocus).focusTarget()) {
                // Without a machine there is no rail, so a split window's start region begins at the window edge (the hinge itself does not move).
                // The target is attached once this subcomposition lays out; a field, Tab or a tap that took focus first keeps it.
                LaunchedEffect(Unit) { withFrameNanos { }; if (!rootHasFocus) rootFocus.requestFocus() }
                val policy = shellPolicy(WindowSpec(maxWidth.value, maxHeight.value, LocalFold.current)).let { if (!connected && it.splitFold != null) it.copy(rail = false) else it }
                val twoPane = policy.twoPane
                SideEffect { wideLayout = twoPane }
                val listState = rememberShellListState(policy, environmentId, start.listCollapsed)
                // Side panel tabs: Preview with dev servers, Screens with the rail's Screens entry (disabled with it), Agents with a roster.
                val entries = navEntries(state, shell)
                val panelTabs = sidePanelTabs(portsAvailable, entries.screens, roster != null, changes = state.checkpoints.isNotEmpty(), thread = inspectorThreadId != null)
                val panelDisabled = if (entries.screensEnabled) emptySet() else setOf(SidePanelTab.Screens)
                val panelTab = resolveSidePanelTab(savedPanelTab, panelTabs, panelDisabled)
                // A saved tab that went away (roster gone, servers unsupported) is replaced by what is shown, so the toggle and Back agree with the screen.
                LaunchedEffect(panelTab, savedPanelTab, connected) { if (connected && panelTab != savedPanelTab) sidePanelTabName = panelTab?.name }
                val panelMode = effectiveSidePanelMode(policy)
                val canDock = panelMode != SidePanelMode.FullScreen
                val presentation = sidePanelPresentation(destination, panelTab, panelMode, screenFullscreen)
                val hasConversation = draft != null || state.selectedThreadId != null
                val docked = canDock && presentation.docked && hasConversation && connected
                // Without a thread the detail is the empty state: no header, no toggle, so an open docked panel closes.
                LaunchedEffect(presentation.docked && canDock && connected && !hasConversation) { if (presentation.docked && canDock && connected && !hasConversation) closePanel() }
                @Suppress("NAME_SHADOWING") val destination = presentation.destination
                @Suppress("NAME_SHADOWING") val showScreens = destination == ShellDestination.Screens
                @Suppress("NAME_SHADOWING") val showPorts = destination == ShellDestination.Servers
                // A docked panel hosts the browser itself (Preview); otherwise an open page takes the window as it does today.
                val browserHosted = portBrowser != null && !docked
                SideEffect { conversationCovered = destination != ShellDestination.Threads || browserHosted || presentation.changesRoute }
                val panes = resolvePanes(policy, listState.startWidth.value, listState.collapsed, sidePanelOpen = docked)
                val split = policy.splitFold != null
                val nav = navChrome(policy.rail, twoPane, policy.compactHeight, connected)
                val besideRail = nav == NavChrome.Rail
                SideEffect { besideChrome = besideRail || docked; inPanel = docked; canDockBrowser = canDock && !docked && hasConversation && connected }
                val machines = !connected || choosingEnvironment
                val closePanelToToggle: () -> Unit = { closePanel(); focusRequest = toggleFocus }
                val openPanelTab: (SidePanelTab) -> Unit = { openPanel(it); focusRequest = panelTabsFocus }
                // The inspector: the Thread tab of a docked panel, with its side thread streamed only while that tab is in front and the shell is
                // fresh. Folding to a window without a docked panel closes the stream but keeps the target, so docking again reopens it.
                val inspectorKind = inspectorThreadId?.let { id -> state.threads.firstOrNull { it.id == id }?.sideThreadKind() ?: state.inspector?.takeIf { it.threadId == id }?.kind } ?: SideThreadKind.Subagent
                val inspectorShown = docked && panelTab == SidePanelTab.Thread && inspectorThreadId != null
                // A stale shell only pauses the stream (the session keeps the last rows, marked not ready); hiding the tab closes it.
                val inspectorFresh = shellFresh(state)
                LaunchedEffect(inspectorShown, inspectorThreadId, inspectorFresh) {
                    val id = inspectorThreadId
                    if (!inspectorShown || id == null) onCloseInspector() else if (inspectorFresh) onOpenInspector(id)
                }
                // Leaves the inspector for the tab it was opened from (when that tab still exists) or closes the panel.
                val leaveInspector: () -> Unit = {
                    val from = inspectorFrom?.let { name -> panelTabs.firstOrNull { it.name == name } }?.takeIf { it != SidePanelTab.Thread && it !in panelDisabled }
                    onCloseInspector()
                    inspectorThreadId = null; inspectorFrom = null
                    if (from != null) { openPanel(from); focusRequest = panelTabsFocus } else closePanelToToggle()
                }
                // Maximize: the side thread becomes the selected one and the panel closes with it.
                val maximizeInspector: (String) -> Unit = { id -> onCloseInspector(); closePanel(); focusRequest = toggleFocus; select(id) }
                // A subagent or side chat of the selected thread opens in the Thread tab of a dockable window; anywhere else it opens as the thread.
                val openSideThread: (String) -> Unit = { id ->
                    val child = state.threads.firstOrNull { it.id == id }
                    if (canDock && connected && hasConversation && draft == null && child?.sideThreadKind() != null && child.parentThreadId == state.selectedThreadId) {
                        showAgents = false
                        inspectorFrom = if (docked) panelTab?.takeIf { it != SidePanelTab.Thread }?.name ?: inspectorFrom else null
                        inspectorThreadId = id; inspectorParent = state.selectedThreadId
                        openPanel(SidePanelTab.Thread); focusRequest = panelTabsFocus
                    } else openAgent(id)
                }
                val back = shellBack(destination, connected, browserHosted, twoPane, showThread, backParent != null, panelDocked = docked, besideRail = besideRail, inspectorOpen = inspectorShown)
                BackHandler(back != null) {
                    when (back) {
                        ShellBack.ToThreads -> navigate(ShellDestination.Threads)
                        ShellBack.CloseInspector -> leaveInspector()
                        ShellBack.ClosePanel -> closePanelToToggle()
                        ShellBack.ToParent -> backParent?.let { select(it.id) }
                        ShellBack.ToList -> { cancelDraft(); showThread = false }
                        null -> Unit
                    }
                }
                // The full-screen Changes route (narrow windows) is left by Back before anything else; registered later, so it wins.
                BackHandler(presentation.changesRoute && connected) { closePanel() }
                val navModel = ShellNavModel(state, savedEnvironments, machineProbes, shell, destination, browserHosted, needsYou,
                    onThreads = { navigate(ShellDestination.Threads); onClosePortBrowser() },
                    onNewThread = { creatingProject = null; creating = true },
                    onShortcuts = { shortcutsOpen = true },
                    onSelectMachine = { navigate(ShellDestination.Threads); showThread = false; onConnect(it) })
                val listHidden = twoPane && panes.list == 0f
                // "Show thread list": a list the panel replaced comes back by closing the panel; a collapsed one by expanding it.
                val showList: () -> Unit = {
                    if (docked && (panelMode == SidePanelMode.ReplacesList || !listState.collapsed)) closePanelToToggle() else listState.collapsed = false
                }
                // Compact height with the keyboard open: the pane holding focus takes the full width, only while the keyboard is up.
                var paneFocus by remember { mutableStateOf(PaneFocus.None) }
                val density = LocalDensity.current
                val imeDp = LocalImeHeightOverride.current ?: (WindowInsets.ime.getBottom(density) / density.density)
                val transient = transientList(twoPane, split, policy.compactHeight, imeDp > 0f, maxHeight.value - imeDp, paneFocus,
                    panelReplacesList = docked && panelMode == SidePanelMode.ReplacesList)
                // Header budget (spec 3.2): which chips stay beside the title, from the conversation pane's own width.
                val threadPorts = remember(threadServers) { chipPorts(threadServers) }
                val dockable = canDock && panelTabs.isNotEmpty()
                val budget = headerBudget(maxWidth.value, panes.conversation, density.fontScale, pill != null, portsAvailable && threadPorts.isNotEmpty(), dockable, panelOpen = docked)
                val openAgents: () -> Unit = if (canDock) ({ openPanelTab(SidePanelTab.Agents) }) else ({ showAgents = true })
                val openServers: () -> Unit = if (canDock) ({ openPanelTab(SidePanelTab.Preview) }) else ({ navigate(ShellDestination.Servers) })
                // Changes: docked beside the conversation where a panel fits, otherwise a full-screen route. A run opens its own turn when it has a checkpoint.
                val openChanges: (String?) -> Unit = { runId ->
                    changesTurn = runId?.let { id -> latestState.runs.firstOrNull { it.id == id }?.ordinal }
                    if (canDock) openPanelTab(SidePanelTab.Changes) else openPanel(SidePanelTab.Changes)
                }
                val latestOpenChanges by rememberUpdatedState(openChanges)
                val hasCheckpoints = state.checkpoints.isNotEmpty()
                val changesEntry = remember(hasCheckpoints) { if (hasCheckpoints) ChangesEntry { runId -> latestOpenChanges(runId) } else null }
                val changesSurface: @Composable (Modifier, (() -> Unit)?) -> Unit = { modifier, onClose ->
                    ChangesScreen(state, changes, changesTurn, { changesTurn = it }, onClose, modifier)
                }
                val toggleOpen: () -> Unit = { if (docked) closePanelToToggle() else sidePanelOpenTarget(SidePanelTab.entries.firstOrNull { it.name == lastPanelTabName }, panelTabs, panelDisabled)?.let(openPanelTab) }
                val trackFocus: (PaneFocus) -> Modifier = { pane ->
                    Modifier.onFocusChanged { if (it.hasFocus) paneFocus = pane else if (paneFocus == pane) paneFocus = PaneFocus.None }
                }
                val detailTitle = if (draft != null) "New thread" else selectedThread?.title
                // Destinations that are not the thread list and conversation; the full-window screen viewer stays full-window even beside a hinge.
                val secondaryHosted = showUsage || machines || browserHosted || (showPorts && portsAvailable) || showScreens
                val fullscreenViewer = showScreens && screenFullscreen && !showUsage && !machines && !browserHosted && !(showPorts && portsAvailable)
                // Keyboard shortcuts (ui/shell/Shortcuts.kt): each calls the function its button calls, under the button's enabled rule; a shortcut whose
                // target is absent or disabled has no binding and does nothing. Allow and Deny of an approval are deliberately not reachable from here.
                val live = state.connection == ConnectionStatus.Connected
                // The threads are on screen in every layout of this destination: both panes, the conversation alone, or a narrow window's list alone.
                val threadsVisible = destination == ShellDestination.Threads && !secondaryHosted && draft == null && live
                val conversationActions = selectedThreadActionsVisible(twoPane, showThread, draft, state.selectedThreadId) && !secondaryHosted
                val railOrDrawer = nav == NavChrome.Rail || nav == NavChrome.Drawer
                val showThreadList: Boolean = twoPane && !secondaryHosted && (listHidden || (nav != NavChrome.Header && policy.listCollapsible))
                val stepThread: (Int) -> Boolean = { step ->
                    val target = if (threadsVisible) adjacentThreadId(listedThreadIds(state.threads), state.selectedThreadId, step) else null
                    if (target != null && target != state.selectedThreadId) { select(target); true } else false
                }
                SideEffect {
                    val bindings = mutableMapOf<ShortcutId, () -> Boolean>()
                    bindings[ShortcutId.ShowShortcuts] = { shortcutsOpen = true; true }
                    // Escape is system Back: the same dispatcher, so the same stack. With nothing enabled the activity would finish, so Escape stays unhandled.
                    bindings[ShortcutId.Dismiss] = { backDispatcher?.takeIf { it.hasEnabledCallbacks() }?.let { it.onBackPressed(); true } ?: false }
                    if (navModel.entries.newThreadEnabled) bindings[ShortcutId.NewThread] = { creatingProject = null; creating = true; true }
                    bindings[ShortcutId.NextThread] = { stepThread(1) }
                    bindings[ShortcutId.PreviousThread] = { stepThread(-1) }
                    if (live) bindings[ShortcutId.NextNeedsYou] = {
                        nextNeedsYouId(needsYouThreads.map { it.id }, openThreadId)?.let { openAttention(it); true } ?: false
                    }
                    if (railOrDrawer) {
                        bindings[ShortcutId.GoThreads] = { navModel.onThreads(); true }
                        if (entries.screens && entries.screensEnabled) shell.onScreens?.let { go -> bindings[ShortcutId.GoScreens] = { go(); true } }
                        if (entries.servers && entries.serversEnabled) shell.onDevServers?.let { go -> bindings[ShortcutId.GoServers] = { go(); true } }
                        if (entries.usage) shell.onUsage?.let { go -> bindings[ShortcutId.GoUsage] = { go(); true } }
                    }
                    if (showThreadList) bindings[ShortcutId.ToggleThreadList] = { if (listHidden) showList() else listState.collapsed = true; true }
                    if (budget.toggle && hasConversation && live && conversationActions) bindings[ShortcutId.ToggleSidePanel] = { toggleOpen(); true }
                    if (roster != null && live && conversationActions) bindings[ShortcutId.OpenAgents] = { openAgents(); true }
                    // Refresh follows the screen in front and the same enabled rule as its refresh button.
                    when {
                        machines || browserHosted || (showPorts && portsAvailable) -> Unit
                        showUsage -> if (usageOfflineNotice(state.connection, state.canReadUsage) == null) bindings[ShortcutId.Refresh] = { onRefreshUsage(); true }
                        showScreens -> if (!screenFullscreen && live && (state.canRead || state.canWrite) && !state.screens.loading) bindings[ShortcutId.Refresh] = { onRefreshScreens(); true }
                        live && !isRefreshing && (twoPane || !showThread) -> bindings[ShortcutId.Refresh] = { onRefresh(); true }
                    }
                    shortcutHost.bindings = bindings
                    shortcutHost.visible = { id -> registeredShortcutVisible(id, listHidden, transient) }
                    shortcutHost.modalOpen = {
                        creating || adding || sshConnecting || sshLogin != null || sshHostKey != null || pairing || shortcutsOpen || showAgents ||
                            (presentation.agentsSheet && roster != null) || !drawerState.isClosed
                    }
                }
                val listChrome = if (nav == NavChrome.Header) ThreadListChrome() else ThreadListChrome(
                    onCollapse = if (policy.listCollapsible) ({ listState.collapsed = true }) else null,
                    onOpenNavigation = if (nav == NavChrome.Drawer) ({ uiScope.launch { drawerState.open() } }) else null,
                    railPresent = policy.rail)
                // A pick from the list beside another destination goes to Threads with that thread, as the rail's Threads entry does.
                val listSelect: (String) -> Unit = if (secondaryHosted) ({ id -> navigate(ShellDestination.Threads); onClosePortBrowser(); select(id) }) else select
                val list: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.statusBarsPadding().then(if (twoPane) Modifier.paneGroup("Thread list", PaneOrder.List).then(trackFocus(PaneFocus.List)) else Modifier)) {
                        conversationState.SaveableStateProvider("thread-list:${state.environment?.id}") {
                            ThreadList(state, listSelect, { creatingProject = null; creating = true }, onThreadAction,
                                selectedId = if (twoPane && draft == null) state.selectedThreadId else null,
                                shell = shell,
                                isRefreshing = isRefreshing, onRefresh = onRefresh,
                                composerDrafts = composerDrafts, onOpenDraft = { creatingProject = it; creating = true }, onDiscardDraft = discardDraft,
                                // Two panes show one banner above both panes (ShellPanes); the phone keeps it in the list.
                                banner = { if (!twoPane) ConnectionBanner(state, onReconnect) }, chrome = listChrome)
                        }
                    }
                }
                val detail: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.statusBarsPadding().windowInsetsPadding(BottomBarsAndIme)
                        .then(if (twoPane) Modifier.paneGroup(detailPaneTitle(destination, detailTitle), PaneOrder.Detail).then(trackFocus(PaneFocus.Conversation)) else Modifier)) {
                        if (!twoPane || split) ConnectionBanner(state, onReconnect)
                        Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalTimelineFoldMemo provides timelineFolds, LocalChangesEntry provides changesEntry) { conversationState.SaveableStateProvider("${state.environment?.id}:${if (draft != null) draftComposerKey(draft) else state.selectedThreadId}") {
                                if (twoPane && draft == null && state.selectedThreadId == null) EmptyDetail(
                                    needsYou = if (state.connection == ConnectionStatus.Connected) needsYouThreads else emptyList(),
                                    newThreadEnabled = navModel.entries.newThreadEnabled, onOpenThread = select, onNewThread = { creatingProject = null; creating = true },
                                    leading = if (listHidden) ({ ShowThreadListButton(otherNeedsYou, showList) }) else null,
                                ) else Conversation(state, onSend, onStop, onRespond, submitMessage = onSubmitMessage, sendOutcomes = sendOutcomes, sendsInFlight = sendsInFlight, onConsumeSendOutcome = onConsumeSendOutcome, stopPendingKeys = stopPendingKeys, queueOperationKeys = queueOperationKeys, onCancelQueued = onCancelQueued, onSteerQueued = onSteerQueued, onResumeQueue = onResumeQueue, onLoadActivity = onLoadActivity, composerDrafts = composerDrafts, promptSuggestions = promptSuggestions, fileDownloadUi = fileDownloadUi,
                                    sideChat = sideChat, threadReferenceInsert = referenceInsert, onThreadReferenceInserted = { referenceInsert = null },
                                    draft = draft, onSetRuntimeMode = onSetRuntimeMode, onSetModel = onSetModel, onSetModelOption = onSetModelOption, onSetModelFavorite = onSetModelFavorite, sendFailed = sendFailed,
                                    onBack = if (backParent != null) ({ select(backParent.id) }) else if (twoPane) null else ({ cancelDraft(); showThread = false }),
                                    onOpenParent = parentThread?.let { p -> { select(p.id) } },
                                    onOpenAgents = if (roster != null) openAgents else null,
                                    onOpenThread = openSideThread,
                                    otherNeedsYou = otherNeedsYou,
                                    subtitle = selectedThread?.takeIf { draft == null && (it.relationshipToParent == "subagent" || it.sideChat) }?.let { t ->
                                        val label = if (t.sideChat) "Side chat" else "Subagent"
                                        parentThread?.let { "$label of ${it.title}" } ?: label
                                    },
                                    actions = {
                                        if (selectedThreadActionsVisible(twoPane, showThread, draft, state.selectedThreadId)) {
                                            if (pill != null && budget.agentsChip) AgentsChip(pill, openAgents)
                                            if (portsAvailable && budget.serversChip) ThreadPortsChip(threadServers, onClick = openServers)
                                            if (budget.toggle) SidePanelToggle(docked, toggleOpen, toggleFocus)
                                            // Chips that do not fit lead the thread menu and do exactly what the chip would do.
                                            val demoted: (@Composable ColumnScope.(() -> Unit) -> Unit)? = if (!budget.menuAgents && !budget.menuServers) null else ({ close ->
                                                if (budget.menuAgents && pill != null) DropdownMenuItem(text = { Text("Agents (${pill.label})") },
                                                    leadingIcon = { Icon(TfIcons.Agents, null, Modifier.size(18.dp)) }, onClick = { close(); openAgents() })
                                                if (budget.menuServers) DropdownMenuItem(text = { Text(serversMenuLabel(threadPorts)) },
                                                    leadingIcon = { Icon(TfIcons.Globe, null, Modifier.size(18.dp)) }, onClick = { close(); openServers() })
                                            })
                                            state.threads.firstOrNull { it.id == state.selectedThreadId }?.let { ThreadActionsMenu(state, it, leadingItems = demoted,
                                                onOpenAgents = if (roster != null && !budget.menuAgents) openAgents else null,
                                                onOpenChanges = if (hasCheckpoints) ({ openChanges(null) }) else null, onAction = { target, operation, value ->
                                                onThreadAction(target, operation, value)
                                                if (settleLeavesConversation(operation, twoPane)) showThread = false
                                            }) }
                                        }
                                    },
                                    seedPrompt = seedPrompt.takeIf { draft != null }, onSeedConsumed = { seedPrompt = null },
                                    // A collapsed list: "Show thread list" first, then the parent arrow of a subagent child.
                                    navigationIcon = if (listHidden) ({ Row(verticalAlignment = Alignment.CenterVertically) {
                                        ShowThreadListButton(otherNeedsYou, showList)
                                        if (backParent != null) ParentBackButton(otherNeedsYou) { select(backParent.id) }
                                    } }) else null)
                            } }
                        }
                    }
                }
                // Every destination but the thread list and the conversation; each fills the modifier it is given.
                val secondary: @Composable (Modifier) -> Unit = { m ->
                    when {
                        showUsage -> key(usageOpenRequest?.first) {
                            UsageScreen(state, usage, if (besideRail) null else ({ navigate(ShellDestination.Threads) }), onRefreshUsage, onUsageRange, onRetryUsage,
                                m.windowInsetsPadding(WindowInsets.systemBars), initialTab = usageTab)
                        }
                        machines -> Column(m.statusBarsPadding()) {
                            ConnectionBanner(state, onReconnect)
                            MachinesScreen(state, savedEnvironments, { navigate(ShellDestination.Threads); onConnect(it) }, onForget, { pairing = true }, { sshConnecting = true },
                                { onDisconnect(); navigate(ShellDestination.Machines); showThread = false },
                                onBack = if (connected && !besideRail) ({ navigate(ShellDestination.Threads) }) else null,
                                modifier = Modifier.weight(1f).windowInsetsPadding(BottomBarsAndIme), probes = machineProbes,
                                onAllowLocalHttp = { environment, allowed -> onAllowLocalNetworkHttp(environment.id, allowed) }, onOpenUsage = shell.onUsage)
                        }
                        // The browser page, or the server list: one surface shared with the side panel's Preview tab.
                        browserHosted || (showPorts && portsAvailable) -> Column(m.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))) {
                            ConnectionBanner(state, onReconnect)
                            devServersSurface(Modifier.weight(1f))
                        }
                        showScreens -> Column(m.then(if (screenFullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)))) {
                            if (!screenFullscreen) ConnectionBanner(state, onReconnect)
                            screensSurface(Modifier.weight(1f))
                        }
                        else -> Unit
                    }
                }
                // A destination beside the rail is the detail pane of the TalkBack order, named after the destination.
                val secondaryModifier = Modifier.fillMaxSize().then(if (twoPane) Modifier.paneGroup(detailPaneTitle(destination, null), PaneOrder.Detail) else Modifier)
                val content: @Composable (Modifier) -> Unit = { modifier -> Box(modifier) { shellContentState.SaveableStateProvider("shell-content") {
                    when {
                        // A separating hinge splits every destination: Threads' start region on the left (a brand panel without a machine), the destination on the right.
                        secondaryHosted && split && !fullscreenViewer -> ShellPanes(policy, panes, listState, banner = {},
                            list = { if (connected) list(Modifier.fillMaxSize()) else BrandPanel(Modifier.fillMaxSize()) }, detail = { secondary(secondaryModifier) })
                        secondaryHosted -> secondary(secondaryModifier)
                        // Narrow windows: Changes is a full-screen route over the conversation, with a back arrow.
                        presentation.changesRoute && panelTab == SidePanelTab.Changes -> Column(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(BottomBarsAndIme)) {
                            ConnectionBanner(state, onReconnect)
                            changesSurface(Modifier.weight(1f), closePanel)
                        }
                        twoPane -> ShellPanes(policy, panes, listState, banner = { ConnectionBanner(state, onReconnect) },
                            list = { list(Modifier.fillMaxSize()) }, detail = { detail(Modifier.fillMaxSize()) }, transient = transient,
                            sidePanel = if (docked && panelTab != null) ({
                                SidePanel(panelTabs, panelTab, panelDisabled, openPanelTab, closePanelToToggle, panelTabsFocus,
                                    Modifier.statusBarsPadding().windowInsetsPadding(BottomBarsAndIme), actions = panelHost.shown, threadLabel = threadTabLabel(inspectorKind)) { tab ->
                                    when (tab) {
                                        SidePanelTab.Preview -> devServersSurface(Modifier.fillMaxSize())
                                        SidePanelTab.Screens -> screensSurface(Modifier.fillMaxSize())
                                        // Opening a child keeps the roster beside it; the tab follows the new thread's roster.
                                        SidePanelTab.Agents -> roster?.let { SidePanelAgents(it, state.providers, state.models, steadyWrite, openSideThread, onStopSubagent, Modifier.fillMaxSize()) }
                                        // The read-only inspector of a subagent or side chat; its header actions are offered to the tab bar while it shows.
                                        SidePanelTab.Thread -> inspectorThreadId?.let { id ->
                                            val addToChat = addToChatReference(state, id)
                                            PanelActionsEffect(panelHost, true, listOfNotNull(PanelAction(InspectorIcons.Maximize, "Maximize thread", true) { maximizeInspector(id) },
                                                addToChat?.let { insert -> PanelAction(TfIcons.Link, "Add to chat", true) { referenceInsert = insert } }))
                                            InspectorThreadPane(state, id, inspectorKind, onRetry = { onOpenInspector(id) }, onMaximize = { maximizeInspector(id) }, onClose = leaveInspector, modifier = Modifier.fillMaxSize())
                                        }
                                        SidePanelTab.Changes -> changesSurface(Modifier.fillMaxSize(), null)
                                    }
                                }
                            }) else null)
                        showThread && (state.selectedThreadId != null || draft != null) -> detail(Modifier.fillMaxSize())
                        else -> list(Modifier.fillMaxSize())
                    }
                } } }
                CompositionLocalProvider(LocalCompactHeight provides policy.compactHeight) {
                    when (nav) {
                        // The rail takes the start, top and bottom insets; content beside it must not apply the start side again.
                        NavChrome.Rail -> Row(Modifier.fillMaxSize()) {
                            ShellNavigationRail(navModel)
                            content(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(PaddingValues(start = panes.rail.dp))
                                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)))
                        }
                        NavChrome.Drawer -> ModalNavigationDrawer(drawerContent = { ShellDrawerSheet(navModel, drawerState) { uiScope.launch { drawerState.close() } } },
                            drawerState = drawerState, scrimColor = c.scrim) {
                            // A split window keeps the hinge at its window position: no start inset may shift the panes.
                            content(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(if (split) WindowInsetsSides.End else WindowInsetsSides.Horizontal)))
                        }
                        NavChrome.Header -> content(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(if (split) WindowInsetsSides.End else WindowInsetsSides.Horizontal)))
                    }
                }
                // Beside a hinge the snackbar belongs to the region of the destination, not to the window centre.
                val snackbarRegion = when {
                    !split -> PaddingValues()
                    panes.hingeAt == HingeAt.BeforeSidePanel -> PaddingValues(start = panes.rail.dp, end = (panes.hinge + panes.sidePanel).dp)
                    else -> PaddingValues(start = (panes.rail + panes.list + panes.hinge).dp)
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(snackbarRegion), contentAlignment = Alignment.BottomCenter) {
                    SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding().onGloballyPositioned { snackbarHostBounds = it.boundsInRoot() })
                }
                // The agents sheet: opened from the header where no panel can dock, or an Agents tab shown in a window too narrow to dock it.
                if ((showAgents || presentation.agentsSheet) && roster != null) ThreadAgentsSheet(roster, state.providers, state.models, steadyWrite,
                    { showAgents = false; if (presentation.agentsSheet) closePanel() }, { id -> showAgents = false; if (presentation.agentsSheet) closePanel(); select(id) }, onStopSubagent)
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
                if (choosingEnvironment) navigate(ShellDestination.Threads)
                showThread = false
                onSshConnect(it)
            }
        }
        if (sshHostKey != null) SshHostKeyDialog(sshHostKey, onTrustSsh, onCancelSsh)
        if (pairing && !sshConnecting && sshLogin == null && sshHostKey == null) key(pairLink?.first) {
            PairDialog(state.busy, { pairing = false; submittedPair = false; onCancelSsh() }, connectionAttempt.takeIf { it.kind == ConnectionKind.Pairing } ?: ConnectionAttempt(), pairLink?.second.orEmpty()) { url, name, insecure -> submittedPair = true; onPair(url, name, insecure) }
        }
        if (shortcutsOpen) KeyboardShortcutsDialog { shortcutsOpen = false }
        if (creating) NewThreadSheet(state, { creating = false }, { newDraft, prompt ->
            seedPrompt = prompt.trim().takeIf { it.isNotEmpty() }
            onStartDraft(newDraft); creating = false; showThread = true
        }, composerDrafts, creatingProject, onSetModelFavorite)
    }
}

/** Header entry point for the Agents sheet: people icon plus the pill label, for example "2/5". */
@Composable
internal fun AgentsChip(pill: SubagentPill, onClick: () -> Unit) {
    val c = Tf.colors
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text,
        modifier = Modifier.padding(end = 2.dp).focusRing(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).handPointer().semantics { contentDescription = pill.accessibilityLabel; role = Role.Button }) {
        Row(Modifier.height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(TfIcons.Agents, null, Modifier.size(16.dp))
            Text(pill.label, style = TrefoilType.meta.copy(color = c.text), maxLines = 1)
        }
    }
}
