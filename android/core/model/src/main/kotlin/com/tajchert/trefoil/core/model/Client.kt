package com.tajchert.trefoil.core.model

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SavedEnvironment(val id: String, val name: String, val baseUrl: String, val accessToken: String, val ssh: SshProfile? = null, val allowInsecureHttp: Boolean = false) {
    override fun toString() = "SavedEnvironment(credentials=***)"
}
data class Project(val id: String, val title: String, val path: String = "")
data class ThreadTarget(val environmentId: String, val threadId: String)
enum class ThreadAction { Settle, Unsettle, Pin, Unpin, Rename, Snooze, Wake }
data class SettlementUndo(val token: String, val target: ThreadTarget)
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
    val parentThreadId: String? = null, val relationshipToParent: String? = null,
    val sideChat: Boolean = false, val creationSource: String? = null, val deletedAt: String? = null,
    val activeProviderThreadId: String? = null, val activityRunStatusPresent: Boolean = false,
    val snoozedAt: String? = null, val pendingRequestId: String? = null,
    val pendingRequestCreatedAt: String? = null, val pendingRequestBlocking: Boolean? = null,
    val pendingGuestMessageCount: Int = 0,
    val interactionMode: String? = null, val hasActionableProposedPlan: Boolean = false,
    val latestUserAuthoredMessageAtPresent: Boolean = false,
    val completionHeldBackgroundWork: Boolean = false,
    /** Derived from child shells for presentation; never supplied by a shell row. */
    val pendingChildRequestKind: String? = null,
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
    val images: List<RemoteFileReference> = emptyList(),
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
    /** Server `ServerProvider.usageLimits`; null when the provider reports none. Last good value is kept by the server. */
    val usageLimits: ProviderUsageLimits? = null,
)
data class Question(val id: String, val title: String, val options: List<String> = emptyList(), val multiple: Boolean = false, val required: Boolean = true)
data class RuntimeRequest(val id: String, val title: String, val detail: String = "", val questions: List<Question> = emptyList(), val canApprove: Boolean = true)
/** [supportedRuntimeModes] empty means the provider reports none: every mode is allowed. */
data class ModelOption(val instanceId: String, val model: String, val label: String = model, val supportedRuntimeModes: List<RuntimeMode> = emptyList())
/** T3 `RuntimeMode` literals; labels mirror T3 web `runtimeModeConfig`. */
enum class RuntimeMode(val wire: String, val label: String, val description: String) {
    ApprovalRequired("approval-required", "Supervised", "Ask before commands and file changes."),
    AutoAcceptEdits("auto-accept-edits", "Auto-accept edits", "Auto-approve edits, ask before other actions."),
    Auto("auto", "Auto", "Supported providers approve routine actions; others still ask."),
    FullAccess("full-access", "Full access", "Allow commands and edits without prompts.");
    companion object { fun fromWire(value: String?): RuntimeMode? = entries.firstOrNull { it.wire == value } }
}
/** A local, unsent conversation. The server thread is created by the first message. */
/**
 * [threadId] and [commandId] are allocated once per draft and sent with `launchThread`, so an
 * uncertain launch can be reconciled by finding [threadId] in the shell, and an explicit user
 * resend is deduplicated by the server instead of creating a second thread.
 */
data class ThreadDraft(
    val projectId: String, val model: ModelOption, val runtimeMode: RuntimeMode = RuntimeMode.ApprovalRequired,
    val threadId: String = java.util.UUID.randomUUID().toString(),
    val commandId: String = java.util.UUID.randomUUID().toString(),
)
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
    val canWriteThreads: Boolean = false,
    val canRead: Boolean = false,
    val threadActionsConfigured: Boolean = false,
    val settlementUndo: SettlementUndo? = null,
    val providers: List<ProviderInstance> = emptyList(),
    val runs: List<ConversationRun> = emptyList(),
    val attempts: List<RunAttempt> = emptyList(),
    val screens: ScreenCatalog = ScreenCatalog(),
    /** Selected thread's current runtime mode, from its projection; null when unknown. */
    val selectedRuntimeMode: RuntimeMode? = null,
    /** Selected thread's current model selection, from its projection; null when unknown. */
    val selectedModel: ModelOption? = null,
    /** True when the selected thread has started and the server cannot hand it to another provider instance. */
    val selectedProviderLocked: Boolean = false,
    /** Server `settings.defaultRuntimeMode`; null when not reported. */
    val defaultRuntimeMode: RuntimeMode? = null,
    /** Selected thread's projection `subagents[]`, in wire order. */
    val subagents: List<Subagent> = emptyList(),
    /** Server `environment.capabilities.threadStop`; false when not reported. */
    val threadStopSupported: Boolean = false,
    /** Session grants `diagnostics:read`, which `server.getUsageSummary` requires. Limits only need read access. */
    val canReadUsage: Boolean = false,
)

/** A mutation may or may not have been applied; reconcile from server state, never resend it. */
class UncertainOutcomeException(message: String) : IllegalStateException(message)

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
    /**
     * Stops one subagent of the selected (parent) thread, following T3 mobile's
     * `resolveSubagentStopTarget` rules. Never replayed; outcomes reconcile from the projection.
     */
    suspend fun stopSubagent(subagentId: String) { error("Stopping subagents is unavailable") }
    suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>> = emptyMap())
    /**
     * Creates a thread together with its first message (`orchestration.launchThread` with
     * `initialMessage`, `generateTitle`), like T3 web. Returns the new thread ID. Never replayed:
     * a request that may have reached the server fails with [UncertainOutcomeException].
     */
    suspend fun launchThread(draft: ThreadDraft, firstMessage: String): String { error("Thread launch is unavailable") }
    /** Dispatches `thread.runtime-mode.set` for the selected thread. */
    suspend fun setRuntimeMode(mode: RuntimeMode) { error("Runtime mode changes are unavailable") }
    /** Dispatches `thread.model-selection.set` for the selected thread. */
    suspend fun setModel(model: ModelOption) { error("Model changes are unavailable") }
    /**
     * Reads historical cost/token usage of the connected environment (`server.getUsageSummary`).
     * [awaitRefresh] false asks for a provisional answer (`provisional: true`, served from a 60 s cache); true always
     * asks the server and waits for a fresh scan.
     * Read-only: failures throw [UsageException] and never change the connection state.
     */
    suspend fun usageReport(range: UsageRange, timeZone: String, awaitRefresh: Boolean = false): UsageReport =
        throw UsageException(UsageFailure.Unsupported, "Usage is unavailable")
    /**
     * Asks the server to re-probe providers (`server.refreshProviders {}`), updating
     * [ClientState.providers] usage limits. [automatic] refreshes are throttled to one per 5 minutes per
     * environment and return false when skipped. Read-only: failures throw [UsageException].
     */
    suspend fun refreshUsageLimits(automatic: Boolean = false): Boolean =
        throw UsageException(UsageFailure.Unsupported, "Usage limits are unavailable")
    suspend fun reconnect()
    fun close()
}

/**
 * Background reachability of a saved environment that is not the active connection.
 * Probes are read-only: they never pair, mutate, or accept an SSH host key.
 */
enum class MachineProbe {
    /** Not checked yet, or it cannot be checked without user input (SSH without saved credentials). */
    Unknown,
    Checking,
    /** The saved credentials were accepted. */
    Online,
    /** No answer in time, network failure, or an unexpected server error. */
    Unreachable,
    /** The server answered and rejected the saved credentials. */
    AuthFailed,
    /** Blocked locally or needs a user decision: URL policy, untrusted or changed SSH host key, identity change. */
    NeedsAttention,
}
