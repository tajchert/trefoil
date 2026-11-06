package com.tajchert.trefoil.core.model

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SavedEnvironment(val id: String, val name: String, val baseUrl: String, val accessToken: String, val ssh: SshProfile? = null, val allowInsecureHttp: Boolean = false) {
    override fun toString() = "SavedEnvironment(credentials=***)"
}
data class Project(val id: String, val title: String, val path: String = "")
data class ThreadSummary(val id: String, val projectId: String, val title: String, val status: String = "idle")
data class ConversationMessage(val id: String, val role: String, val text: String, val streaming: Boolean = false)
data class ToolActivity(val id: String, val title: String, val detail: String = "")
data class Question(val id: String, val title: String, val options: List<String> = emptyList(), val multiple: Boolean = false, val required: Boolean = true)
data class RuntimeRequest(val id: String, val title: String, val detail: String = "", val questions: List<Question> = emptyList(), val canApprove: Boolean = true)
data class ModelOption(val instanceId: String, val model: String, val label: String = model)
enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting, Error }
data class ClientState(
    val environment: SavedEnvironment? = null,
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val projects: List<Project> = emptyList(),
    val threads: List<ThreadSummary> = emptyList(),
    val selectedThreadId: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val activities: List<ToolActivity> = emptyList(),
    val requests: List<RuntimeRequest> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val activeRunId: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val canWrite: Boolean = false,
)

/** All commands fail explicitly; implementations must never replay uncertain mutations. */
interface TrefoilClient {
    val state: StateFlow<ClientState>
    suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean = false): SavedEnvironment
    suspend fun connect(environment: SavedEnvironment)
    suspend fun disconnect()
    suspend fun selectThread(threadId: String)
    suspend fun sendMessage(text: String)
    suspend fun interruptRun()
    suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>> = emptyMap())
    suspend fun createThread(projectId: String, title: String, model: ModelOption): String
    suspend fun reconnect()
    fun close()
}
