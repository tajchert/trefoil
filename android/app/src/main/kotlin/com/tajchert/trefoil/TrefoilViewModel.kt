package com.tajchert.trefoil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.EnvironmentProbe
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.core.session.ThreadDraftController
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshTunnel
import com.tajchert.trefoil.core.ssh.SshTunnelConnector
import com.tajchert.trefoil.security.EnvironmentVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class TrefoilViewModel(application: Application) : AndroidViewModel(application) {
    private val client = T3Client(viewModelScope)
    val screenSessions = com.tajchert.trefoil.ui.screens.ScreenSessionCoordinator(viewModelScope, client)
    val fileDownloads = com.tajchert.trefoil.downloads.FileDownloadCoordinator(viewModelScope, client,
        com.tajchert.trefoil.downloads.MediaStoreDownloadStore(application))
    private val connector = SshTunnelConnector()
    private val vault = EnvironmentVault(application)
    private val vaultMutex = Mutex()
    private val connectionMutex = Mutex()
    val state = client.state
    private val _environments = MutableStateFlow<List<SavedEnvironment>>(emptyList())
    val environments = _environments.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val _connectionAttempt = MutableStateFlow(ConnectionAttempt())
    val connectionAttempt = _connectionAttempt.asStateFlow()
    private val drafts = ThreadDraftController(viewModelScope, client) { _notice.value = it }
    /** Unsent new conversation; while non-null the UI shows the draft instead of the selected thread. */
    val draft: StateFlow<ThreadDraft?> = drafts.draft
    /** Emits once for every send (draft or thread) that did not deliver; the composer must leave its pending state. */
    val sendFailed: SharedFlow<Unit> = drafts.sendFailed
    private val _sshHostKey = MutableStateFlow<SshHostKey?>(null)
    val sshHostKey = _sshHostKey.asStateFlow()
    private val _sshLogin = MutableStateFlow<SshConnectionInput?>(null)
    val sshLogin = _sshLogin.asStateFlow()
    private var hostKeys = emptyList<SshHostKey>()
    private var pendingInput: SshConnectionInput? = null
    private var tunnel: SshTunnel? = null
    private var tunnelMonitor: Job? = null
    private var connectionJob: Job? = null
    private var selectedEnvironment: SavedEnvironment? = null
    private var resumeEnvironment: SavedEnvironment? = null
    private var foreground = true
    private var connectionEpoch = 0L
    var sshFilePickerActive = false
    private val prefs = application.getSharedPreferences("trefoil.ui", android.content.Context.MODE_PRIVATE)
    private val environmentProbe = EnvironmentProbe()
    private val prober = MachineProber(
        direct = { environment, baseUrl -> environmentProbe.probe(environment, baseUrl) },
        openSsh = { input, pinned -> connector.open(input, pinned).let { opened -> object : ProbeTunnel {
            override val baseUrl = opened.baseUrl
            override fun close() = opened.close()
        } } },
    )
    private val _machineProbes = MutableStateFlow<Map<String, MachineProbe>>(emptyMap())
    /** Reachability of saved machines other than the active connection; never mutates a server. */
    val machineProbes = _machineProbes.asStateFlow()
    private var probeJob: Job? = null
    /** Auto-connect is considered once per app launch, until a decision is made or the user acts. */
    private var autoConnectPending = true

    private val initialized = viewModelScope.async {
        try {
            vaultMutex.withLock {
                _environments.value = withContext(Dispatchers.IO) { vault.read() }
                hostKeys = withContext(Dispatchers.IO) { vault.readHostKeys() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _notice.value = "Saved credentials could not be unlocked. Pair this device again." }
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

    private fun connectionAction(failure: String, kind: ConnectionKind = ConnectionKind.Saved, block: suspend () -> Unit) {
        val epoch = ++connectionEpoch
        autoConnectPending = false
        connectionJob?.cancel()
        _sshHostKey.value = null
        if (kind != ConnectionKind.Ssh) _sshLogin.value = null
        pendingInput = null
        _notice.value = null; _working.value = true
        _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Testing, "Testing connection…")
        connectionJob = viewModelScope.launch {
            try {
                initialized.await()
                connectionMutex.withLock {
                    if (!foreground || epoch != connectionEpoch) return@withLock
                    try {
                        block()
                        if (epoch == connectionEpoch) {
                            _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Succeeded, "Connected")
                            _sshLogin.value = null
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
                            } else {
                                _sshHostKey.value = e.presented
                                _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.AwaitingTrust, "Confirm the SSH host fingerprint to continue.")
                            }
                        }
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
    }

    private fun connectionFailed(epoch: Long, kind: ConnectionKind, message: String) {
        _notice.value = message
        _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Failed, message)
    }

    private fun connectionStage(message: String) {
        _connectionAttempt.update { it.copy(phase = ConnectionPhase.Testing, message = message) }
    }

    private suspend fun save(environments: List<SavedEnvironment>) {
        val durable = environments.map { it.forStorage() }
        withContext(Dispatchers.IO) { vault.write(durable) }
        _environments.value = durable
    }

    private suspend fun disconnectCurrent() {
        tunnelMonitor?.cancel(); tunnelMonitor = null
        client.disconnect()
        val closing = tunnel; tunnel = null
        withContext(NonCancellable + Dispatchers.IO) { closing?.close() }
    }

    fun pair(url: String, name: String, allowInsecureHttp: Boolean) = connectionAction("Pairing failed. Check the link, server address and certificate, then try again.", ConnectionKind.Pairing) {
        clearDraft()
        disconnectCurrent()
        connectionStage("Testing pairing link and exchanging credentials…")
        val environment = client.pair(url, name, allowInsecureHttp)
        vaultMutex.withLock { save(_environments.value.filterNot { it.id == environment.id } + environment) }
        selectedEnvironment = environment
        connectionStage("Opening T3 session…")
        client.connect(environment)
    }

    fun connect(environment: SavedEnvironment) {
        if (draft.value != null && environment.id != drafts.environmentId) clearDraft()
        if (environment.ssh != null) {
            val input = environment.sshInput()
            if ((input.authMethod == SshAuthMethod.Password && input.password.isEmpty()) ||
                (input.authMethod == SshAuthMethod.PrivateKey && input.privateKey.isEmpty())) {
                cancelSsh(); _sshLogin.value = input
            } else connectSsh(input)
        } else connectionAction("Could not connect. Check the server or pair again if access has expired.") {
            disconnectCurrent(); selectedEnvironment = environment; client.connect(environment)
        }
    }

    fun connectSsh(input: SshConnectionInput) = connectionAction("SSH connection failed. Check the address, login and running T3 server.", ConnectionKind.Ssh) {
        sshInputValidationError(input)?.let { throw SshConnectionException(it) }
        disconnectCurrent()
        val normalized = input.copy(host = input.host.trim(), username = input.username.trim())
        val saved = normalized.savedEnvironmentId?.let { id -> _environments.value.firstOrNull { it.id == id && it.ssh != null } }
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
            vaultMutex.withLock { save(_environments.value.filterNot { it.id == configured.id } + configured) }
            selectedEnvironment = configured.forStorage()
            tunnel = opened; adopted = true
            tunnelMonitor = viewModelScope.launch {
                opened.active.filter { !it }.first()
                connectionMutex.withLock {
                    if (tunnel === opened) {
                        client.disconnect(); tunnel = null
                        withContext(NonCancellable + Dispatchers.IO) { opened.close() }
                        _notice.value = "SSH tunnel closed. Reconnect to refresh; pending command outcomes may be uncertain."
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
        clearDraft()
        action("Could not open the thread. Reconnect and try again.") { client.selectThread(id) }
    }
    /**
     * With a draft, the first message creates the thread (`launchThread`); otherwise it goes to the
     * selected thread. Every failed or uncertain send emits [sendFailed]. An uncertain launch keeps
     * the draft and its ids so it can be reconciled or explicitly resent without a duplicate.
     */
    fun send(text: String) = drafts.send(text)
    fun loadActivity(id: String) = action("Could not load activity output. Reconnect and try again.") { client.loadActivity(id) }
    fun interrupt() = action("Interrupt outcome is uncertain. Check the run status.") { client.interruptRun() }
    fun stopSubagent(id: String) = action("Agent stop failed or its outcome is uncertain. Check the agent before trying again.") { client.stopSubagent(id) }
    fun threadAction(target: ThreadTarget, operation: ThreadAction, value: String?) = action("Could not update thread. Check its status before trying again.") { client.threadAction(target, operation, value) }
    fun undoSettlement(undo: SettlementUndo) = action("Could not undo settlement. Check the thread status.") { client.undoSettlement(undo) }
    fun respond(id: String, decision: String, answers: Map<String,List<String>>) = action("Response outcome is uncertain. Check the request status before responding again.") { client.respond(id, decision, answers) }
    /** Opens a local draft; no server mutation until the first send. */
    fun startDraft(draft: ThreadDraft) = drafts.start(draft)
    fun cancelDraft() = clearDraft()
    private fun clearDraft() = drafts.clear()
    /** Draft: updates locally. Selected thread: dispatches `thread.runtime-mode.set`. */
    fun setRuntimeMode(mode: RuntimeMode) = drafts.setRuntimeMode(mode)
    /** Draft: updates locally. Selected thread: dispatches `thread.model-selection.set`. */
    fun setModel(model: ModelOption) = drafts.setModel(model)

    /** Keeps the draft: reconnecting is the same environment, and an uncertain launch is reconciled after it. */
    fun reconnect() {
        val environment = selectedEnvironment ?: state.value.environment?.forStorage() ?: return
        if (environment.ssh != null) connect(environment)
        else connectionAction("Could not reconnect. Check the server or pair again.") {
            disconnectCurrent(); client.connect(environment)
        }
    }

    fun disconnect() {
        clearDraft()
        screenSessions.close()
        resumeEnvironment = null; cancelSsh()
        action("Could not disconnect.") { connectionMutex.withLock { disconnectCurrent() } }
    }
    fun forget(environment: SavedEnvironment) = action("Could not remove saved credentials.") {
        if (drafts.environmentId == environment.id || state.value.environment?.id == environment.id) clearDraft()
        if (selectedEnvironment?.id == environment.id || state.value.environment?.id == environment.id) cancelSsh()
        if (resumeEnvironment?.id == environment.id) resumeEnvironment = null
        _machineProbes.update { it - environment.id }
        if (prefs.getString(LAST_ENVIRONMENT, null) == environment.id) prefs.edit().remove(LAST_ENVIRONMENT).apply()
        connectionMutex.withLock {
            if (selectedEnvironment?.id == environment.id || state.value.environment?.id == environment.id) {
                disconnectCurrent(); selectedEnvironment = null
            }
            client.forgetEnvironment(environment.id)
        }
        initialized.await()
        vaultMutex.withLock { save(_environments.value.filterNot { it.id == environment.id }) }
    }
    fun pause() {
        screenSessions.pause()
        foreground = false
        probeJob?.cancel(); probeJob = null
        _machineProbes.update { probes -> probes.mapValues { (_, probe) -> if (probe == MachineProbe.Checking) MachineProbe.Unknown else probe } }
        resumeEnvironment = if (state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)) selectedEnvironment else null
        if (sshFilePickerActive) { connectionEpoch++; connectionJob?.cancel(); _working.value = false } else cancelSsh()
        viewModelScope.launch { connectionMutex.withLock { disconnectCurrent() } }
    }
    fun resume() {
        foreground = true
        screenSessions.resume()
        val environment = resumeEnvironment
        resumeEnvironment = null
        if (environment != null && _sshLogin.value == null) connect(environment)
        probeMachines(skipId = environment?.id)
    }

    /**
     * Probes every saved machine except the active or resuming one, in parallel. On the first
     * launch it may then connect the last-used machine, or the only reachable one, through the
     * normal [connect] path, unless the user started a connection in the meantime.
     */
    private fun probeMachines(skipId: String?) {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            initialized.await()
            if (!foreground) return@launch
            val epoch = connectionEpoch
            val keys = hostKeys
            val live = state.value.environment?.id?.takeIf {
                state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)
            }
            val targets = _environments.value.filter { it.id != skipId && it.id != live }
            _machineProbes.update { probes ->
                probes.filterKeys { id -> _environments.value.any { it.id == id } } + targets.associate { it.id to MachineProbe.Checking }
            }
            fun considerAutoConnect() {
                if (!autoConnectPending) return
                if (epoch != connectionEpoch || !foreground || _sshLogin.value != null ||
                    state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)) {
                    autoConnectPending = false; return
                }
                when (val decision = autoConnectDecision(prefs.getString(LAST_ENVIRONMENT, null), _environments.value, _machineProbes.value)) {
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
                        val result = prober.probe(environment, keys)
                        _machineProbes.update { probes -> if (environment.id in probes) probes + (environment.id to result) else probes }
                        considerAutoConnect()
                    }
                }
            }
            considerAutoConnect()
        }
    }
    override fun onCleared() {
        probeJob?.cancel()
        screenSessions.close()
        tunnelMonitor?.cancel()
        val closing = tunnel; tunnel = null
        if (closing != null) Thread({ closing.close() }, "trefoil-ssh-cleanup").apply { isDaemon = true; start() }
        client.close()
    }

    private companion object { const val LAST_ENVIRONMENT = "lastEnvironmentId" }
}
