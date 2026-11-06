package com.tajchert.trefoil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.security.EnvironmentVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TrefoilViewModel(application: Application) : AndroidViewModel(application) {
    private val client = T3Client(viewModelScope)
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
    private var resumeConnection = false

    init {
        viewModelScope.launch {
            try { vaultMutex.withLock { _environments.value = withContext(Dispatchers.IO) { vault.read() } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _notice.value = "Saved credentials could not be unlocked. Pair this device again." }
        }
    }
    private fun action(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _notice.value = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _notice.value = failure }
        }
    }
    private suspend fun save(environments: List<SavedEnvironment>) {
        withContext(Dispatchers.IO) { vault.write(environments) }
        _environments.value = environments
    }
    fun pair(url: String, name: String) = action("Pairing failed. Check the link, server address and certificate, then try again.") {
        connectionMutex.withLock {
            _working.value = true
            try {
                val environment = client.pair(url, name)
                vaultMutex.withLock { save(_environments.value.filterNot { it.id == environment.id } + environment) }
                client.connect(environment)
            } finally { _working.value = false }
        }
    }
    fun connect(environment: SavedEnvironment) = action("Could not connect. Check the server or pair again if access has expired.") {
        connectionMutex.withLock { client.connect(environment) }
    }
    fun selectThread(id: String) = action("Could not open the thread. Reconnect and try again.") { client.selectThread(id) }
    fun send(text: String) = action("Message outcome is uncertain. Check the conversation before sending again.") { client.sendMessage(text) }
    fun interrupt() = action("Interrupt outcome is uncertain. Check the run status.") { client.interruptRun() }
    fun respond(id: String, decision: String, answers: Map<String,List<String>>) = action("Response outcome is uncertain. Check the request status before responding again.") { client.respond(id, decision, answers) }
    fun createThread(projectId: String, title: String, model: ModelOption) = action("Thread creation outcome is uncertain. Check the thread list before retrying.") {
        val id = client.createThread(projectId, title, model)
        client.selectThread(id)
    }
    fun reconnect() = action("Could not reconnect. Check the server or pair again.") { connectionMutex.withLock { client.reconnect() } }
    fun disconnect() = action("Could not disconnect.") { connectionMutex.withLock { client.disconnect() } }
    fun forget(environment: SavedEnvironment) = action("Could not remove saved credentials.") {
        connectionMutex.withLock {
            if (state.value.environment?.id == environment.id) client.disconnect()
            vaultMutex.withLock { save(_environments.value.filterNot { it.id == environment.id }) }
        }
    }
    fun pause() = action("Connection paused.") {
        connectionMutex.withLock {
            resumeConnection = state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)
            if (resumeConnection) client.disconnect()
        }
    }
    fun resume() = action("Could not reconnect. Check the server or pair again.") {
        connectionMutex.withLock {
            if (resumeConnection) { resumeConnection = false; client.reconnect() }
        }
    }
    override fun onCleared() { client.close() }
}
