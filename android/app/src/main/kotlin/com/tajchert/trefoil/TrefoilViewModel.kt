package com.tajchert.trefoil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.T3Client
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
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _notice.value = failure }
        }
    }

    private fun connectionAction(failure: String, block: suspend () -> Unit) {
        connectionEpoch++
        connectionJob?.cancel()
        _sshHostKey.value = null; _sshLogin.value = null; pendingInput = null
        connectionJob = viewModelScope.launch {
            initialized.await()
            connectionMutex.withLock {
                if (!foreground) return@withLock
                _working.value = true; _notice.value = null
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: SshHostKeyException) {
                    if (e.changed) {
                        pendingInput = null
                        _notice.value = "SSH host key changed for ${e.presented.host}:${e.presented.port}. Connection blocked. Verify the server key independently before changing your saved trust."
                    } else { _sshHostKey.value = e.presented }
                }
                catch (e: SshConnectionException) { pendingInput = null; _notice.value = e.message ?: failure }
                catch (_: Exception) { pendingInput = null; _notice.value = failure }
                finally { _working.value = false }
            }
        }
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

    fun pair(url: String, name: String) = connectionAction("Pairing failed. Check the link, server address and certificate, then try again.") {
        disconnectCurrent()
        val environment = client.pair(url, name)
        vaultMutex.withLock { save(_environments.value.filterNot { it.id == environment.id } + environment) }
        selectedEnvironment = environment
        client.connect(environment)
    }

    fun connect(environment: SavedEnvironment) {
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

    fun connectSsh(input: SshConnectionInput) = connectionAction("SSH connection failed. Check the address, login and running T3 server.") {
        val previousId = state.value.environment?.id
        val previousThread = state.value.selectedThreadId
        disconnectCurrent()
        val normalized = input.copy(host = input.host.trim(), username = input.username.trim())
        val saved = normalized.savedEnvironmentId?.let { id -> _environments.value.firstOrNull { it.id == id && it.ssh != null } }
        val expected = hostKeys.firstOrNull { it.host.equals(normalized.host, ignoreCase = true) && it.port == normalized.port }?.fingerprint
            ?: saved?.ssh?.takeIf { it.host.equals(normalized.host, ignoreCase = true) && it.port == normalized.port }?.fingerprint
        pendingInput = normalized
        val opened = connector.open(normalized, expected)
        pendingInput = null
        var adopted = false
        try {
            coroutineContext.ensureActive()
            // A saved identity is verified by T3Client before its bearer token is sent.
            val environment = if (saved == null) {
                val credential = opened.issuePairingCredential()
                val link = opened.baseUrl.trimEnd('/') + "/pair#token=" + java.net.URLEncoder.encode(credential, "UTF-8")
                client.pair(link, normalized.name)
            } else saved.copy(baseUrl = opened.baseUrl)
            val configured = environment.copy(name = normalized.name, ssh = normalized.savedProfile(opened.hostKey))
            client.connect(configured)
            if (previousId == configured.id) previousThread?.let { client.selectThread(it) }
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
    }

    fun selectThread(id: String) = action("Could not open the thread. Reconnect and try again.") { client.selectThread(id) }
    fun send(text: String) = action("Message outcome is uncertain. Check the conversation before sending again.") { client.sendMessage(text) }
    fun interrupt() = action("Interrupt outcome is uncertain. Check the run status.") { client.interruptRun() }
    fun respond(id: String, decision: String, answers: Map<String,List<String>>) = action("Response outcome is uncertain. Check the request status before responding again.") { client.respond(id, decision, answers) }
    fun createThread(projectId: String, title: String, model: ModelOption) = action("Thread creation outcome is uncertain. Check the thread list before retrying.") {
        val id = client.createThread(projectId, title, model); client.selectThread(id)
    }

    fun reconnect() {
        val environment = selectedEnvironment ?: state.value.environment?.forStorage() ?: return
        val threadId = state.value.selectedThreadId
        if (environment.ssh != null) connect(environment)
        else connectionAction("Could not reconnect. Check the server or pair again.") {
            disconnectCurrent(); client.connect(environment)
            threadId?.let { client.selectThread(it) }
        }
    }

    fun disconnect() {
        resumeEnvironment = null; cancelSsh()
        action("Could not disconnect.") { connectionMutex.withLock { disconnectCurrent() } }
    }
    fun forget(environment: SavedEnvironment) = action("Could not remove saved credentials.") {
        if (selectedEnvironment?.id == environment.id || state.value.environment?.id == environment.id) {
            cancelSsh(); resumeEnvironment = null
            connectionMutex.withLock { disconnectCurrent(); client.forgetEnvironment(); selectedEnvironment = null }
        }
        initialized.await()
        vaultMutex.withLock { save(_environments.value.filterNot { it.id == environment.id }) }
    }
    fun pause() {
        foreground = false
        resumeEnvironment = if (state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)) selectedEnvironment else null
        if (sshFilePickerActive) { connectionEpoch++; connectionJob?.cancel(); _working.value = false } else cancelSsh()
        viewModelScope.launch { connectionMutex.withLock { disconnectCurrent() } }
    }
    fun resume() {
        foreground = true
        val environment = resumeEnvironment ?: return
        resumeEnvironment = null
        if (_sshLogin.value == null) connect(environment)
    }
    override fun onCleared() {
        tunnelMonitor?.cancel()
        val closing = tunnel; tunnel = null
        if (closing != null) Thread({ closing.close() }, "trefoil-ssh-cleanup").apply { isDaemon = true; start() }
        client.close()
    }
}
