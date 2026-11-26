package com.tajchert.trefoil

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.ComposerSubmission
import com.tajchert.trefoil.ui.SendOutcome
import com.tajchert.trefoil.ui.SideChatResult
import com.tajchert.trefoil.ui.SideChatState
import com.tajchert.trefoil.ui.QueueOperationGuard
import com.tajchert.trefoil.ui.StopGuard
import com.tajchert.trefoil.ui.pr.PullRequestSender
import com.tajchert.trefoil.ui.queueSignature
import com.tajchert.trefoil.ui.stopKey
import com.tajchert.trefoil.core.session.EnvironmentProbe
import com.tajchert.trefoil.core.session.EnvironmentTokenRenewer
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.core.session.CachedShell
import com.tajchert.trefoil.core.session.ShellCacheStore
import com.tajchert.trefoil.core.session.ShellCacheTicket
import com.tajchert.trefoil.core.session.ShellCacheWriter
import com.tajchert.trefoil.core.session.withTokenOf
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftDiscard
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.core.session.ComposerDraftStore
import com.tajchert.trefoil.core.session.IssueLinkNotice
import com.tajchert.trefoil.core.session.ThreadDraftController
import com.tajchert.trefoil.core.session.UNCERTAIN_MESSAGE
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshTunnel
import com.tajchert.trefoil.core.ssh.SshTunnelConnector
import com.tajchert.trefoil.keepalive.ConnectionKeepAlive
import com.tajchert.trefoil.notifications.AgentAlertDriver
import com.tajchert.trefoil.notifications.AgentAlertNotifier
import com.tajchert.trefoil.notifications.OpenThreadRequest
import com.tajchert.trefoil.notifications.shellFresh
import com.tajchert.trefoil.security.DeviceDpopKey
import com.tajchert.trefoil.security.ComposerDraftFileStore
import com.tajchert.trefoil.security.EnvironmentStore
import com.tajchert.trefoil.security.EnvironmentWrites
import com.tajchert.trefoil.security.EnvironmentVault
import com.tajchert.trefoil.security.EncryptedShellCacheStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

internal const val BACKGROUND_CONNECTION_GRACE_MS = 60_000L
internal const val RETAINED_CONNECTION_FRESHNESS_MS = 20_000L
/** A resume re-reads the thread list only after at least this long in the background (not rotation or a quick switch). */
internal const val RESUME_SHELL_REFRESH_MIN_BACKGROUND_MS = 30_000L
/** Waits before each automatic reconnect attempt after an unexpected drop; one episode per drop. */
internal val AUTO_RECONNECT_BACKOFF_MS = listOf(2_000L, 5_000L, 15_000L)
/** After a default-network change, the live connection must answer a ping within this or it is dropped and reconnected. */
internal const val LINK_CHECK_TIMEOUT_MS = 4_000L
/** Most dismissed prompt suggestion keys kept in memory; the oldest go first. */
internal const val MAX_DISMISSED_SUGGESTIONS = 200

class TrefoilViewModel internal constructor(
    application: Application,
    private val vault: EnvironmentStore,
    /** Process-wide ([AppHttp]): the client, the renewer and the probe share its connection pool. */
    http: OkHttpClient = AppHttp.client,
    /** Encrypted on-device shell rows per machine; replaced only by tests. */
    private val shellCache: ShellCacheStore = EncryptedShellCacheStore.get(application),
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, EnvironmentVault(application))
    /** One renewer for the client and the probe, so DPoP renewals of a machine are serialized and land in the vault. */
    private val tokens = EnvironmentTokenRenewer(DeviceDpopKey::loadOrCreate, http = http, storeRenewed = { renewed, replaces -> saveRenewedToken(renewed, replaces) })
    private val client = T3Client(viewModelScope, tokens = tokens, http = http)
    /** Shell re-reads (pull-to-refresh, resume catch-up) go through this; replaced only by tests. */
    internal var shellReads: TrefoilClient = client
    val usageController = UsageController(viewModelScope, { client }, onReport = {
        try { widgetSync.report(it) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Widget storage must not fail app reads. */ }
    })
    val usage = usageController.state
    init {
        viewModelScope.launch {
            client.state.map { it.environment?.id }.distinctUntilChanged().collect { usageController.reset() }
        }
    }
    /** Issue list and detail (memory only). Both reset with the environment; `syncLive` follows the connection and the capabilities. */
    val issuesController = IssuesController(viewModelScope, { client }, { client.state.value })
    val issueDetailController = IssueDetailController(viewModelScope, { client }, { client.state.value })
    val issues = issuesController.state
    val issueDetail = issueDetailController.state
    /** Link and unlink of the selected thread, sent once each (never replayed); the notice carries a rejection or an uncertain outcome. */
    private val issueLinkController = IssueLinkController(viewModelScope, { client }, { _notice.value = it },
        { issueDetailController.refreshLinkedThreads() }, track = { block -> mutationsInFlight.tracked { block() } })
    val issueLinkPending = issueLinkController.pending
    /** Start thread from an issue: prefills the project's new-thread draft and carries the link into the draft (see [IssueStartController]). */
    private val issueStartController = IssueStartController(viewModelScope, { client.state.value },
        { key -> composerDraftStore.get(key)?.text }, { key, text -> composerDraftStore.updateText(key, text) }, { composerDraftStore.awaitLoaded() })
    val issueStart = issueStartController.request
    fun startThreadFromIssue(environmentId: String?, summary: IssueSummary?) = issueStartController.start(environmentId, summary)
    fun replaceIssueStartDraft() = issueStartController.replace()
    fun keepIssueStartDraft() = issueStartController.keep()
    fun cancelIssueStart() = issueStartController.cancel()
    fun linkIssue(link: ThreadIssueLink) = issueLinkController.link(link)
    fun unlinkIssue(key: IssueKey) = issueLinkController.unlink(key)
    init {
        viewModelScope.launch {
            client.state.map { it.environment?.id }.distinctUntilChanged().collect { issuesController.reset(); issueDetailController.reset(); issueStartController.cancel() }
        }
        viewModelScope.launch {
            client.state.map { Triple(it.connection, it.issuesSupported, it.issueLinkedThreadsSupported) }.distinctUntilChanged().collect { (connection, issues, _) ->
                // The same machine coming back without issue support: nothing held for it may be shown or reused.
                if (connection == ConnectionStatus.Connected && !issues) { issuesController.reset(); issueDetailController.reset() }
                issuesController.syncLive(); issueDetailController.syncLive()
            }
        }
    }
    /** Selects a thread linked from an issue, only inside its own environment; false when it is gone or cannot be opened. */
    suspend fun openIssueThread(environmentId: String, threadId: String): Boolean {
        dropPendingOpen()
        clearDraft()
        return try { client.selectThreadIn(environmentId, threadId) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }
    val screenSessions = com.tajchert.trefoil.ui.screens.ScreenSessionCoordinator(viewModelScope, client)
    val fileDownloads = com.tajchert.trefoil.downloads.FileDownloadCoordinator(viewModelScope, client,
        com.tajchert.trefoil.downloads.MediaStoreDownloadStore(application))
    private val connector = SshTunnelConnector()
    private val vaultMutex = Mutex()
    private val connectionMutex = Mutex()
    val state = client.state
    private val _environments = MutableStateFlow<List<SavedEnvironment>>(emptyList())
    val environments = _environments.asStateFlow()
    private val widgetSync = com.tajchert.trefoil.widget.UsageWidgetSync(application, viewModelScope, client, environments)
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    /** Dev-server forwards and the in-app tunnel browser; failures surface as [notice] like other actions. */
    /** Elapsed real time including deep sleep, for the cap on forwards held for an external browser; replaced in tests. */
    internal var forwardClock: () -> Long = android.os.SystemClock::elapsedRealtime
    val portForwards = com.tajchert.trefoil.ui.ports.PortForwardCoordinator(viewModelScope, client,
        onExternalForwardsChanged = { checkKeepAlive() }, clock = { forwardClock() }) { _notice.value = it }
    /**
     * The device has no app that could open a forwarded dev server. Nothing new is held; a hold from an earlier open of
     * the same forward stays as it was, since that listener and its tab still work.
     */
    fun externalOpenFailed() { _notice.value = "No browser found to open the dev server." }
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    /** True while a [refresh] the user asked for runs (pull-to-refresh indicator); silent resume refreshes never set it. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _connectionAttempt = MutableStateFlow(ConnectionAttempt())
    val connectionAttempt = _connectionAttempt.asStateFlow()
    /** Mutations started here or by [drafts] that have not settled; a route switch waits for zero. */
    private val mutationsInFlight = AtomicInteger()
    /** Process-wide: its load and final write outlive this view model, and every screen shares one map and write queue. */
    private val composerDraftStore = ComposerDraftFileStore.get(application)
    /** Local unsent composer text with content, per thread reply and per (environment, project) new thread; never synced. */
    val composerDrafts: StateFlow<Map<ComposerDraftKey, ComposerDraft>> = composerDraftStore.drafts
    /** False until the stored drafts are loaded; editors must not write before. */
    val composerDraftsLoaded: StateFlow<Boolean> = composerDraftStore.loaded
    /** NewThread drafts, including settings-only ones; unchanged while only replies are typed. */
    val newThreadComposerDrafts: StateFlow<Map<ComposerDraftKey, ComposerDraft>> = composerDraftStore.records
        .map { all -> all.filterKeys { it is ComposerDraftKey.NewThread } }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** Reply drafts with content reduced to their first line (the thread-row preview); unchanged while typing past it. */
    val replyDraftPreviews: StateFlow<Map<ComposerDraftKey, ComposerDraft>> = composerDraftStore.drafts
        .map { all -> all.filterKeys { it is ComposerDraftKey.Reply }.mapValues { (_, d) -> d.copy(text = assistantCitationsToPlainText(d.text).lineSequence().map { it.trim() }.first { it.isNotEmpty() }, updatedAtMillis = 0) } }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** The client every composer send goes through: it counts the mutation and clears the accepted reply's draft. */
    private val composerClient = ComposerDraftSettlingClient(MutationTrackingClient(client, mutationsInFlight), composerDraftStore)
    private val drafts = ThreadDraftController(viewModelScope, composerClient) { _notice.value = it }
    private val _sendOutcomes = MutableStateFlow<Map<String, SendOutcome>>(emptyMap())
    /**
     * Outcome of every composer submission on an existing thread, by submission id. Kept until the composer that made it
     * consumes it, so a composer that was closed meanwhile (and restored later) still settles its own send, and only that one.
     */
    internal val sendOutcomes: StateFlow<Map<String, SendOutcome>> = _sendOutcomes.asStateFlow()
    internal fun consumeSendOutcome(id: String) = _sendOutcomes.update { it - id }
    private val _sendsInFlight = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Submissions of this process still waiting for their outcome. A composer restored after process death holds an id
     * that is in neither this set nor [sendOutcomes]: that send is lost, and the composer settles it as failed.
     */
    internal val sendsInFlight: StateFlow<Set<String>> = _sendsInFlight.asStateFlow()
    private val _dismissedPromptSuggestions = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Prompt suggestions dismissed in this process, as [promptSuggestionKey] (environment and run). Kept here so a dismissal survives
     * leaving and reopening the thread; in memory only, since a suggestion is not persisted by the server either.
     */
    internal val dismissedPromptSuggestions: StateFlow<Set<String>> = _dismissedPromptSuggestions.asStateFlow()
    internal fun dismissPromptSuggestion(key: String) = _dismissedPromptSuggestions.update { (it + key).toList().takeLast(MAX_DISMISSED_SUGGESTIONS).toSet() }
    private val stopGuard = StopGuard(android.os.SystemClock::elapsedRealtime)
    /** Threads (see [stopKey]) whose Stop is in flight; each is held until the thread has nothing left to stop, or 10 s. */
    internal val stopPending: StateFlow<Set<String>> = stopGuard.pending
    private val queueGuard = QueueOperationGuard(StopGuard(android.os.SystemClock::elapsedRealtime))
    /** Threads (see [stopKey]) with a queue operation in flight; the queue strip disables its controls for them. */
    internal val queueOperationPending: StateFlow<Set<String>> = queueGuard.pending
    /** Unsent new conversation; while non-null the UI shows the draft instead of the selected thread. */
    val draft: StateFlow<ThreadDraft?> = drafts.draft
    /** "Thread started; the issue was not linked": its action sends one new explicit link, never an automatic resend. */
    val issueLinkNotice: StateFlow<IssueLinkNotice?> = drafts.issueLinkNotice
    fun retryIssueLink() = drafts.retryIssueLink()
    fun dismissIssueLinkNotice() = drafts.dismissIssueLinkNotice()
    /** Emits once for every send (draft or thread) that did not deliver; the composer must leave its pending state. */
    val sendFailed: SharedFlow<Unit> = drafts.sendFailed
    init {
        // Stop is done holding once the selected thread has nothing left to stop.
        viewModelScope.launch {
            client.state.collect {
                val key = stopKey(it.environment?.id, it.selectedThreadId)
                if (!it.canStop) stopGuard.release(key)
                queueGuard.onState(key) { it.queueSignature() }
            }
        }
        // A launch whose thread a connected snapshot lists is settled, also one restored from an earlier session. Only the
        // live shell counts (see launchEvidence): cached or retained rows may still list a thread the server has since deleted.
        viewModelScope.launch {
            client.state.map(::launchEvidence)
                .distinctUntilChanged()
                .combine(composerDraftStore.drafts) { snapshot, _ -> snapshot }
                .collect { snapshot -> snapshot?.let { (environmentId, threadIds) -> composerDraftStore.reconcileLaunches(environmentId, threadIds) } }
        }
    }
    private val _sshHostKey = MutableStateFlow<SshHostKey?>(null)
    val sshHostKey = _sshHostKey.asStateFlow()
    private val _sshLogin = MutableStateFlow<SshConnectionInput?>(null)
    val sshLogin = _sshLogin.asStateFlow()
    private var hostKeys = emptyList<SshHostKey>()
    private var pendingInput: SshConnectionInput? = null
    private var tunnel: SshTunnel? = null
    private var tunnelMonitor: Job? = null
    private var connectionJob: Job? = null
    private var backgroundDisconnectJob: Job? = null
    private var selectedEnvironment: SavedEnvironment? = null
    private var resumeEnvironment: SavedEnvironment? = null
    private val foregroundState = MutableStateFlow(true)
    /** Between [resume] and [pause]; a flow too, so a held notification tap can wait for it. */
    private var foreground: Boolean
        get() = foregroundState.value
        set(value) { foregroundState.value = value }
    /** The machine and message of the app-wide banner last posted for lost forwards, so clearing the notice clears it. */
    private var forwardBanner: Pair<String, String>? = null
    /** A connection failure took the banner from [forwardBanner] before the user acted on it; the next attempt restores it. */
    private var forwardBannerDisplaced = false
    /** [forwardBanner] was restored once already; a later failure replacing it consumes it, so it never repeats. */
    private var forwardBannerRestored = false
    /** `SystemClock.elapsedRealtime()` of the last [pause]; null until the first one. */
    private var backgroundedAt: Long? = null
    private var silentShellRefresh: Job? = null
    private var connectionEpoch = 0L
    var sshFilePickerActive = false
    private val prefs = application.getSharedPreferences("trefoil.ui", android.content.Context.MODE_PRIVATE)
    private val _keepConnected = MutableStateFlow(prefs.getBoolean(KEEP_CONNECTED, false))
    /** Opt-in: a foreground service keeps a live connection open (and auto-reconnect working) while in the background. */
    val keepConnected: StateFlow<Boolean> = _keepConnected.asStateFlow()
    private val _hideTestFiles = MutableStateFlow(prefs.getBoolean(HIDE_TEST_FILES, false))
    /** Device-local: the Changes screen leaves test files out of the file list and the diff. */
    val hideTestFiles: StateFlow<Boolean> = _hideTestFiles.asStateFlow()
    private val _agentNotifications = MutableStateFlow(prefs.getBoolean(AGENT_NOTIFICATIONS, true))
    /** Local notifications about agent activity while the app is in the background and connected; on by default. */
    val agentNotifications: StateFlow<Boolean> = _agentNotifications.asStateFlow()
    /** Posts those notifications; takes the foreground and shown-thread facts from the lifecycle hooks below. */
    private val agentAlerts = AgentAlertDriver(viewModelScope, client.state, AgentAlertNotifier(application), _agentNotifications)
    /** The notification tap still waiting for its machine and thread; lives here so cold start and rotation keep it. */
    private var pendingOpen: OpenThreadRequest? = null
    private var pendingOpenJob: Job? = null
    private val _openedThread = MutableStateFlow<OpenThreadRequest?>(null)
    /** A thread a notification tap selected; the UI brings it on screen once and then calls [consumeOpenedThread]. */
    val openedThread: StateFlow<OpenThreadRequest?> = _openedThread.asStateFlow()
    /** Drives the keep-alive service; replaced only by tests. */
    internal var keepAliveControl: KeepAliveControl = ServiceKeepAliveControl
        set(value) { field = value; watchKeepAliveService() }
    private var keepAliveWatch: Job? = null
    private val _keepAliveActive = MutableStateFlow(false)
    /** The keep-alive service runs (its start was accepted); the background then keeps the connection like the foreground. */
    val keepAliveActive: StateFlow<Boolean> = _keepAliveActive.asStateFlow()
    private val keepAlive get() = _keepAliveActive.value
    /** What the notification shows; it is only updated when one of them changes. */
    private var keepAliveMachine: String? = null
    private var keepAliveReconnecting = false
    /** A refused start (or a service that ended on its own) counts as the setting being off until the connection ends or the setting changes. */
    private var keepAliveRefused = false
    private var keepAliveCheckPosted = false
    /** Set by an explicit disconnect or forgetting the live machine; until the next connection action nothing starts the service. */
    private var keepAliveLatched = false
    private val environmentProbe = EnvironmentProbe(http = http, tokens = tokens)
    private val prober = MachineProber(
        // Adapter over the single-route probe; EnvironmentProbe's multi-route probe has the same contract.
        direct = { environment, routes -> probeRoutes(routes) { environmentProbe.probe(environment, it.baseUrl) } },
        openSsh = { input, pinned -> connector.open(input, pinned).let { opened -> object : ProbeTunnel {
            override val baseUrl = opened.baseUrl
            override fun close() = opened.close()
        } } },
        // Filtered as a whole, like the connect's plan, so no group gets back a route the connect leaves out.
        plan = { usableRoutes(it.candidateRoutes(), cellularOnly) },
    )
    private val _machineProbes = MutableStateFlow<Map<String, MachineProbe>>(emptyMap())
    /** Reachability of saved machines other than the active connection; never mutates a server. */
    val machineProbes = _machineProbes.asStateFlow()
    private var probeJob: Job? = null
    /** Auto-connect is considered once per app launch, until a decision is made or the user acts. */
    private var autoConnectPending = true
    /**
     * The [connectionEpoch] of the cold-start connect while it is still quiet (see [startConnect]); a tap on its machine
     * makes it the user's attempt (null) before it ends.
     */
    private var quietAttemptEpoch: Long? = null
    /** The quiet attempt of this epoch failed as unreachable. */
    private var quietFailedEpoch: Long? = null
    /** Probes one saved machine; replaced only by tests. */
    internal var machineProbe: suspend (SavedEnvironment, List<SshHostKey>) -> MachineProbe = { environment, keys -> prober.probe(environment, keys) }
    /**
     * The machine of the last successful connection attempt. Armed only by a successful connection and disarmed by any
     * disconnect this view model makes, so a later drop to Error/Disconnected is unexpected and reconnects once.
     */
    private var liveEnvironment: SavedEnvironment? = null
    /** The saved machine the latest [connect] attempt targets; null for pairing, plain SSH and other attempts. */
    private var attemptEnvironmentId: String? = null
    private var autoReconnectJob: Job? = null
        // invokeOnCompletion runs on the completing thread; this job lives on viewModelScope (Main), so the posted check
        // and its non-atomic flag stay main-thread confined.
        set(value) { field = value; value?.invokeOnCompletion { postKeepAliveCheck() }; postKeepAliveCheck() }
    /** The machine of the current auto-reconnect episode; read only while [autoReconnectJob] is active. */
    private var autoReconnectEnvironment: SavedEnvironment? = null
    /** The active episode only waits out its backoff: no attempt of it is running. */
    private var autoReconnectWaiting = false
    /** The [connectionEpoch] the active episode expects; once a user action moves the epoch on, the episode is obsolete. */
    private var autoReconnectEpoch = 0L
    /** A drop while offline reconnects when a default network appears. */
    private var reconnectWhenOnline: SavedEnvironment? = null
        set(value) { field = value; postKeepAliveCheck() }
    private var defaultNetwork: Long? = null
    private var networkKnown = false
    private var networkAvailable = true
    /** The default network is plain cellular (see [usableRoutes]); null until its capabilities are known. */
    private var cellularOnly: Boolean? = null
    private var linkCheckJob: Job? = null
    /** The [connectionEpoch] of a network change: a drop in that epoch reconnects without the first backoff. */
    private var networkChangeEpoch: Long? = null
    /** Route hints for a machine that is not saved yet (SSH pairing saves after connecting); applied when it is saved. */
    private val unsavedHints = mutableMapOf<String, List<RouteHints>>()
    /** The better-route watch's credential-free check; replaced only by tests. */
    internal var routePreflight: suspend (String, EnvironmentRoute, Boolean, Long) -> Boolean =
        { id, route, insecure, timeout -> client.preflight(id, route, insecure, timeout) }
    private val routeMonitor = RouteMonitor(viewModelScope,
        preflight = { id, route, insecure, timeout -> routePreflight(id, route, insecure, timeout) },
        canSwitch = ::idleForRouteSwitch,
        onBetterRoute = ::switchRoute,
    )

    /** Every machine write goes through this; see [EnvironmentWrites] for what a failed read may do. */
    private val environmentWrites = EnvironmentWrites(vault)
    /** The vault was read in this process; only then may cache entries of machines it does not list be removed. */
    private var vaultRead = false
    private val initialized = viewModelScope.async {
        try {
            vaultMutex.withLock {
                _environments.value = withContext(Dispatchers.IO) { environmentWrites.load() }
                vaultRead = true
                hostKeys = withContext(Dispatchers.IO) { vault.readHostKeys() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _notice.value = "Saved credentials could not be unlocked. Pair this device again." }
    }

    /**
     * Writes a machine's last synchronized shell checkpoint ([T3Client.shellCheckpointed]) to [shellCache], debounced and
     * off the main thread; only saved machines are written. Its scope outlives this model, so the flush in [onCleared]
     * completes; the store's process-wide ticket order keeps that late flush from undoing a successor's forget.
     */
    private val shellCacheWriter = ShellCacheWriter(shellCache, CoroutineScope(SupervisorJob() + Dispatchers.IO), snapshot = { id ->
        if (_environments.value.any { it.id == id }) client.shellCacheEntry(id) else null
    })
    /** Machines whose cache entry was read (or forgotten) in this process: read at most once, never after a forget. Main thread. */
    private val shellCacheRead = mutableSetOf<String>()
    /** Machines whose cache read is running. Main thread. */
    private val shellCacheReading = mutableSetOf<String>()

    init {
        // The cold start shows the last-used machine's cached rows as soon as they and the vault are loaded; nothing waits
        // for the file (see [readShellCacheInBackground]).
        prefs.getString(LAST_ENVIRONMENT, null)?.let(::readShellCacheInBackground)
    }

    init {
        viewModelScope.launch {
            initialized.await()
            if (vaultRead) shellCacheWriter.retainOnly(_environments.value.mapTo(HashSet()) { it.id })
        }
        // Only synchronized shell state is cached: a checkpoint schedules a write, and a dropped connection writes the
        // last checkpoint at once (rows a catch-up applied before its marker never reach the disk).
        viewModelScope.launch { client.shellCheckpointed.collect(shellCacheWriter::changed) }
        viewModelScope.launch {
            client.state.map { it.environment?.id to it.connection }.distinctUntilChanged().collect { (id, connection) ->
                if (id != null && (connection == ConnectionStatus.Disconnected || connection == ConnectionStatus.Error)) shellCacheWriter.flush(id)
            }
        }
        viewModelScope.launch { client.routeHints.collect { hints -> learnRoutes(hints) } }
        viewModelScope.launch {
            combine(client.state.map { Triple(it.connection, it.environment?.id, it.activeRoute) }.distinctUntilChanged(), _environments) { _, _ -> }
                .collect { refreshRouteMonitor() }
        }
        viewModelScope.launch {
            client.state.map { it.connection to it.environment?.id }.distinctUntilChanged().collect { (connection, id) ->
                if (connection == ConnectionStatus.Error || connection == ConnectionStatus.Disconnected) connectionDropped(id)
            }
        }
        viewModelScope.launch {
            combine(client.state.map { it.connection to it.environment?.name }.distinctUntilChanged(), _keepConnected, _working) { _, _, _ -> }
                .collect { postKeepAliveCheck() }
        }
        viewModelScope.launch { ConnectionKeepAlive.disconnectRequests.collect { disconnect() } }
        viewModelScope.launch { portForwards.stoppedNotices.collect(::syncForwardBanner) }
        watchKeepAliveService()
    }

    /** The service ending on its own (startForeground refused, timeout) counts as a refused start for this connection. */
    private fun watchKeepAliveService() {
        keepAliveWatch?.cancel()
        val control = keepAliveControl
        keepAliveWatch = viewModelScope.launch {
            combine(control.running, control.starting) { _, _ -> }.collect {
                if (keepAlive && !control.running.value && !control.starting.value) { keepAliveRefused = true; stopKeepAlive(releaseBackground = true) }
            }
        }
    }

    private fun action(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _notice.value = null
            try { block() }
            catch (e: CancellationException) {
                // A stray cancellation (e.g. an inner timeout) while this job is alive is a failure, never silence.
                coroutineContext.ensureActive(); _notice.value = failure
            }
            catch (_: Exception) { _notice.value = failure }
        }
    }

    /** A mutation the user started; counted so a route switch never abandons it. */
    private fun mutation(failure: String, block: suspend () -> Unit) = action(failure) { mutationsInFlight.tracked { block() } }

    private fun connectionAction(failure: String, kind: ConnectionKind = ConnectionKind.Saved, block: suspend () -> Unit): Job {
        backgroundDisconnectJob?.cancel(); backgroundDisconnectJob = null
        reconnectWhenOnline = null
        keepAliveLatched = false
        val epoch = ++connectionEpoch
        attemptEnvironmentId = null
        autoConnectPending = false
        connectionJob?.cancel()
        _sshHostKey.value = null
        if (kind != ConnectionKind.Ssh) _sshLogin.value = null
        pendingInput = null
        // A lost-forward banner a failure replaced unseen comes back; one on screen now is consumed by this attempt.
        _notice.value = connectionNotice(); _working.value = true
        _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Testing, "Testing connection…")
        connectionJob = viewModelScope.launch {
            try {
                initialized.await()
                connectionMutex.withLock {
                    if (!(foreground || keepAlive) || epoch != connectionEpoch) return@withLock
                    try {
                        block()
                        if (epoch == connectionEpoch) {
                            _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Succeeded, "Connected")
                            _sshLogin.value = null
                            if (state.value.connection == ConnectionStatus.Connected) liveEnvironment = selectedEnvironment ?: state.value.environment?.forStorage()
                            (selectedEnvironment ?: state.value.environment)?.id?.let { id ->
                                prefs.edit().putString(LAST_ENVIRONMENT, id).apply()
                                _machineProbes.update { it + (id to MachineProbe.Online) }
                            }
                        }
                    } catch (e: CancellationException) {
                        coroutineContext.ensureActive()
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, failure) }
                    } catch (e: SshHostKeyException) {
                        if (epoch == connectionEpoch) {
                            if (e.changed) {
                                pendingInput = null
                                connectionFailed(epoch, kind, "SSH host key changed for ${e.presented.host}:${e.presented.port}. Connection blocked. Verify the server key independently before changing your saved trust.")
                            } else if (!foreground) {
                                // A background (keep-alive) attempt never raises the trust prompt; it fails like any other attempt.
                                pendingInput = null
                                connectionFailed(epoch, kind, "Confirm the SSH host fingerprint in Trefoil to reconnect.")
                            } else {
                                _sshHostKey.value = e.presented
                                _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.AwaitingTrust, "Confirm the SSH host fingerprint to continue.")
                            }
                        }
                    } catch (e: SshLoginRequired) {
                        // Every route before it failed and SSH is the last option: ask for the login, like a plain SSH connect.
                        if (epoch == connectionEpoch) { pendingInput = null; _connectionAttempt.value = ConnectionAttempt(); _sshLogin.value = e.input }
                    } catch (e: QuietStartupFailure) {
                        if (epoch == connectionEpoch) { pendingInput = null; quietStartupFailed(epoch, e.environmentId) }
                    } catch (e: SshConnectionException) {
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, e.message ?: failure) }
                    } catch (e: PairingException) {
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, e.message ?: failure) }
                    } catch (_: Exception) {
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, failure) }
                    }
                }
            } finally { if (epoch == connectionEpoch) _working.value = false }
        }
        return connectionJob!!
    }

    private fun connectionFailed(epoch: Long, kind: ConnectionKind, message: String) {
        showConnectionFailure(message)
        _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Failed, message)
    }

    /**
     * The cold-start connect found its machine unreachable: no notice, and the failed selection is cleared so the machine
     * list (with that machine's probe) is shown instead of its empty thread screen.
     */
    private fun quietStartupFailed(epoch: Long, environmentId: String) {
        quietFailedEpoch = epoch
        _machineProbes.update { it + (environmentId to MachineProbe.Unreachable) }
        // Deselected, not forgotten: the machine's retained rows, resume cursor and cache entry stay for the next attempt.
        if (state.value.environment?.id == environmentId && state.value.connection != ConnectionStatus.Connected) client.deselectEnvironment(environmentId)
        if (selectedEnvironment?.id == environmentId) selectedEnvironment = null
        if (attemptEnvironmentId == environmentId) attemptEnvironmentId = null
        _connectionAttempt.value = ConnectionAttempt()
    }

    private fun connectionStage(message: String) {
        _connectionAttempt.update { it.copy(phase = ConnectionPhase.Testing, message = message) }
    }

    /**
     * Applies [change] to what the vault holds now (see [EnvironmentWrites]), so a write never brings back an older copy
     * such as a token the widget renewed since this model read the vault. [_environments] then shows what was stored.
     * Call under [vaultMutex] after [initialized]; [change] must not block. [newPairing] marks a write that may replace a
     * vault never readable in this process.
     */
    private suspend fun save(newPairing: Boolean = false, change: (List<SavedEnvironment>) -> List<SavedEnvironment>) {
        val hints = unsavedHints.toMap()
        val stored = withContext(Dispatchers.IO) {
            environmentWrites.write(newPairing) { current ->
                change(current).map { environment -> hints[environment.id]?.fold(environment) { e, h -> e.withRouteHints(h) } ?: environment }
                    .map { it.forStorage() }
            }
        }
        stored.forEach { unsavedHints.remove(it.id) }
        val removed = _environments.value.any { previous -> stored.none { it.id == previous.id } }
        _environments.value = stored
        // A forgotten machine, or a new pairing over a vault never readable here: its rows must not stay on the device.
        if (removed || newPairing) shellCacheWriter.retainOnly(stored.mapTo(HashSet()) { it.id })
    }

    private suspend fun readShellCache(environmentId: String): CachedShell? =
        try { shellCache.read(environmentId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /**
     * Restores [cached] rows of [environmentId] into the client (never live: no reads or writes until the connection's
     * `synchronized` marker), once per machine per process, only while it is saved, never after it was forgotten (here,
     * or in the store since [ticket]) and only while its connection has not subscribed the shell ([T3Client.restoreShell]).
     */
    private fun restoreShellCache(environmentId: String, cached: CachedShell?, ticket: ShellCacheTicket) {
        if (!shellCacheRead.add(environmentId) || cached == null || _environments.value.none { it.id == environmentId }) return
        if (!shellCache.valid(environmentId, ticket)) return
        // Marked first: these rows are what the store holds, whether or not the client still takes them.
        shellCacheWriter.markWritten(cached)
        client.restoreShell(cached)
    }

    /**
     * Reads [environmentId]'s cached rows unless this process already did (or holds newer rows). Never awaited: a stalled
     * file or Keystore call must not hold up a connection, so the rows show only if they arrive before it subscribes the shell.
     */
    private fun readShellCacheInBackground(environmentId: String) {
        if (environmentId in shellCacheRead || !shellCacheReading.add(environmentId)) return
        // Taken before the read: a forget while it runs invalidates what it returns.
        val ticket = shellCache.ticket(environmentId)
        viewModelScope.launch {
            try {
                val cached = readShellCache(environmentId)
                initialized.await()
                restoreShellCache(environmentId, cached, ticket)
            } finally { shellCacheReading.remove(environmentId) }
        }
    }

    /**
     * Stores a renewed DPoP token over the same session's older token only; a newer pairing's token is never replaced.
     * Returns whether the vault holds [renewed]'s token afterwards, read back from it rather than taken from what was
     * written (a write can return without committing); false keeps the renewer's chain for the next store.
     */
    internal suspend fun saveRenewedToken(renewed: SavedEnvironment, replaces: Set<String>): Boolean {
        initialized.await()
        return vaultMutex.withLock {
            save { saved -> saved.map { if (it.id == renewed.id && it.accessToken in replaces) it.withTokenOf(renewed) else it } }
            withContext(Dispatchers.IO) { vault.locked { vault.read() } }.any { it.id == renewed.id && it.accessToken == renewed.accessToken }
        }
    }

    /**
     * The live connection is being given up on purpose for [targetId] (null: for no saved machine, or forgetting it): when
     * that leaves the current machine, its forwards end without a lost-forward notice and its banner goes too. Called
     * only where the old connection is actually dropped, never before a prompt that could still be cancelled.
     */
    private fun leaveMachine(targetId: String? = null) {
        val current = state.value.environment?.id ?: return
        if (targetId == current) return
        portForwards.releaseAll()
        forwardBanner?.let { (_, message) -> if (_notice.value == message) _notice.value = null }
        forwardBanner = null; forwardBannerDisplaced = false; forwardBannerRestored = false
    }

    private suspend fun disconnectCurrent() {
        liveEnvironment = null
        tunnelMonitor?.cancel(); tunnelMonitor = null
        client.disconnect()
        val closing = tunnel; tunnel = null
        withContext(NonCancellable + Dispatchers.IO) { closing?.close() }
    }

    fun pair(url: String, name: String, allowInsecureHttp: Boolean) { connectionAction("Pairing failed. Check the link, server address and certificate, then try again.", ConnectionKind.Pairing) {
        clearDraft()
        leaveMachine()
        disconnectCurrent()
        connectionStage("Testing pairing link and exchanging credentials…")
        initialized.await()
        val paired = client.pair(url, name, allowInsecureHttp)
        // Re-pairing keeps the learned routes; the hints of this connection then refresh them.
        val environment = vaultMutex.withLock {
            // A new pairing: its token replaces the stored one on purpose.
            save(newPairing = true) { saved -> saved.filterNot { it.id == paired.id } + paired.withLearnedRoutesOf(saved) }
            _environments.value.first { it.id == paired.id }
        }
        selectedEnvironment = environment
        connectionStage("Opening T3 session…")
        client.connect(environment)
    } }

    /**
     * The user picked [environment] from the machine list or rail. Returning to the machine that is already live (fresh
     * pong) or still connecting reuses that session as is; only a different machine, or a dead or stale session,
     * connects (and [connectThroughPlan] closes the previous one first).
     */
    fun selectMachine(environment: SavedEnvironment) {
        // Picking another machine abandons a notification tap still waiting for its own.
        if (pendingOpen?.environmentId != environment.id) dropPendingOpen()
        chooseMachine(environment)
    }

    private fun chooseMachine(environment: SavedEnvironment) {
        // A tap on the machine the cold-start connect is reaching makes that attempt the user's: it then asks for an SSH
        // login and reports a failure like any connect.
        if (quietAttemptEpoch == connectionEpoch && _working.value && attemptEnvironmentId == environment.id) { quietAttemptEpoch = null; return }
        if (reusableSession(environment.id)) return
        connect(environment)
    }

    private fun reusableSession(environmentId: String): Boolean {
        val current = state.value
        if (_sshLogin.value != null || _sshHostKey.value != null) return false
        if (_working.value) return attemptEnvironmentId == environmentId
        return current.environment?.id == environmentId && current.connection == ConnectionStatus.Connected &&
            client.millisSinceLastPong()?.let { it <= RETAINED_CONNECTION_FRESHNESS_MS } == true
    }

    fun connect(environment: SavedEnvironment) { startConnect(environment, quiet = false) }

    /**
     * A [quiet] attempt (the cold-start connect) does not start when only SSH without a saved login is left (that would
     * prompt), and while it stays quiet ([quietAttemptEpoch]) an unreachable machine fails without a notice (see
     * [connectThroughPlan]). Null when no attempt started.
     */
    private fun startConnect(environment: SavedEnvironment, quiet: Boolean): Job? {
        val input = environment.ssh?.let { environment.sshInput() }
        val needsLogin = input != null && !input.hasSavedLogin() && environment.candidateRoutes().all { it.kind == RouteKind.Ssh }
        if (quiet && needsLogin) return null
        if (draft.value != null && environment.id != drafts.environmentId) clearDraft()
        // Only SSH is left and its login is not saved: ask right away, without a connection attempt.
        if (needsLogin) { cancelSsh(); _sshLogin.value = input; return null }
        val attempt: suspend () -> Unit = {
            // The block runs only while its epoch is current.
            val epoch = connectionEpoch
            leaveMachine(environment.id)
            connectThroughPlan(environment, quiet = if (quiet) ({ quietAttemptEpoch == epoch }) else null)
        }
        val job = if (input != null) connectionAction(SSH_FAILURE, ConnectionKind.Ssh, attempt)
        else connectionAction("Could not connect. Check the server or pair again if access has expired.", block = attempt)
        if (quiet) quietAttemptEpoch = connectionEpoch
        attemptEnvironmentId = environment.id
        return job
    }

    fun connectSsh(input: SshConnectionInput) { connectionAction(SSH_FAILURE, ConnectionKind.Ssh) { openSsh(input) } }

    /**
     * Walks [requested]'s connection plan (its latest saved copy), from [startAt] when given: direct routes in one
     * [TrefoilClient.connect] call per run, SSH in its slot when its login is saved. Without a saved login SSH is skipped
     * when a later route (the tunnel) exists; as the last option it asks for the login when [interactive], else fails.
     *
     * Routes that cannot work on plain cellular are left out ([usableRoutes]); when the network stops being plain cellular
     * during the attempt, they are tried before it fails (through the client, so the URL policy still applies).
     *
     * While [quiet] holds (the cold-start connect, see [startConnect]), a machine without SSH is probed alongside the
     * attempt; a failure ends quietly ([QuietStartupFailure]) only when that probe finds the machine unreachable. Anything
     * a probe reports otherwise (sign-in rejected, blocked address, another machine, a server that answers while the
     * session fails) and every SSH failure or login prompt is a normal failure.
     */
    private suspend fun connectThroughPlan(
        requested: SavedEnvironment,
        startAt: EnvironmentRoute? = null,
        interactive: Boolean = true,
        quiet: (() -> Boolean)? = null,
    ) {
        disconnectCurrent()
        // A request made before its machine was forgotten never connects it (nor pairs it anew).
        val environment = _environments.value.firstOrNull { it.id == requested.id } ?: throw MachineRemovedException()
        selectedEnvironment = environment.forStorage()
        readShellCacheInBackground(environment.id)
        val fullPlan = environment.candidateRoutes().let { plan -> startAt?.let { start -> plan.dropWhile { !sameRoute(it, start) } }?.ifEmpty { null } ?: plan }
        val plan = usableRoutes(fullPlan, cellularOnly)
        val skipped = fullPlan.filter { route -> plan.none { it === route } }
        val input = environment.ssh?.let { environment.sshInput() }
        // No leg connects for a machine forgotten while the plan runs; one that connected anyway is closed again.
        fun ensureSaved() { if (_environments.value.none { it.id == environment.id }) throw MachineRemovedException() }
        val direct: suspend (List<EnvironmentRoute>) -> Unit = { routes ->
            ensureSaved()
            client.connect(environment, routes)
            try { ensureSaved() } catch (e: MachineRemovedException) { client.disconnect(); throw e }
        }
        val keys = hostKeys
        val reachability = if (quiet != null && environment.ssh == null) viewModelScope.async {
            try { machineProbe(environment, keys) } catch (e: CancellationException) { throw e } catch (_: Exception) { MachineProbe.Unknown }
        } else null
        try {
            var failure: Exception? = null
            var outcome = try { walkConnectionPlan(plan, input?.hasSavedLogin() == true, ::connectionStage, direct, ssh = { ensureSaved(); openSsh(checkNotNull(input)) }) }
            catch (e: CancellationException) { throw e } catch (e: MachineRemovedException) { throw e } catch (e: SshHostKeyException) { throw e }
            catch (e: Exception) { failure = e; null }
            if (outcome != PlanOutcome.Connected && skipped.isNotEmpty() && cellularOnly != true) {
                // Left out on plain cellular (only LAN and Tailnet routes are), and the network changed since.
                outcome = try { walkConnectionPlan(skipped, false, ::connectionStage, direct, ssh = {}) }
                catch (e: CancellationException) { throw e } catch (e: MachineRemovedException) { throw e }
                catch (_: Exception) { outcome }
            }
            when (outcome) {
                PlanOutcome.Connected -> return
                PlanOutcome.NeedsSshLogin -> {
                    if (interactive && quiet?.invoke() != true && input != null) throw SshLoginRequired(input)
                    throw PairingException("Could not reach this machine without your SSH login. Connect to sign in again.")
                }
                null -> {
                    val error = checkNotNull(failure)
                    // Quiet only when the connect itself failed to reach the machine: a sign-in rejected, an address refused by
                    // the URL policy or anything after a successful session check is shown, whatever the probe found.
                    if (reachability != null && error.isUnreachableFailure() && reachability.await() == MachineProbe.Unreachable && quiet?.invoke() == true)
                        throw QuietStartupFailure(environment.id)
                    throw error
                }
            }
        } finally { reachability?.cancel() }
    }

    private suspend fun openSsh(input: SshConnectionInput) {
        sshInputValidationError(input)?.let { throw SshConnectionException(it) }
        leaveMachine(input.savedEnvironmentId)
        disconnectCurrent()
        val normalized = input.copy(host = input.host.trim(), username = input.username.trim())
        initialized.await()
        val saved = normalized.savedEnvironmentId?.let { id -> _environments.value.firstOrNull { it.id == id && it.ssh != null } }
        // A reconnect of a machine forgotten meanwhile must not turn into a new pairing.
        if (normalized.savedEnvironmentId?.let { id -> _environments.value.none { it.id == id } } == true) throw MachineRemovedException()
        saved?.let { readShellCacheInBackground(it.id) }
        val expected = hostKeys.firstOrNull { it.host.equals(normalized.host, ignoreCase = true) && it.port == normalized.port }?.fingerprint
            ?: saved?.ssh?.takeIf { it.host.equals(normalized.host, ignoreCase = true) && it.port == normalized.port }?.fingerprint
        pendingInput = normalized
        connectionStage("Testing SSH login and locating the T3 server…")
        val opened = connector.open(normalized, expected)
        pendingInput = null
        var adopted = false
        try {
            coroutineContext.ensureActive()
            // A saved identity is verified by T3Client before its bearer token is sent.
            val environment = if (saved == null) {
                connectionStage("Obtaining a T3 pairing credential…")
                val credential = opened.issuePairingCredential()
                val link = opened.baseUrl.trimEnd('/') + "/pair#token=" + java.net.URLEncoder.encode(credential, "UTF-8")
                client.pair(link, normalized.name)
            } else saved.copy(baseUrl = opened.baseUrl)
            val configured = environment.copy(name = normalized.name, ssh = normalized.savedProfile(opened.hostKey))
            connectionStage("Testing T3 session through the SSH tunnel…")
            client.connect(configured)
            coroutineContext.ensureActive()
            check(opened.active.value) { "SSH tunnel closed" }
            // The vault's learned routes are newer than the copy this attempt started from (and survive re-pairing). A
            // saved machine keeps the vault's token: it may have been renewed (here or by the widget) since it was read.
            vaultMutex.withLock {
                save(newPairing = saved == null) { stored ->
                    if (saved == null) stored.filterNot { it.id == configured.id } + configured.withLearnedRoutesOf(stored)
                    else stored.withReconnected(configured)
                }
            }
            // Forgotten while this reconnect was in flight: do not adopt a session for a machine that is no longer saved
            // (the finally block closes the tunnel and the client), and do not let the plan try another route.
            if (_environments.value.none { it.id == configured.id }) throw MachineRemovedException()
            selectedEnvironment = _environments.value.firstOrNull { it.id == configured.id } ?: configured.forStorage()
            tunnel = opened; adopted = true
            tunnelMonitor = viewModelScope.launch {
                opened.active.filter { !it }.first()
                connectionMutex.withLock {
                    if (tunnel === opened) {
                        client.disconnect(); tunnel = null
                        withContext(NonCancellable + Dispatchers.IO) { opened.close() }
                        showConnectionFailure("SSH tunnel closed. Reconnect to refresh; pending command outcomes may be uncertain.")
                    }
                }
            }
        } finally {
            if (!adopted) { client.disconnect(); withContext(NonCancellable + Dispatchers.IO) { opened.close() } }
        }
    }

    fun trustSsh() {
        val input = pendingInput ?: return
        val key = _sshHostKey.value ?: return
        if (!input.host.equals(key.host, ignoreCase = true) || input.port != key.port) return
        _sshHostKey.value = null; pendingInput = null
        val trustEpoch = connectionEpoch
        action("Could not save SSH host trust.") {
            vaultMutex.withLock {
                val keys = hostKeys.filterNot { it.host.equals(key.host, ignoreCase = true) && it.port == key.port } + key
                withContext(Dispatchers.IO) { vault.writeHostKeys(keys) }
                hostKeys = keys
            }
            if (foreground && connectionEpoch == trustEpoch) connectSsh(input)
        }
    }

    fun cancelSsh() {
        connectionEpoch++
        connectionJob?.cancel()
        pendingInput = null; _sshHostKey.value = null; _sshLogin.value = null
        _working.value = false
        _connectionAttempt.value = ConnectionAttempt()
    }

    fun selectThread(id: String) {
        dropPendingOpen()
        clearDraft()
        action("Could not open the thread. Reconnect and try again.") { client.selectThread(id) }
    }
    /**
     * With a draft, the first message creates the thread (`launchThread`); otherwise it goes to the
     * selected thread. Every failed or uncertain send emits [sendFailed]. An uncertain launch keeps
     * the draft and its ids so it can be reconciled or explicitly resent without a duplicate.
     */
    fun send(text: String) = drafts.send(text)
    /**
     * A composer message for an existing thread: [mode] null on an idle thread, else the follow-up delivery. Sent once through
     * the draft-clearing client (a failed or uncertain outcome keeps the draft and shows a notice; nothing is ever replayed)
     * and answered in [sendOutcomes] under the submission's id. A submission made for another thread than the selected one
     * (the user moved on before the tap landed) is refused rather than sent to the wrong conversation.
     */
    internal fun submitMessage(submission: ComposerSubmission, mode: FollowUpMode?) {
        _sendsInFlight.update { it + submission.id }
        viewModelScope.launch {
            _notice.value = null
            val current = client.state.value
            val failure = if (current.environment?.id != submission.environmentId || current.selectedThreadId != submission.threadId) {
                "The conversation changed before the message was sent. Send it again."
            } else try {
                if (mode == null) composerClient.sendMessage(submission.text) else composerClient.sendMessage(submission.text, mode)
                null
            }
            catch (e: CancellationException) { coroutineContext.ensureActive(); UNCERTAIN_MESSAGE }
            catch (_: UncertainOutcomeException) { UNCERTAIN_MESSAGE }
            catch (e: Exception) { e.message ?: "Could not send the message." }
            if (failure != null) _notice.value = failure
            // Outcome first, so there is never a moment when the id is in neither set.
            _sendOutcomes.update { it + (submission.id to if (failure == null) SendOutcome.Accepted else SendOutcome.Failed) }
            _sendsInFlight.update { it - submission.id }
        }
    }
    private val _sideChat = MutableStateFlow(SideChatState())
    /** `/side`: whether a side chat is being started (one at a time) and how the last start came out, until its composer consumes it. */
    internal val sideChat: StateFlow<SideChatState> = _sideChat.asStateFlow()
    internal fun consumeSideChatResult() = _sideChat.update { it.copy(result = null) }
    /**
     * Starts a side chat of the selected thread with [question] and reports the outcome in [sideChat]. Never replayed: a second call while one
     * is in flight is ignored, and an uncertain outcome is shown as such rather than retried.
     */
    internal fun startSideChat(question: String) {
        val current = client.state.value
        val environmentId = current.environment?.id ?: return
        val parentId = current.selectedThreadId ?: return
        val idle = _sideChat.value
        if (idle.inFlight || !_sideChat.compareAndSet(idle, SideChatState(inFlight = true))) return
        viewModelScope.launch {
            val result = try {
                SideChatResult.Started(environmentId, parentId, mutationsInFlight.tracked { client.startSideChat(question) })
            }
            catch (e: CancellationException) { coroutineContext.ensureActive(); SideChatResult.Failed(environmentId, parentId, UNCERTAIN_MESSAGE) }
            catch (_: UncertainOutcomeException) { SideChatResult.Failed(environmentId, parentId, UNCERTAIN_MESSAGE) }
            catch (e: Exception) { SideChatResult.Failed(environmentId, parentId, e.message ?: "Could not start the side chat.") }
            _sideChat.value = SideChatState(inFlight = false, result = result)
        }
    }
    /**
     * Stops the selected thread (run, background work, subagents) and holds its queue. One Stop per thread is in flight at a
     * time, checked here at dispatch: a second tap, or a re-entered conversation, cannot send another.
     */
    fun stopThread() {
        val current = client.state.value
        val key = stopKey(current.environment?.id, current.selectedThreadId)
        if (!stopGuard.tryBegin(key)) return
        viewModelScope.launch { delay(stopGuard.holdMs); stopGuard.expire(key) }
        mutation("Stop outcome is uncertain. Check the thread status before trying again.") {
            try { client.stopThread() } catch (e: Throwable) { stopGuard.release(key); throw e }
        }
    }
    /**
     * A queue command (Resume, Send now, Remove) for the selected thread. The per-thread guard is taken here, synchronously at
     * the tap: a second tap before the projection catches up is a no-op, never a second command.
     */
    private fun queueOperation(failure: String, block: suspend () -> Unit) {
        val current = client.state.value
        val key = stopKey(current.environment?.id, current.selectedThreadId)
        val token = queueGuard.tryBegin(key, current.queueSignature()) ?: return
        viewModelScope.launch { delay(queueGuard.holdMs); queueGuard.expire(key, token) }
        mutation(failure) {
            try {
                block()
                val now = client.state.value
                queueGuard.completed(key, token, now.queueSignature().takeIf { stopKey(now.environment?.id, now.selectedThreadId) == key })
            } catch (e: Throwable) { queueGuard.failed(key, token); throw e }
        }
    }
    fun cancelQueued(runId: String) = queueOperation("Could not remove the queued message. Check the queue before trying again.") { client.cancelQueuedMessage(runId) }
    fun steerQueued(runId: String) = queueOperation("Could not send the queued message now. Check the queue before trying again.") { client.steerQueuedMessage(runId) }
    fun resumeQueue() = queueOperation("Could not resume the queue. Check the thread status before trying again.") { client.resumeQueue() }
    fun loadActivity(id: String) = action("Could not load activity output. Reconnect and try again.") { client.loadActivity(id) }
    /** Streams a side thread of the selected thread into the inspector pane (read-only; never a mutation, so a failure only shows a notice). */
    fun openInspector(id: String) = action("Could not open the thread. Check the connection and try again.") { client.openInspector(id) }
    fun closeInspector() = client.closeInspector()
    fun stopSubagent(id: String) = mutation("Agent stop failed or its outcome is uncertain. Check the agent before trying again.") { client.stopSubagent(id) }
    fun threadAction(target: ThreadTarget, operation: ThreadAction, value: String?) = mutation("Could not update thread. Check its status before trying again.") { client.threadAction(target, operation, value) }
    fun undoSettlement(undo: SettlementUndo) = mutation("Could not undo settlement. Check the thread status.") { client.undoSettlement(undo) }
    fun respond(id: String, decision: String, answers: Map<String,List<String>>) = mutation("Response outcome is uncertain. Check the request status before responding again.") { client.respond(id, decision, answers) }
    /** Opens a local draft; no server mutation until the first send. */
    fun startDraft(draft: ThreadDraft) {
        dropPendingOpen()
        // A draft whose earlier launch is still uncertain relaunches with the same ids, so the server resumes instead of duplicating.
        val launch = state.value.environment?.id?.let { composerDraftStore.get(ComposerDraftKey.NewThread(it, draft.projectId))?.pendingLaunch }
        val ids = if (launch == null) draft else draft.copy(threadId = launch.threadId, commandId = launch.commandId)
        // A thread started from an issue links it once after the launch; a request of another machine is dropped.
        drafts.start(issueStartController.take(state.value.environment?.id)?.let { ids.copy(issueLink = it) } ?: ids)
    }
    /** The composer draft for [key], including a NewThread draft that holds only sheet settings. */
    fun composerDraft(key: ComposerDraftKey): ComposerDraft? = composerDraftStore.get(key)
    /** Writes through the composer state (debounced); blank text without settings removes it. Allowed while disconnected. */
    fun updateComposerDraft(key: ComposerDraftKey, text: String, modelInstanceId: String? = null, model: String? = null, runtimeMode: String? = null, effort: String? = null, fastMode: Boolean? = null) =
        composerDraftStore.update(key, text, modelInstanceId, model, runtimeMode, effort, fastMode)
    /** Writes text only, keeping a NewThread draft's settings (the conversation composer). */
    fun updateComposerDraftText(key: ComposerDraftKey, text: String) = composerDraftStore.updateText(key, text)
    fun clearComposerDraft(key: ComposerDraftKey) = composerDraftStore.clear(key)
    /** Moves the sheet's draft to another project; false (nothing changed) when the target holds a draft. See [ComposerDraftStore.move]. */
    fun moveComposerDraft(from: ComposerDraftKey, to: ComposerDraftKey, keepSource: Boolean): Boolean = composerDraftStore.move(from, to, keepSource)
    /** Discards a draft; the token undoes it while nothing replaced it. */
    fun discardComposerDraft(key: ComposerDraftKey): ComposerDraftDiscard? = composerDraftStore.discard(key)
    fun restoreComposerDraft(discard: ComposerDraftDiscard) { composerDraftStore.restore(discard) }
    fun flushComposerDrafts() { composerDraftStore.flushInBackground() }
    fun cancelDraft() = clearDraft()
    private fun clearDraft() = drafts.clear()
    /** Draft: updates locally. Selected thread: dispatches `thread.runtime-mode.set`. */
    fun setRuntimeMode(mode: RuntimeMode) = drafts.setRuntimeMode(mode)
    /** Draft: updates locally. Selected thread: dispatches `thread.model-selection.set`. */
    fun setModel(model: ModelOption) = drafts.setModel(model)
    /** Provider option ([value] String or Boolean). Draft: updates locally. Selected thread: dispatches `thread.model-selection.set`. */
    fun setModelOption(id: String, value: Any) = drafts.setModelOption(id, value)
    /** Stars or unstars [key] in the server's favourites (shared with T3 desktop); a failure shows as a notice. */
    fun setModelFavorite(key: ModelKey, favorite: Boolean) {
        viewModelScope.launch {
            try { composerClient.setModelFavorite(key, favorite) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _notice.value = e.message ?: "Could not change favourites." }
        }
    }

    /** Keeps the draft: reconnecting is the same environment, and an uncertain launch is reconciled after it. */
    fun reconnect() {
        val environment = selectedEnvironment ?: state.value.environment?.forStorage() ?: return
        if (environment.ssh != null) connect(environment)
        else connectionAction("Could not reconnect. Check the server or pair again.") { connectThroughPlan(environment) }
    }

    /**
     * Pull-to-refresh for the thread list. A live connection re-reads the shell ([TrefoilClient.refreshThreads]), so
     * every row's run status, background work and requests come from a fresh server snapshot. Without one it reconnects
     * the current machine (or waits for the attempt already running). Read-only: never replays a mutation. A second
     * call while [isRefreshing] is ignored.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        val current = state.value
        val live = current.connection == ConnectionStatus.Connected && current.environment != null && !_working.value
        if (!live && !_working.value) {
            if (selectedEnvironment == null && current.environment == null) return
            reconnect()
        }
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                // A reconnect that stops at a host-key or login prompt is the user's turn, not a refresh in progress.
                if (live) shellReads.refreshThreads()
                else combine(_working, _sshHostKey, _sshLogin) { working, hostKey, login -> !working || hostKey != null || login != null }.first { it }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _notice.value = "Could not refresh threads. Check the connection and try again." }
            finally { _isRefreshing.value = false }
        }
    }

    /**
     * Resuming on a retained live session catches the shell up ([TrefoilClient.resyncThreads]: only the changes missed
     * while in the background, or a full snapshot without a valid cursor), so rows never show statuses from before the
     * background. Pull-to-refresh stays a full snapshot ([refresh]).
     */
    private fun refreshRetainedShell() {
        val away = backgroundedAt?.let { android.os.SystemClock.elapsedRealtime() - it } ?: return
        if (away < RESUME_SHELL_REFRESH_MIN_BACKGROUND_MS || silentShellRefresh?.isActive == true || _isRefreshing.value) return
        if (state.value.connection != ConnectionStatus.Connected || _working.value) return
        silentShellRefresh = viewModelScope.launch {
            try { shellReads.resyncThreads() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* A failed refresh fails the connection; drop handling reconnects. */ }
        }
    }

    /**
     * A tap on an agent notification (authenticated by `parseOpenThreadIntent`, ids still checked here). Waits for the saved
     * machines to load and for the foreground (a connect started in the background would stop at its foreground guard), drops a
     * request for an unknown machine, switches to (or reconnects) the named machine when needed and selects the thread once that
     * machine's live shell is synchronized: retained or cached rows may list a thread deleted meanwhile, and a selection tried
     * against them while disconnected would never be retried. The first synchronized shell that does not list the thread drops
     * the request; a selection that fails is retried at the next synchronized state. The client checks the machine, freshness and
     * listing together with the selection ([TrefoilClient.selectThreadIn]), so another machine's thread is never opened by id.
     * While held it takes precedence over resuming the previous machine ([resume]). Consumed once; dropped when the user picks
     * another machine or thread, starts a new thread, disconnects, forgets the machine, or the connection moves to another machine.
     */
    fun openThreadFromNotification(request: OpenThreadRequest) {
        dropPendingOpen()
        pendingOpen = request
        pendingOpenJob = viewModelScope.launch {
            initialized.await()
            val machine = _environments.value.firstOrNull { it.id == request.environmentId }
            if (machine == null) { if (pendingOpen === request) pendingOpen = null; return@launch }
            foregroundState.first { it }
            val current = state.value
            if (current.environment?.id != machine.id || current.connection == ConnectionStatus.Disconnected || current.connection == ConnectionStatus.Error) chooseMachine(machine)
            var seen = state.value.environment?.id
            client.state.map { Triple(it.environment?.id, shellFresh(it), it.threads.any { thread -> thread.id == request.threadId }) }
                .distinctUntilChanged()
                .first { (environmentId, fresh, listed) ->
                    // The connection moved to a third machine (or the switch was replaced): the request no longer applies.
                    if (environmentId != null && environmentId != seen && environmentId != request.environmentId) return@first true
                    seen = environmentId
                    if (environmentId != request.environmentId || !fresh) return@first false
                    // The server's own list does not have it (deleted, archived away): nothing to open.
                    if (!listed) return@first true
                    clearDraft()
                    val opened = try { client.selectThreadIn(request.environmentId, request.threadId) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (opened) _openedThread.value = request
                    opened
                }
            if (pendingOpen === request) pendingOpen = null
        }
    }

    /** The UI has shown the thread from [openedThread]. */
    fun consumeOpenedThread() { _openedThread.value = null }

    private fun dropPendingOpen() {
        pendingOpenJob?.cancel(); pendingOpenJob = null; pendingOpen = null
    }

    fun disconnect() {
        dropPendingOpen()
        liveEnvironment = null; reconnectWhenOnline = null
        autoReconnectJob?.cancel(); autoReconnectJob = null
        agentAlerts.cancelAll()
        clearDraft()
        screenSessions.close()
        resumeEnvironment = null; cancelSsh()
        // The disconnect below waits for connectionMutex; a check posted before then must not restart the service.
        keepAliveLatched = true
        stopKeepAlive(releaseBackground = false)
        // Asked for (also from the keep-alive notification), so ending the forwards it held is not a loss to report.
        portForwards.releaseAll()
        action("Could not disconnect.") { connectionMutex.withLock { disconnectCurrent() } }
    }

    /** Persists the opt-in; the keep-alive service follows it (turning it off in the background restores the grace timeout). */
    fun setKeepConnected(enabled: Boolean) {
        prefs.edit().putBoolean(KEEP_CONNECTED, enabled).apply()
        keepAliveRefused = false
        _keepConnected.value = enabled
        postKeepAliveCheck()
    }
    fun setHideTestFiles(hidden: Boolean) {
        prefs.edit().putBoolean(HIDE_TEST_FILES, hidden).apply()
        _hideTestFiles.value = hidden
    }
    /** Reads and parses one turn's diff of the selected thread (the Changes screen); the session applies its own selection guard. */
    suspend fun loadTurnDiff(turnCount: Int): DiffDocument = client.loadTurnDiff(turnCount)
    /** Pull request panel state, reads and actions of the selected thread (the PR panel); the session owns the rules and the refresh stream. */
    fun prPanel(key: PullRequestKey): StateFlow<PrPanelState> = client.prPanel(key)
    suspend fun refreshPullRequest(key: PullRequestKey) = client.refreshPullRequest(key)
    suspend fun loadPullRequestDiff(key: PullRequestKey, cursor: String?): PrDiffPage = client.loadPullRequestDiff(key, cursor)
    /**
     * Pull request mutations run on [viewModelScope] through [PullRequestSender], so closing the panel, switching threads or rotating never
     * cancels a send (a cancelled send would be recorded as an unknown outcome) and a second tap for the same pull request is dropped.
     */
    private val pullRequestSender = PullRequestSender(
        viewModelScope,
        sendAction = { key, action, method -> client.runPullRequestAction(key, action, method) },
        sendComment = { key, body -> client.commentOnPullRequest(key, body) },
    )
    val pullRequestBusy: StateFlow<Set<PullRequestKey>> get() = pullRequestSender.busy
    val pullRequestErrors: StateFlow<Map<PullRequestKey, String>> get() = pullRequestSender.errors
    fun runPullRequestAction(key: PullRequestKey, action: PrAction, method: PrMergeMethod?) { pullRequestSender.run(key, action, method) }
    fun commentOnPullRequest(key: PullRequestKey, body: String) { pullRequestSender.comment(key, body) }
    fun dismissPullRequestError(key: PullRequestKey) = pullRequestSender.dismissError(key)
    /** Persists the opt-in for agent notifications; turning it off removes the ones in the shade (the driver sees the flow change). */
    fun setAgentNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(AGENT_NOTIFICATIONS, enabled).apply()
        _agentNotifications.value = enabled
    }
    /** Saves per-machine consent for plain HTTP to literal private LAN addresses; applies from the next connect. */
    fun allowLocalNetworkHttp(environmentId: String, allowed: Boolean) = action("Could not save the local network setting.") {
        initialized.await()
        vaultMutex.withLock {
            val current = _environments.value.firstOrNull { it.id == environmentId } ?: return@withLock
            if (current.allowInsecureHttp == allowed) return@withLock
            save { saved -> saved.map { if (it.id == environmentId) it.copy(allowInsecureHttp = allowed) else it } }
            selectedEnvironment?.takeIf { it.id == environmentId }?.let { selectedEnvironment = it.copy(allowInsecureHttp = allowed) }
        }
    }
    fun forget(environment: SavedEnvironment) = action("Could not remove saved credentials.") {
        // Before anything suspends: a cache read still running for this machine is never restored.
        shellCacheRead += environment.id
        if (pendingOpen?.environmentId == environment.id) dropPendingOpen()
        composerDraftStore.clearEnvironment(environment.id)
        if (drafts.environmentId == environment.id || state.value.environment?.id == environment.id) clearDraft()
        if (selectedEnvironment?.id == environment.id || state.value.environment?.id == environment.id) { cancelSsh(); keepAliveLatched = true; agentAlerts.cancelAll() }
        if (resumeEnvironment?.id == environment.id) resumeEnvironment = null
        _machineProbes.update { it - environment.id }
        if (prefs.getString(LAST_ENVIRONMENT, null) == environment.id) prefs.edit().remove(LAST_ENVIRONMENT).apply()
        connectionMutex.withLock {
            if (selectedEnvironment?.id == environment.id || state.value.environment?.id == environment.id) {
                autoReconnectJob?.cancel(); autoReconnectJob = null; reconnectWhenOnline = null
                leaveMachine()
                disconnectCurrent(); selectedEnvironment = null
                stopKeepAlive(releaseBackground = false)
            }
            if (reconnectWhenOnline?.id == environment.id) reconnectWhenOnline = null
            client.forgetEnvironment(environment.id)
            shellCacheWriter.wipe(environment.id)
            fileDownloads.htmlRenders.evictEnvironment(environment.id)
            // Deleted before connection attempts run again: one queued behind this lock then finds no row and stops.
            initialized.await()
            vaultMutex.withLock { save { saved -> saved.filterNot { it.id == environment.id } } }
        }
        widgetSync.forget(environment.id)
    }
    private var threadShown = false
    /** The conversation is on screen (reported by the UI); a visit is recorded only while it is shown in the foreground. */
    fun setThreadShown(shown: Boolean) { threadShown = shown; updateThreadViewing(); agentAlerts.setThreadShown(shown) }
    private fun updateThreadViewing() = client.setThreadViewing(foreground && threadShown)
    fun pause() {
        flushComposerDrafts()
        state.value.environment?.id?.let(shellCacheWriter::flush)
        if (!foreground) return
        screenSessions.pause()
        foreground = false
        updateThreadViewing()
        agentAlerts.setForeground(false)
        backgroundedAt = android.os.SystemClock.elapsedRealtime()
        // Route switches and machine probes wait for the foreground.
        refreshRouteMonitor()
        probeJob?.cancel(); probeJob = null
        _machineProbes.update { probes -> probes.mapValues { (_, probe) -> if (probe == MachineProbe.Checking) MachineProbe.Unknown else probe } }
        resumeEnvironment = if (state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting))
            selectedEnvironment ?: state.value.environment?.forStorage() else null
        if (keepAlive) {
            // The service keeps the connection, auto-reconnect and a running attempt going; interactive SSH prompts still
            // wait for the foreground, as without it.
            if (_sshHostKey.value != null || (_sshLogin.value != null && !sshFilePickerActive)) cancelSsh()
        } else {
            // The cold-start connect is cancelled below before it published Connecting (e.g. during SSH discovery), so
            // nothing above records it: re-arm it, and the next foreground runs it again (through connectLastUsedNow, which
            // skips a machine forgotten meanwhile) instead of only probing. A connection the user started moved the epoch on
            // or took the attempt over (quietAttemptEpoch null) and is not re-armed; each re-arm needs another pause.
            val startupCancelled = resumeEnvironment == null && _working.value && quietAttemptEpoch == connectionEpoch
            releaseInBackground()
            if (startupCancelled) { quietAttemptEpoch = null; autoConnectPending = true }
        }
    }

    /**
     * Background without keep-alive: lifecycle code owns reconnecting, pending handshakes and trust are cancelled, and an
     * established socket and SSH tunnel get [BACKGROUND_CONNECTION_GRACE_MS] to stay live.
     */
    private fun releaseInBackground() {
        autoReconnectJob?.cancel(); autoReconnectJob = null; reconnectWhenOnline = null
        if (sshFilePickerActive) { connectionEpoch++; connectionJob?.cancel(); _working.value = false } else cancelSsh()
        val epoch = connectionEpoch
        backgroundDisconnectJob?.cancel()
        backgroundDisconnectJob = viewModelScope.launch {
            delay(BACKGROUND_CONNECTION_GRACE_MS)
            connectionMutex.withLock {
                // A resume, explicit disconnect or newer connection attempt supersedes this timeout.
                if (!foreground && epoch == connectionEpoch) { portForwards.graceDisconnecting(); disconnectCurrent() }
            }
        }
    }
    /**
     * Dismisses the lost-forward notice of [environmentId]; the app-wide banner that announced it goes with it, through
     * the [stoppedNotices][com.tajchert.trefoil.ui.ports.PortForwardCoordinator.stoppedNotices] collector.
     */
    fun dismissStoppedForwards(environmentId: String) = portForwards.dismissStoppedNotice(environmentId)

    /** Posts the losses of the shown machine not announced yet; only in the foreground, where the user sees it. */
    private fun announceStoppedForwards() {
        if (!foreground) return
        val environmentId = state.value.environment?.id ?: return
        val message = portForwards.announceStopped(environmentId) ?: return
        forwardBanner = environmentId to message; forwardBannerDisplaced = false; forwardBannerRestored = false
        _notice.value = message
    }

    private fun syncForwardBanner(notices: Map<String, com.tajchert.trefoil.ui.ports.StoppedForwardNotice>) {
        forwardBanner?.let { (environmentId, message) ->
            if (environmentId !in notices) {
                forwardBanner = null; forwardBannerDisplaced = false; forwardBannerRestored = false
                if (_notice.value == message) _notice.value = null
            }
        }
        announceStoppedForwards()
    }

    /** A connection failure takes the notice; a lost-forward banner it replaces unseen comes back once (see [connectionNotice]). */
    private fun showConnectionFailure(message: String) {
        if (forwardBanner?.second == _notice.value && !forwardBannerRestored) forwardBannerDisplaced = true
        _notice.value = message
    }

    /**
     * What a connection attempt leaves in the notice. A lost-forward banner on screen when the attempt starts (a Reconnect
     * tap) was seen and is consumed; one a connection failure replaced before the user acted on it comes back, once.
     */
    private fun connectionNotice(): String? {
        val (environmentId, message) = forwardBanner ?: return null
        if (!forwardBannerDisplaced) return null
        forwardBannerDisplaced = false
        if (environmentId != state.value.environment?.id || environmentId !in portForwards.stoppedNotices.value) return null
        forwardBannerRestored = true
        return message
    }
    fun resume() {
        foreground = true
        portForwards.checkDeadlines()
        updateThreadViewing()
        agentAlerts.setForeground(true)
        backgroundDisconnectJob?.cancel(); backgroundDisconnectJob = null
        postKeepAliveCheck()
        screenSessions.resume()
        refreshRouteMonitor()
        // A held notification tap names the machine to bring back instead of the one used before the background.
        val openTarget = pendingOpen?.let { open -> _environments.value.firstOrNull { it.id == open.environmentId } }
        val environment = openTarget ?: resumeEnvironment
        resumeEnvironment = null
        // A running attempt (of a background reconnect episode or not) goes on; connecting would cancel it. An episode that
        // only waits out its backoff is replaced by one connect now (offline it keeps waiting for the network). An episode
        // made obsolete by a later connection action (epoch moved on) is never taken over: its machine is no longer wanted.
        val episodeActive = autoReconnectJob?.isActive == true
        val takeOverEpisode = episodeActive && autoReconnectWaiting && autoReconnectEpoch == connectionEpoch &&
            !_working.value && networkAvailable && _sshLogin.value == null
        val target = environment ?: autoReconnectEnvironment?.takeIf { episode -> takeOverEpisode && _environments.value.any { it.id == episode.id } }
        if (takeOverEpisode && target != null) { autoReconnectJob?.cancel(); autoReconnectJob = null }
        val reconnectInFlight = _working.value || autoReconnectJob?.isActive == true
        if (target != null && _sshLogin.value == null && !reconnectInFlight &&
            (state.value.connection != ConnectionStatus.Connected || state.value.environment?.id != target.id ||
                (client.millisSinceLastPong()?.let { it > RETAINED_CONNECTION_FRESHNESS_MS } != false))) connect(target)
        else if (environment != null && !reconnectInFlight && state.value.environment?.id == environment.id) refreshRetainedShell()
        // Set after any connect above (which clears notices), so the user learns why a page in their browser stopped
        // loading; once, and only while the machine it is about is the one shown. A loss published after this point is
        // announced by the stoppedNotices collector.
        announceStoppedForwards()
        probeMachines(skipId = target?.id, connectLastUsedFirst = target == null && !reconnectInFlight)
    }

    /**
     * Probes every saved machine except the active or resuming one, in parallel. On the first launch
     * ([connectLastUsedFirst]) the last-used machine connects at once, without waiting for its probe, and the
     * others are probed once that attempt settles. When it fails (quietly), the probes run as before, without it
     * among the auto-connect candidates: the only reachable other machine may connect through the normal [connect]
     * path, unless the user started a connection in the meantime.
     */
    private fun probeMachines(skipId: String?, connectLastUsedFirst: Boolean = false) {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            initialized.await()
            if (!foreground) return@launch
            var failedLastUsed: String? = null
            if (connectLastUsedFirst) connectLastUsedNow()?.let { (id, attempt, attemptEpoch) ->
                attempt.join()
                if (!foreground) return@launch
                // Only a quiet failure with nothing started since leaves auto-connect to the probes. The failed machine
                // was probed with its attempt (its card shows Unreachable), so it is neither probed nor connected again.
                if (attemptEpoch == connectionEpoch && quietFailedEpoch == attemptEpoch) { autoConnectPending = true; failedLastUsed = id }
            }
            val epoch = connectionEpoch
            val keys = hostKeys
            val live = state.value.environment?.id?.takeIf {
                state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)
            }
            val targets = _environments.value.filter { it.id != skipId && it.id != live && it.id != failedLastUsed }
            _machineProbes.update { probes ->
                probes.filterKeys { id -> _environments.value.any { it.id == id } } + targets.associate { it.id to MachineProbe.Checking }
            }
            fun considerAutoConnect() {
                if (!autoConnectPending) return
                if (epoch != connectionEpoch || !foreground || _sshLogin.value != null ||
                    state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)) {
                    autoConnectPending = false; return
                }
                val lastUsed = prefs.getString(LAST_ENVIRONMENT, null)?.takeIf { it != failedLastUsed }
                val candidates = _environments.value.filter { it.id != failedLastUsed }
                when (val decision = autoConnectDecision(lastUsed, candidates, _machineProbes.value)) {
                    AutoConnectDecision.Wait -> {}
                    AutoConnectDecision.None -> autoConnectPending = false
                    is AutoConnectDecision.Connect -> {
                        autoConnectPending = false
                        _environments.value.firstOrNull { it.id == decision.environmentId }?.let(::connect)
                    }
                }
            }
            supervisorScope {
                targets.forEach { environment ->
                    launch {
                        val result = machineProbe(environment, keys)
                        _machineProbes.update { probes -> if (environment.id in probes) probes + (environment.id to result) else probes }
                        considerAutoConnect()
                    }
                }
            }
            considerAutoConnect()
        }
    }
    /**
     * The cold-start connect: the last-used machine (still saved) connects through the normal plan before any probe,
     * while nothing else connects or prompts and a network is up. Returns its id, attempt and epoch; null otherwise.
     */
    private fun connectLastUsedNow(): Triple<String, Job, Long>? {
        if (!autoConnectPending || !foreground || !networkAvailable || _working.value) return null
        if (_sshLogin.value != null || _sshHostKey.value != null) return null
        if (state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)) return null
        val lastUsedId = prefs.getString(LAST_ENVIRONMENT, null) ?: return null
        val environment = _environments.value.firstOrNull { it.id == lastUsedId } ?: return null
        val attempt = startConnect(environment, quiet = true) ?: return null
        return Triple(environment.id, attempt, connectionEpoch)
    }

    private suspend fun learnRoutes(hints: RouteHints) {
        try {
            initialized.await()
            vaultMutex.withLock {
                val id = hints.environmentId
                val updated = applyRouteHints(_environments.value, hints)
                if (updated == null) {
                    // SSH pairing saves the machine after its connect: keep the hints until then.
                    if (_environments.value.none { it.id == id }) {
                        if (id !in unsavedHints && unsavedHints.size >= 4) unsavedHints.clear()
                        unsavedHints[id] = (unsavedHints[id].orEmpty() + hints).takeLast(4)
                    }
                    return@withLock
                }
                save { saved -> applyRouteHints(saved, hints) ?: saved }
                val routes = _environments.value.first { it.id == id }.routes
                selectedEnvironment?.takeIf { it.id == id }?.let { selectedEnvironment = it.copy(routes = routes) }
                resumeEnvironment?.takeIf { it.id == id }?.let { resumeEnvironment = it.copy(routes = routes) }
                liveEnvironment?.takeIf { it.id == id }?.let { liveEnvironment = it.copy(routes = routes) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Learning is best effort; the plan keeps the routes it knows. */ }
    }

    /** Watches for a better route while the foreground connection is on a route its plan ranks lower. */
    private fun refreshRouteMonitor() {
        val current = state.value
        val active = current.activeRoute
        val saved = current.environment?.id?.let { id -> _environments.value.firstOrNull { it.id == id } }
        // Leaving an SSH session whose login was typed, not saved, could not fall back to it if the switch failed.
        val fallbackPossible = active?.kind != RouteKind.Ssh || saved?.ssh?.let { saved.sshInput().hasSavedLogin() } == true
        routeMonitor.watch(if (foreground && current.connection == ConnectionStatus.Connected && active != null && saved != null && fallbackPossible)
            RouteWatch(saved.id, active, usableRoutes(saved.candidateRoutes(), cellularOnly), saved.allowInsecureHttp) else null)
    }

    /** A switch is a disconnect plus connect: only when nothing in flight could be abandoned or cut off. */
    private fun idleForRouteSwitch(): Boolean = foreground && !_working.value && _sshLogin.value == null && _sshHostKey.value == null &&
        mutationsInFlight.get() == 0 && client.isIdleForRouteSwitch() &&
        fileDownloads.state.value.values.none { it.status == com.tajchert.trefoil.downloads.DownloadStatus.Downloading } &&
        screenSessions.state.value.target == null

    /** Reconnects through the plan from [route]; a route that does not end up active goes on cooldown. */
    private fun switchRoute(watch: RouteWatch, route: EnvironmentRoute) {
        val current = state.value
        val saved = _environments.value.firstOrNull { it.id == watch.environmentId } ?: return
        if (current.connection != ConnectionStatus.Connected || current.environment?.id != saved.id || !idleForRouteSwitch()) return
        val attempt = connectionAction("Could not switch to a faster connection. Reconnect to refresh.") {
            connectThroughPlan(saved, startAt = route, interactive = false)
        }
        val epoch = connectionEpoch
        viewModelScope.launch {
            attempt.join()
            if (epoch != connectionEpoch) return@launch
            val active = state.value.activeRoute
            if (state.value.connection != ConnectionStatus.Connected || active == null || !sameRoute(active, route)) routeMonitor.switchFailed(saved.id, route)
        }
    }

    /** An established connection ended without this view model asking for it. */
    private fun connectionDropped(environmentId: String?) {
        val live = liveEnvironment ?: return
        liveEnvironment = null
        val afterNetworkChange = networkChangeEpoch == connectionEpoch
        networkChangeEpoch = null
        if (live.id != environmentId || !(foreground || keepAlive) || _working.value || _sshLogin.value != null) return
        scheduleAutoReconnect(live, immediate = afterNetworkChange)
    }

    /**
     * One reconnect episode through the plan, attempts after [AUTO_RECONNECT_BACKOFF_MS] ([immediate]: the first without
     * waiting, as after a network change). It only re-reads (connect never replays a mutation) and stops at success, a
     * user action or pause (epoch change), a forgotten machine, or offline.
     */
    private fun scheduleAutoReconnect(environment: SavedEnvironment, immediate: Boolean = false) {
        autoReconnectJob?.cancel(); autoReconnectJob = null
        if (!networkAvailable) { reconnectWhenOnline = environment; return }
        reconnectWhenOnline = null
        var epoch = connectionEpoch
        // Only the active episode writes these: a cancelled one stops at its next suspension, before any write.
        autoReconnectEnvironment = environment; autoReconnectWaiting = true; autoReconnectEpoch = epoch
        autoReconnectJob = viewModelScope.launch {
            for (wait in if (immediate) listOf(0L) + AUTO_RECONNECT_BACKOFF_MS.drop(1) else AUTO_RECONNECT_BACKOFF_MS) {
                autoReconnectWaiting = true
                delay(wait)
                autoReconnectWaiting = false
                if (!(foreground || keepAlive) || epoch != connectionEpoch || _sshLogin.value != null || _environments.value.none { it.id == environment.id }) return@launch
                if (!networkAvailable) { reconnectWhenOnline = environment; return@launch }
                val attempt = connectionAction("Connection lost. Check the network and server, then reconnect.") {
                    connectThroughPlan(environment, interactive = false)
                }
                epoch = connectionEpoch; autoReconnectEpoch = epoch
                attempt.join()
                if (epoch != connectionEpoch) return@launch
                if (state.value.connection == ConnectionStatus.Connected && state.value.environment?.id == environment.id) return@launch
            }
        }
    }

    /**
     * The default network is [handle] (`Network.getNetworkHandle`). Regaining one resumes a pending reconnect at once. A
     * change pings the live connection, whose socket may still be bound to the old network: silence drops it and the
     * plan reconnects without the first backoff. It also checks for a better route.
     */
    fun onDefaultNetwork(handle: Long) {
        // Network callbacks can be the first thing the app runs after deep sleep.
        portForwards.checkDeadlines()
        val changed = networkKnown && defaultNetwork != handle
        val wasCellular = cellularOnly
        if (defaultNetwork != handle) cellularOnly = null
        networkKnown = true; defaultNetwork = handle; networkAvailable = true
        if (!(foreground || keepAlive)) return
        reconnectWhenOnline?.let { scheduleAutoReconnect(it, immediate = true); return }
        if (changed && state.value.connection == ConnectionStatus.Connected) {
            verifyLink()
            if (foreground) {
                // Plain cellular stopped the watch; rebuild it before asking, or the check waits for the next interval.
                if (wasCellular == true) refreshRouteMonitor()
                routeMonitor.requestCheck(networkChanged = true)
            }
        }
    }

    /** A failed check fails the connection inside the client; [connectionDropped] then reconnects through the plan. */
    private fun verifyLink() {
        val epoch = connectionEpoch
        networkChangeEpoch = epoch
        linkCheckJob?.cancel()
        linkCheckJob = viewModelScope.launch {
            // A pong keeps this connection and its route; a later drop waits the normal backoff.
            if (client.verifyLink(LINK_CHECK_TIMEOUT_MS) && networkChangeEpoch == epoch) networkChangeEpoch = null
        }
    }

    /** Network [handle] stopped being the default; only the current default going away means offline. */
    fun onDefaultNetworkLost(handle: Long) {
        portForwards.checkDeadlines()
        if (defaultNetwork == handle) { defaultNetwork = null; networkAvailable = false; cellularOnly = null }
    }

    /** The transports of the default network [handle]: plain cellular leaves out routes that cannot work there. */
    fun onDefaultNetworkTransports(handle: Long, plainCellular: Boolean) {
        if (defaultNetwork != handle || cellularOnly == plainCellular) return
        val wasCellular = cellularOnly
        cellularOnly = plainCellular
        refreshRouteMonitor()
        // The same network gained a local transport (or VPN): its private routes may answer now.
        if (wasCellular == true && foreground && state.value.connection == ConnectionStatus.Connected) routeMonitor.requestCheck(networkChanged = true)
    }

    /** Coalesces keep-alive checks to after the current main-thread work, so transient states never stop the service. */
    private fun postKeepAliveCheck() {
        if (keepAliveCheckPosted) return
        keepAliveCheckPosted = true
        viewModelScope.launch(Dispatchers.Main) { keepAliveCheckPosted = false; checkKeepAlive() }
    }

    /** The one place that starts, updates or stops the keep-alive service, from the current state. */
    private fun checkKeepAlive() {
        // After deep sleep a hold may be past its deadline; ending it runs this check again from the change.
        if (portForwards.checkDeadlines()) return
        val current = state.value
        val reconnectPending = autoReconnectJob?.isActive == true || reconnectWhenOnline != null ||
            // A drop that [connectionDropped] has not handled yet may still start a reconnect.
            liveEnvironment != null ||
            // An attempt that replaces a kept connection (reconnect, route or machine switch); a first connect does not start it.
            (keepAlive && _working.value)
        // A forward opened in an external browser holds the connection while it lives (setting off or not); it never
        // holds a reconnect, since nothing restarts a forward after a drop.
        val forwardHold = portForwards.externalForwards.value.isNotEmpty() && current.connection == ConnectionStatus.Connected
        val wanted = !keepAliveLatched && (keepAliveWanted(_keepConnected.value, current.connection, reconnectPending) || forwardHold)
        if (!wanted) keepAliveRefused = false
        // Keeps the shown name while the client has no environment, so fallbacks never flap the notification.
        val machine = current.environment?.name ?: keepAliveMachine ?: liveEnvironment?.name ?: reconnectWhenOnline?.name ?: selectedEnvironment?.name ?: "your machine"
        val reconnecting = current.connection != ConnectionStatus.Connected
        val changed = machine != keepAliveMachine || reconnecting != keepAliveReconnecting
        when (keepAliveStep(wanted, keepAlive, foreground, keepAliveRefused, changed)) {
            KeepAliveStep.Start -> {
                val started = try { keepAliveControl.start(getApplication(), machine, reconnecting) } catch (_: Exception) { false }
                if (started) { keepAliveMachine = machine; keepAliveReconnecting = reconnecting; _keepAliveActive.value = true }
                else { keepAliveRefused = true; stopKeepAlive(releaseBackground = true) }
            }
            KeepAliveStep.Update -> {
                // A running service only replaces its notification; a failure there leaves it running as it was.
                val updated = try { keepAliveControl.start(getApplication(), machine, reconnecting) } catch (_: Exception) { false }
                if (updated) { keepAliveMachine = machine; keepAliveReconnecting = reconnecting }
            }
            KeepAliveStep.Stop -> stopKeepAlive(releaseBackground = true)
            KeepAliveStep.None -> {}
        }
    }

    /** Stops the service; in the background [releaseBackground] then applies the rules of a pause without keep-alive. */
    private fun stopKeepAlive(releaseBackground: Boolean) {
        val wasActive = keepAlive
        _keepAliveActive.value = false; keepAliveMachine = null; keepAliveReconnecting = false
        if (wasActive) try { keepAliveControl.stop(getApplication()) } catch (_: Exception) {}
        if (wasActive && releaseBackground && !foreground) releaseInBackground()
    }

    override fun onCleared() {
        flushComposerDrafts() // on the store's own scope; viewModelScope is already cancelled
        // Nothing observes the connection after this, so no alert could be cleared any more.
        agentAlerts.cancelAll()
        if (keepAlive) { _keepAliveActive.value = false; try { keepAliveControl.stop(getApplication()) } catch (_: Exception) {} }
        widgetSync.close()
        probeJob?.cancel()
        screenSessions.close()
        tunnelMonitor?.cancel()
        val closing = tunnel; tunnel = null
        if (closing != null) Thread({ closing.close() }, "trefoil-ssh-cleanup").apply { isDaemon = true; start() }
        // Taken now, written on the cache's own scope; nothing is captured after it.
        shellCacheWriter.close(state.value.environment?.id)
        client.close()
    }

    private companion object {
        const val LAST_ENVIRONMENT = "lastEnvironmentId"
        const val KEEP_CONNECTED = "keepConnectedInBackground"
        const val AGENT_NOTIFICATIONS = "agentNotifications"
        const val HIDE_TEST_FILES = "changesHideTestFiles"
        const val SSH_FAILURE = "SSH connection failed. Check the address, login and running T3 server."
    }
}


/** Starts and stops the keep-alive service; [ServiceKeepAliveControl] in production. */
internal interface KeepAliveControl {
    /** The service is in the foreground. */
    val running: StateFlow<Boolean>
    /** An accepted start has not reached the service yet. */
    val starting: StateFlow<Boolean>
    /** Starts the service or updates its notification; false when the OS refuses the start. */
    fun start(context: Context, machineName: String, reconnecting: Boolean): Boolean
    fun stop(context: Context)
}

internal object ServiceKeepAliveControl : KeepAliveControl {
    override val running get() = ConnectionKeepAlive.running
    override val starting get() = ConnectionKeepAlive.starting
    override fun start(context: Context, machineName: String, reconnecting: Boolean) = ConnectionKeepAlive.start(context, machineName, reconnecting)
    override fun stop(context: Context) = ConnectionKeepAlive.stop(context)
}

/**
 * The keep-alive service should run: opted in, and a connection is live or being re-established. A plain first
 * Connecting is not enough; [reconnectPending] covers attempts that continue a kept connection.
 */
internal fun keepAliveWanted(enabled: Boolean, connection: ConnectionStatus, reconnectPending: Boolean): Boolean =
    enabled && (reconnectPending || connection == ConnectionStatus.Connected || connection == ConnectionStatus.Reconnecting)

internal enum class KeepAliveStep { Start, Update, Stop, None }

/**
 * What to do with the service. It starts only in the foreground (Android forbids background starts), never again after
 * a refused start; a running service updates its notification when [changed] (also in the background, no new start);
 * it stops as soon as it is no longer [wanted].
 */
internal fun keepAliveStep(wanted: Boolean, active: Boolean, foreground: Boolean, refused: Boolean, changed: Boolean): KeepAliveStep = when {
    !wanted -> if (active) KeepAliveStep.Stop else KeepAliveStep.None
    active -> if (changed) KeepAliveStep.Update else KeepAliveStep.None
    foreground && !refused -> KeepAliveStep.Start
    else -> KeepAliveStep.None
}

internal enum class PlanOutcome { Connected, NeedsSshLogin }

/** The machine was forgotten while connecting: the whole connection plan stops, no route may connect for it. */
internal class MachineRemovedException : Exception("This machine was removed while connecting")
/** The cold-start connect found its machine unreachable; it ends without a notice. */
private class QuietStartupFailure(val environmentId: String) : Exception("Machine unreachable")
/**
 * The threads that settle pending launches of the shown machine: only rows the current connection's shell confirmed
 * ([ClientState.shellLive]); null while connecting or showing cached or retained rows, which may list a deleted thread.
 */
internal fun launchEvidence(state: ClientState): Pair<String, Set<String>>? =
    state.environment?.id?.takeIf { state.connection == ConnectionStatus.Connected && state.shellLive }?.let { it to state.threads.mapTo(HashSet()) { t -> t.id } }
/** A direct connect that never got as far as a successful session check, for reachability reasons only ([ConnectFailure]). */
internal fun Exception.isUnreachableFailure(): Boolean = this is PairingException && failure == ConnectFailure.Unreachable

/** Every route before SSH failed, SSH is the last option and its login is not saved. */
internal class SshLoginRequired(val input: SshConnectionInput) : Exception("SSH login required")

internal fun SshConnectionInput.hasSavedLogin(): Boolean = when (authMethod) {
    SshAuthMethod.Password -> password.isNotEmpty()
    SshAuthMethod.PrivateKey -> privateKey.isNotEmpty()
}

private fun routeLabel(kind: RouteKind) = when (kind) {
    RouteKind.Lan -> "local network"
    RouteKind.Tailnet -> "Tailscale"
    RouteKind.Public -> "HTTPS address"
    RouteKind.Ssh -> "SSH"
    RouteKind.Tunnel -> "T3 Connect tunnel"
}

/** "Trying local network…", or "Trying local network, then T3 Connect tunnel…" for a run of several kinds. */
internal fun routeStage(routes: List<EnvironmentRoute>): String =
    "Trying " + routes.map { routeLabel(it.kind) }.distinct().joinToString(", then ") + "…"

/**
 * Walks [plan] in order: each run of direct routes goes to [direct] in one call; an Ssh step runs [ssh] when
 * [sshLoginSaved]. Without a saved login SSH is skipped when a later route exists, and as the last option the walk
 * returns [PlanOutcome.NeedsSshLogin]. A failed step continues with the next; SSH host key problems (the user's decision)
 * and a machine removed meanwhile stop the walk. When every step fails, the last failure is thrown.
 */
internal suspend fun walkConnectionPlan(
    plan: List<EnvironmentRoute>,
    sshLoginSaved: Boolean,
    stage: (String) -> Unit,
    direct: suspend (List<EnvironmentRoute>) -> Unit,
    ssh: suspend () -> Unit,
): PlanOutcome {
    if (plan.isEmpty()) throw PairingException("This machine has no saved address. Pair it again.")
    var failure: Exception? = null
    var index = 0
    while (index < plan.size) {
        currentCoroutineContext().ensureActive()
        if (plan[index].kind == RouteKind.Ssh) {
            index++
            if (!sshLoginSaved) { if (index < plan.size) continue else return PlanOutcome.NeedsSshLogin }
            try { ssh(); return PlanOutcome.Connected }
            catch (e: CancellationException) { throw e }
            catch (e: SshHostKeyException) { throw e }
            catch (e: MachineRemovedException) { throw e }
            catch (e: Exception) { failure = e }
        } else {
            val run = plan.drop(index).takeWhile { it.kind != RouteKind.Ssh }
            index += run.size
            stage(routeStage(run))
            try { direct(run); return PlanOutcome.Connected }
            catch (e: CancellationException) { throw e }
            catch (e: MachineRemovedException) { throw e }
            catch (e: Exception) { failure = e }
        }
    }
    throw failure ?: PairingException("Could not connect")
}

/** Hints applied to the matching saved machine, or null when they change nothing (and nothing needs writing). */
internal fun applyRouteHints(environments: List<SavedEnvironment>, hints: RouteHints): List<SavedEnvironment>? {
    val current = environments.firstOrNull { it.id == hints.environmentId } ?: return null
    val updated = current.withRouteHints(hints)
    return if (updated == current) null else environments.map { if (it.id == current.id) updated else it }
}

/** The vault's learned routes for the same machine win over this copy's (re-pairing, or a copy older than the vault). */
internal fun SavedEnvironment.withLearnedRoutesOf(environments: List<SavedEnvironment>): SavedEnvironment =
    environments.firstOrNull { it.id == id }?.let { copy(routes = it.routes) } ?: this

/**
 * A saved machine reconnected as [configured], a copy taken before the network attempt: its name and SSH profile apply,
 * while routes, LAN HTTP consent and token stay the vault's current ones (a revoked consent is never granted again),
 * and a machine forgotten meanwhile is not re-created.
 */
internal fun List<SavedEnvironment>.withReconnected(configured: SavedEnvironment): List<SavedEnvironment> = map { stored ->
    if (stored.id != configured.id) stored
    else configured.copy(routes = stored.routes, allowInsecureHttp = stored.allowInsecureHttp).withTokenOf(stored)
}

/**
 * Settles composer drafts around [ThreadDraftController]'s sends. An accepted reply clears the thread's Reply draft
 * unless it was edited while the send was in flight. A launch first records its ids on the NewThread draft (so a
 * relaunch after an uncertain outcome, even after a restart, reuses them) and, once accepted, clears the draft
 * unless it was edited since. Failed or uncertain sends keep the drafts.
 */
internal class ComposerDraftSettlingClient(private val inner: TrefoilClient, private val store: ComposerDraftStore) : TrefoilClient by inner {
    override suspend fun sendMessage(text: String) = settling(text) { inner.sendMessage(text) }
    override suspend fun sendMessage(text: String, mode: FollowUpMode) = settling(text) { inner.sendMessage(text, mode) }

    private suspend fun settling(text: String, send: suspend () -> Unit) {
        val state = inner.state.value
        val key = state.environment?.id?.let { environment -> state.selectedThreadId?.let { ComposerDraftKey.Reply(environment, it) } }
        // Only a draft that holds exactly the sent text may be cleared by its ack.
        val revision = key?.let { store.revisionIfText(it, text) }
        send()
        if (key != null && revision != null) store.clearIfUnchanged(key, revision)
    }

    /**
     * Picks the launch identity after the stored drafts are loaded: a pending launch of the same text is resent
     * with its ids (the server replays it). A pending launch of different text is not reused, since the server would
     * replay the original and drop the new prompt: once its thread is listed it settles and the new text launches
     * with fresh ids, otherwise this fails without dispatching. The identity is persisted before dispatch; if that
     * write fails the launch still goes out (fail open: only a retry after a restart loses its identity).
     */
    override suspend fun launchThread(draft: ThreadDraft, firstMessage: String): String {
        val environment = inner.state.value.environment?.id ?: return inner.launchThread(draft, firstMessage)
        val key = ComposerDraftKey.NewThread(environment, draft.projectId)
        store.awaitLoaded()
        var launch = draft
        var resend = false
        val pending = store.get(key)?.pendingLaunch
        if (pending != null && ComposerDraftStore.sameSubmission(pending.text, firstMessage)) {
            launch = draft.copy(threadId = pending.threadId, commandId = pending.commandId); resend = true
        } else if (pending != null) {
            val state = inner.state.value
            if (state.connection != ConnectionStatus.Connected || !state.shellLive || state.environment?.id != environment || state.threads.none { it.id == pending.threadId })
                throw IllegalStateException(UNCONFIRMED_LAUNCH)
            store.settleLaunched(environment, pending.threadId)
            if (draft.threadId == pending.threadId || draft.commandId == pending.commandId)
                launch = draft.copy(threadId = UUID.randomUUID().toString(), commandId = UUID.randomUUID().toString())
        }
        store.recordLaunch(key, launch.threadId, launch.commandId, firstMessage)
        val id = try { inner.launchThread(launch, firstMessage) }
        catch (e: CancellationException) { throw e }
        catch (e: UncertainOutcomeException) { throw e }
        catch (e: Exception) { if (!resend) store.dropLaunch(key, launch.commandId); throw e }
        store.settleLaunched(environment, launch.threadId)
        return id
    }

    private companion object {
        const val UNCONFIRMED_LAUNCH = "An earlier start of this draft is not confirmed yet. Reconnect so it can be checked, send the original prompt again, or discard the draft."
    }
}

/** Counts the mutations [ThreadDraftController] sends, so a route switch can wait for them to settle. */
internal class MutationTrackingClient(private val inner: TrefoilClient, private val inFlight: AtomicInteger) : TrefoilClient by inner {
    override suspend fun sendMessage(text: String) = inFlight.tracked { inner.sendMessage(text) }
    override suspend fun sendMessage(text: String, mode: FollowUpMode) = inFlight.tracked { inner.sendMessage(text, mode) }
    override suspend fun launchThread(draft: ThreadDraft, firstMessage: String) = inFlight.tracked { inner.launchThread(draft, firstMessage) }
    override suspend fun setRuntimeMode(mode: RuntimeMode) = inFlight.tracked { inner.setRuntimeMode(mode) }
    override suspend fun setModel(model: ModelOption) = inFlight.tracked { inner.setModel(model) }
    override suspend fun setModelOption(id: String, value: Any) = inFlight.tracked { inner.setModelOption(id, value) }
    override suspend fun setModelFavorite(key: ModelKey, favorite: Boolean) = inFlight.tracked { inner.setModelFavorite(key, favorite) }
}

internal inline fun <T> AtomicInteger.tracked(block: () -> T): T {
    incrementAndGet()
    try { return block() } finally { decrementAndGet() }
}
