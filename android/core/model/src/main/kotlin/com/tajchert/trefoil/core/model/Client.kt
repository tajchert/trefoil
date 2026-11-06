package com.tajchert.trefoil.core.model

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SavedEnvironment(val id: String, val name: String, val baseUrl: String, val accessToken: String, val ssh: SshProfile? = null, val allowInsecureHttp: Boolean = false) {
    override fun toString() = "SavedEnvironment(credentials=***)"
}
data class Project(val id: String, val title: String, val path: String = "")
data class ThreadSummary(
    val id: String, val projectId: String, val title: String, val status: String = "idle",
    val createdAt: String? = null, val updatedAt: String? = null,
    val latestUserMessageAt: String? = null, val latestUserAuthoredMessageAt: String? = null,
    val archivedAt: String? = null, val archived: Boolean = false,
    val pinnedAt: String? = null, val settledAt: String? = null, val settledOverride: String? = null,
    val activeOrderKey: String? = null, val pinOrderKey: String? = null,
    val pendingRequestKind: String? = null, val latestMessage: String = "",
    val branch: String? = null, val model: String? = null,
    val blockedOnThreadWait: Boolean? = null, val activityRunStatus: String? = null,
    val snoozedUntil: String? = null, val unsettledAt: String? = null,
    val latestRunRequestedAt: String? = null, val latestRunCompletedAt: String? = null,
    val latestRunStartedAt: String? = null, val activityRunStartedAt: String? = null,
    val activityRunStartedAtPresent: Boolean = false,
    val latestRunId: String? = null, val activeRunId: String? = null,
    val providerInstanceId: String? = null, val modelInstanceId: String? = null,
    val providerInstanceHistory: List<String> = emptyList(),
)
data class ConversationMessage(
    val id: String, val role: String, val text: String, val streaming: Boolean = false,
    val createdAt: String? = null, val updatedAt: String? = null,
    val runId: String? = null, val ordinal: Int? = null,
    val runAttemptId: String? = null, val nodeId: String? = null, val providerTurnId: String? = null,
    val attachments: List<RemoteFileReference> = emptyList(),
)
data class ToolActivity(
    val id: String, val title: String, val detail: String = "",
    val kind: String = "", val status: String = "",
    val startedAt: String? = null, val completedAt: String? = null,
    val runId: String? = null, val ordinal: Int? = null,
    val runAttemptId: String? = null, val nodeId: String? = null, val providerTurnId: String? = null,
    val parentItemId: String? = null,
    val input: String? = null, val output: String? = null, val exitCode: Int? = null,
    val outputIndicatesFailure: Boolean? = null, val outputOmitted: Boolean = false,
    val detailsOmitted: Boolean = false,
    val fileName: String? = null, val diffStr: String? = null,
    val oldStr: String? = null, val newStr: String? = null,
    val searchResults: String? = null, val failure: String? = null,
    val sourceThreadId: String? = null, val sourceItemId: String? = null,
)
data class ConversationRun(
    val id: String, val status: String,
    val requestedAt: String? = null, val startedAt: String? = null, val completedAt: String? = null,
    val workStartedAt: String? = null, val ordinal: Int? = null,
    val activeAttemptId: String? = null, val userMessageId: String? = null,
    val providerInstanceId: String? = null, val modelInstanceId: String? = null, val model: String? = null,
)
data class RunAttempt(
    val id: String, val runId: String, val status: String,
    val attemptOrdinal: Int? = null, val reason: String? = null,
    val startedAt: String? = null, val completedAt: String? = null,
    val rootNodeId: String? = null, val providerInstanceId: String? = null, val providerTurnId: String? = null,
)
/** Routing identity comes from the server catalog, independently of its model names. */
data class ProviderInstance(
    val instanceId: String, val driver: String,
    val displayName: String? = null, val accentColor: String? = null, val enabled: Boolean = true,
)
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
    val canRead: Boolean = false,
    val providers: List<ProviderInstance> = emptyList(),
    val runs: List<ConversationRun> = emptyList(),
    val attempts: List<RunAttempt> = emptyList(),
    val screens: ScreenCatalog = ScreenCatalog(),
)

/** All commands fail explicitly; implementations must never replay uncertain mutations. */
interface TrefoilClient {
    val state: StateFlow<ClientState>
    suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean = false): SavedEnvironment
    suspend fun connect(environment: SavedEnvironment)
    suspend fun disconnect()
    suspend fun selectThread(threadId: String)
    suspend fun refreshScreens() { error("Remote screens are unavailable") }
    suspend fun screenAccess(target: ScreenTarget): ScreenAccess { error("Remote screens are unavailable") }
    fun isScreenAccessCurrent(access: ScreenAccess): Boolean = false
    /** Read a body withheld from the synchronized projection, without dispatching a mutation. */
    suspend fun loadActivity(itemId: String) { error("Activity details are unavailable") }
    /** Streams a signed asset into caller-owned storage; failures may leave partial bytes. */
    suspend fun downloadFile(reference: RemoteFileReference, output: java.io.OutputStream, environmentId: String, threadId: String, onProgress: (FileDownloadProgress) -> Unit = {}): DownloadedFile { error("File downloads are unavailable") }
    suspend fun sendMessage(text: String)
    suspend fun interruptRun()
    suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>> = emptyMap())
    suspend fun createThread(projectId: String, title: String, model: ModelOption): String
    suspend fun reconnect()
    fun close()
}
