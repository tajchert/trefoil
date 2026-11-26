package com.tajchert.trefoil.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SavedEnvironment(val id: String, val name: String, val baseUrl: String, val accessToken: String, val ssh: SshProfile? = null, val allowInsecureHttp: Boolean = false,
    /** Learned routes only (see Routes.kt); the paired address stays in [baseUrl]. Defaults keep old vault JSON decodable. */
    val routes: List<EnvironmentRoute> = emptyList(),
    /** T3 `token_type`. A [AccessTokenType.DPoP] token is bound to this device's proof key; old vault JSON has none and stays Bearer. */
    val tokenType: AccessTokenType = AccessTokenType.Bearer,
    /** Expiry the server reported at pairing (`expires_in`), epoch millis; null when it did not say. Informational: the server decides. */
    val accessTokenExpiresAtMillis: Long? = null) {
    override fun toString() = "SavedEnvironment(credentials=***)"
}
/** Authorization scheme of a saved access token: `Authorization: Bearer`, or `Authorization: DPoP` plus a per-request proof (RFC 9449). */
@Serializable enum class AccessTokenType { Bearer, DPoP }
/** [Serializable] for the encrypted on-device shell cache only; the wire format is parsed by the client, not by this. */
@Serializable
data class Project(val id: String, val title: String, val path: String = "")
data class ThreadTarget(val environmentId: String, val threadId: String)
enum class ThreadAction { Settle, Unsettle, Pin, Unpin, Rename, Snooze, Wake }
data class SettlementUndo(val token: String, val target: ThreadTarget)
/** [Serializable] for the encrypted on-device shell cache only (see [Project]); new fields need defaults. */
@Serializable
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
    /** T3 `latestRunStopRequested`: the user stopped the latest run, so its completion is not news. */
    val latestRunStopRequested: Boolean = false,
    /** T3 `latestRunBackgroundWake`: the latest run was started by a background task waking the agent. */
    val latestRunBackgroundWake: Boolean = false,
    /** T3 `pendingRuntimeRequest.userOnly`: a card only the user can answer (secret, link), not the coordinator. */
    val pendingRequestUserOnly: Boolean = false,
    /** T3 `lineage.workflowStep`: an agent step of a workflow run, listed only inside the run. */
    val workflowStep: Boolean = false,
    /** T3 `monitorHoldsWakeCompletion`: a background-wake run whose non-pull-request-watch monitor is still pending. */
    val monitorHoldsWakeCompletion: Boolean = false,
    /** T3 shell `issues[]`: the issues this thread links. Defaults keep old shell caches decodable. */
    val issues: List<ThreadIssueLink> = emptyList(),
    /** Derived from child shells for presentation; never supplied by a shell row, so never cached. */
    @Transient val pendingChildRequestKind: String? = null,
)
data class ConversationMessage(
    val id: String, val role: String, val text: String, val streaming: Boolean = false,
    val createdAt: String? = null, val updatedAt: String? = null,
    val runId: String? = null, val ordinal: Int? = null,
    val runAttemptId: String? = null, val nodeId: String? = null, val providerTurnId: String? = null,
    val attachments: List<RemoteFileReference> = emptyList(),
    /** User messages only: T3 `inputIntent` (`steer`, `promoted_queued_to_steer`, `queued_turn`, ...), when sent. */
    val inputIntent: String? = null,
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
    /** Set only for a completed, non-error `html_render` dynamic_tool item. */
    val htmlRender: HtmlRenderReference? = null,
    /** `subagent` items only: the projection's `subagents[].id` this item reports on. */
    val subagentId: String? = null,
    /** The turn item's last update; T3 times a work entry from `startedAt ?? updatedAt`. */
    val updatedAt: String? = null,
    /** `system_notice` items only: T3 `level` (`info`, or absent/other for a warning). */
    val noticeLevel: String? = null,
)
/** How a message sent while the thread works is delivered (T3 `ActiveTurnComposerAction` `queue` / `steer`). */
enum class FollowUpMode { Queue, Steer }
/**
 * A user message waiting in the selected thread's queue: its queued run and message, with the message text.
 * [canSendNow]: T3 queue eligibility for [TrefoilClient.steerQueuedMessage] — with no active run the thread is not
 * archived or deleted; with one, the turn accepts a promoted steer (`canPromoteToSteer`) and the message does not start
 * a fresh session (unless the target is a `/compact` turn, which is skipped instead). Write gating is the caller's job.
 */
data class QueuedMessage(val runId: String, val messageId: String, val text: String, val canSendNow: Boolean = false)
data class ConversationRun(
    val id: String, val status: String,
    val requestedAt: String? = null, val startedAt: String? = null, val completedAt: String? = null,
    val workStartedAt: String? = null, val ordinal: Int? = null,
    val activeAttemptId: String? = null, val userMessageId: String? = null,
    val providerInstanceId: String? = null, val modelInstanceId: String? = null, val model: String? = null,
    /** T3 `ThreadRunSummary.turnEnded`: the active attempt's latest provider turn is no longer pending or running. */
    val turnEnded: Boolean = false,
    /** T3 `queueHeld`: a queued run held back from starting. */
    val queueHeld: Boolean = false,
    /** T3 `usageLimitRunPresentedAsLatest`: this usage-limited run stays the thread's latest run. */
    val presentedAsLatest: Boolean = false,
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
/**
 * [supportedRuntimeModes] empty means the provider reports none: every mode is allowed.
 * [descriptors] are the model's `capabilities.optionDescriptors` (unknown types skipped). Compare models
 * with [sameModelAs], never `==`: descriptors may change between catalog refreshes.
 */
data class ModelOption(
    val instanceId: String, val model: String, val label: String = model, val supportedRuntimeModes: List<RuntimeMode> = emptyList(),
    val descriptors: List<ModelOptionDescriptor> = emptyList(),
    /** Catalog metadata (T3 `ServerProviderModel`): optional short name, backend, legacy/default flags and `badge` ("new"). */
    val shortName: String? = null, val subProvider: String? = null, val isLegacy: Boolean = false, val isDefault: Boolean = false, val badge: String? = null,
) {
    /** The model's reasoning control (T3 `isReasoningSelect`): the first reasoning select. */
    val reasoning: ModelOptionDescriptor.Select? get() = descriptors.filterIsInstance<ModelOptionDescriptor.Select>().firstOrNull { it.isReasoning }
    /** The model's Fast mode switch: the boolean descriptor `fastMode`. */
    val fastMode: ModelOptionDescriptor.Toggle? get() = descriptors.filterIsInstance<ModelOptionDescriptor.Toggle>().firstOrNull { it.id == FAST_MODE_OPTION_ID }
    fun descriptor(id: String): ModelOptionDescriptor? = descriptors.firstOrNull { it.id == id }
    /** Same provider instance and model slug. */
    fun sameModelAs(other: ModelOption?): Boolean = other != null && instanceId == other.instanceId && model == other.model
}
data class ModelOptionChoice(val id: String, val label: String, val description: String? = null, val isDefault: Boolean = false)
/** T3 `ProviderOptionDescriptor`. */
sealed interface ModelOptionDescriptor {
    val id: String; val label: String; val description: String?
    data class Select(
        override val id: String, override val label: String, override val description: String? = null,
        val choices: List<ModelOptionChoice>, val currentValue: String? = null,
        val promptInjectedValues: List<String> = emptyList(), val isReasoning: Boolean = false,
    ) : ModelOptionDescriptor {
        /** Choices a picker offers: prompt-injected values (e.g. Claude "Ultrathink") are hidden. */
        val selectableChoices: List<ModelOptionChoice> get() = choices.filter { it.id !in promptInjectedValues }
    }
    data class Toggle(override val id: String, override val label: String, override val description: String? = null, val currentValue: Boolean? = null) : ModelOptionDescriptor
}
/** Option value: String or Boolean. */
typealias ModelOptionValues = Map<String, Any>
/** T3 `RuntimeMode` literals; labels mirror T3 web `runtimeModeConfig`. */
enum class RuntimeMode(val wire: String, val label: String, val description: String) {
    ApprovalRequired("approval-required", "Supervised", "Ask before commands and file changes."),
    AutoAcceptEdits("auto-accept-edits", "Auto-accept edits", "Auto-approve edits, ask before other actions."),
    Auto("auto", "Auto", "Supported providers approve routine actions; others still ask."),
    FullAccess("full-access", "Full access", "Allow commands and edits without prompts.");
    companion object { fun fromWire(value: String?): RuntimeMode? = entries.firstOrNull { it.wire == value } }
}
/** A configured T3 `ModelSelection` (settings `defaultModelSelection`): instance, model slug and explicit options. */
data class ModelDefault(val instanceId: String, val model: String, val options: ModelOptionValues = emptyMap())
/** New-thread defaults one scope sets; null means the scope does not set it (the wider scope applies). */
data class NewThreadDefaults(val model: ModelDefault? = null, val runtimeMode: RuntimeMode? = null)

/**
 * T3 `resolveProjectSettings` for new threads: a project override wins over the environment setting. A configured
 * model the catalog does not offer (provider disabled, model gone) is skipped, so the next scope applies; with neither,
 * [NewThreadSettings.model] is the catalog's first model. Options keep only values the model still offers.
 */
fun ClientState.newThreadSettings(projectId: String?): NewThreadSettings {
    val project = projectId?.let { projectDefaults[it] }
    fun offered(selection: ModelDefault?) = selection?.let { s -> models.firstOrNull { it.instanceId == s.instanceId && it.model == s.model }?.let { it to s } }
    val (model, selection) = offered(project?.model) ?: offered(defaultModelSelection) ?: (models.firstOrNull() to null)
    return NewThreadSettings(model, selection?.let { model?.validOptions(it.options) }.orEmpty(), project?.runtimeMode ?: defaultRuntimeMode ?: RuntimeMode.ApprovalRequired)
}
/** Resolved defaults a new thread starts with; see [newThreadSettings]. */
data class NewThreadSettings(val model: ModelOption?, val options: ModelOptionValues, val runtimeMode: RuntimeMode)

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
    /** Explicit provider options (id to String or Boolean), sent as `modelSelection.options`. */
    val modelOptions: ModelOptionValues = emptyMap(),
    /** Issue to link once the launched thread exists (start thread from issue); in memory only. */
    val issueLink: ThreadIssueLink? = null,
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
    /** Raw `orchestration.subscribePromptSuggestion` value for the selected thread; read it through [offeredPromptSuggestion]. Cleared on thread switch and disconnect. */
    val promptSuggestion: PromptSuggestion? = null,
    val attempts: List<RunAttempt> = emptyList(),
    val screens: ScreenCatalog = ScreenCatalog(),
    /** Selected thread's current runtime mode, from its projection; null when unknown. */
    val selectedRuntimeMode: RuntimeMode? = null,
    /** Selected thread's current model selection, from its projection; null when unknown. */
    val selectedModel: ModelOption? = null,
    /** Selected thread's explicit `modelSelection.options` (id to String or Boolean); empty when none. */
    val selectedModelOptions: ModelOptionValues = emptyMap(),
    /** True when the selected thread has started and the server cannot hand it to another provider instance. */
    val selectedProviderLocked: Boolean = false,
    /** Server `settings.defaultRuntimeMode`; null when not reported. */
    val defaultRuntimeMode: RuntimeMode? = null,
    /** Server `settings.defaultModelSelection`; null when unset or not reported. */
    val defaultModelSelection: ModelDefault? = null,
    /** Server `settings.projectSettingsOverrides`, by project id: only the new-thread defaults a project overrides. */
    val projectDefaults: Map<String, NewThreadDefaults> = emptyMap(),
    /** Server `settings.favorites` and `settings.providerModelPreferences`; [ModelPreferences.synced] false when not reported. */
    val modelPreferences: ModelPreferences = ModelPreferences(),
    /** True when the session holds `settings:write`, so favourites can be changed on the server. */
    val canWriteSettings: Boolean = false,
    /** Selected thread's projection `subagents[]`, in wire order. */
    val subagents: List<Subagent> = emptyList(),
    /** Selected thread's pending background work (T3 `derivePendingBackgroundWork`); empty while a run is live. */
    val backgroundWork: List<PendingBackgroundTask> = emptyList(),
    /** Server `environment.capabilities.threadStop`; false when not reported. */
    val threadStopSupported: Boolean = false,
    /** Server `environment.capabilities.issues`; false when not reported. */
    val issuesSupported: Boolean = false,
    /** Server `environment.capabilities.issueLinkedThreads`; false when not reported. */
    val issueLinkedThreadsSupported: Boolean = false,
    /** Selected thread's user messages queued behind the active run (T3 `getUserQueuedThreadRuns`), in queue order. */
    val queuedMessages: List<QueuedMessage> = emptyList(),
    /** A stop (or restart) held the selected thread's queue; it starts again only on [Client.resumeQueue]. */
    val queueHeld: Boolean = false,
    /** The selected thread's active turn accepts steering (T3 `canPromoteToSteer`); otherwise follow-ups queue. */
    val canSteer: Boolean = false,
    /** Something on the selected thread can be stopped: a live run, pending background work or an active subagent. */
    val canStop: Boolean = false,
    /** Session grants `diagnostics:read`, which `server.getUsageSummary` requires. Limits only need read access. */
    val canReadUsage: Boolean = false,
    /** Route of the live connection. For SSH: `EnvironmentRoute(Ssh, <ephemeral loopback>, Paired)`, display only, never persisted. */
    val activeRoute: EnvironmentRoute? = null,
    /**
     * [projects] and [threads] hold a shell snapshot of [environment]: applied live, retained from earlier in this
     * process, or restored from the local cache. False until a first snapshot exists, so an empty list is "loading",
     * not "no threads". It says nothing about freshness; see [canRead] and [canWrite].
     */
    val shellLoaded: Boolean = false,
    /**
     * The live connection's shell stream is synchronized, so [projects] and [threads] follow the server. False while
     * rows retained or restored from the local cache wait for their catch-up, and whenever not connected.
     */
    val shellLive: Boolean = false,
    /** Dev servers T3 discovered on the environment host; see PortModels.kt. */
    val localServers: LocalServerCatalog = LocalServerCatalog(),
    /**
     * Server `environment.capabilities.previewTunnelSetting`: true/false when reported, null when the server did not say.
     * It only says the server honours the `enablePreviewTunnel` setting (true on current servers, absent on older ones,
     * where the tunnel is always on). It never says whether the tunnel is on; see [previewTunnelDisabled].
     */
    val previewTunnelSetting: Boolean? = null,
    /**
     * The server config says the tunnel is off: [previewTunnelSetting] is true and `settings.enablePreviewTunnel` is
     * false or `managedPolicy.limits.previewTunnel` is false (T3 `allowsPreviewTunnel`). Absent or wrong-typed fields
     * leave it false, so only a `TunnelDisabled` handshake can still say the tunnel is off. Kept current from
     * `subscribeServerConfig`; false again when that stream is not live, and cleared on disconnect.
     */
    val previewTunnelDisabled: Boolean = false,
    /** Port forwards of the live connection, ordered by start. Cleared on disconnect and environment switch. */
    val portForwards: List<PortForward> = emptyList(),
    /** Read-only child of the selected thread streamed beside it; null when no inspector is open. See SideThreads.kt. */
    val inspector: InspectorThread? = null,
    /** Server `environment.capabilities.sideChats`; false when not reported. Gates starting side chats, not opening them. */
    val sideChatsSupported: Boolean = false,
    /** Selected thread's projection `checkpoints` with a non-null `appRunOrdinal`, in turn order; see [TrefoilClient.loadTurnDiff]. */
    val checkpoints: List<ThreadCheckpoint> = emptyList(),
    /** Selected thread's projection `pullRequests` links (without `stack-dismissed` ones), in wire order. */
    val threadPullRequests: List<ThreadPullRequest> = emptyList(),
    /** Server `environment.capabilities.pullRequests`; false when not reported. Gates every `pullRequests.*` call. */
    val pullRequestsSupported: Boolean = false,
    /** Server `environment.capabilities.pullRequestChecks`; false when not reported. Gates `pullRequests.checks`. */
    val pullRequestChecksSupported: Boolean = false,
    /** Session grants `source-control:write`, which PR mutations (`runAction`, `comment`) require. */
    val canWriteSourceControl: Boolean = false,
)

private val ACTIVE_RUN_STATUSES = setOf("preparing", "starting", "running", "waiting")
private val SETUP_RUN_STATUSES = setOf("preparing", "starting")

/**
 * The selected thread has work a message must queue behind: an active run, any run in an active status, or a shell row
 * whose run is still preparing/starting (a projection can lag behind it).
 */
val ClientState.threadBusy: Boolean get() {
    val shell = threads.firstOrNull { it.id == selectedThreadId }
    return activeRunId != null || runs.any { it.status in ACTIVE_RUN_STATUSES } ||
        shell?.activityRunStatus in SETUP_RUN_STATUSES || shell?.status in SETUP_RUN_STATUSES
}

/** A mutation may or may not have been applied; reconcile from server state, never resend it. */
class UncertainOutcomeException(message: String) : IllegalStateException(message)

/** All commands fail explicitly; implementations must never replay uncertain mutations. */
interface TrefoilClient {
    val state: StateFlow<ClientState>
    suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean = false): SavedEnvironment
    /** Connects to `environment.baseUrl` as a single route; sets [ClientState.activeRoute] to the classified paired route. */
    suspend fun connect(environment: SavedEnvironment)
    /**
     * Connect through the first route in [routes] (all non-Ssh, already in preference order) whose descriptor
     * answers as environment.id; the bearer is sent only to that route. Sets ClientState.activeRoute.
     * Throws PairingException if none answers (message names the last failure).
     */
    suspend fun connect(environment: SavedEnvironment, routes: List<EnvironmentRoute>) { connect(environment.copy(baseUrl = routes.first().baseUrl)) }
    /** Route hints from the connected host: once after each successful connect (the first config's directEndpoints + link-state; additive while the first config snapshot's list may be incomplete), then on each subscribeServerConfig snapshot whose directEndpoints change, or once the first list settles. */
    val routeHints: Flow<RouteHints> get() = emptyFlow()
    /** Credential-free preflight: true when route answers /.well-known/t3/environment as environmentId (protocol 2) within timeoutMillis. Applies URL policy with allowInsecureHttp. */
    suspend fun preflight(environmentId: String, route: EnvironmentRoute, allowInsecureHttp: Boolean, timeoutMillis: Long): Boolean = false
    /** True when a route switch now cannot abandon an in-flight mutation (no pending dispatch, launch, respond, stop, thread action). */
    fun isIdleForRouteSwitch(): Boolean = false
    /** Pings the live connection: true when a pong arrives within [timeoutMillis]; otherwise the connection fails (its drop handling runs) and false. */
    suspend fun verifyLink(timeoutMillis: Long): Boolean = true
    suspend fun disconnect()
    suspend fun selectThread(threadId: String)
    /**
     * Selects [threadId] only while [environmentId] is the current, connected environment and its live (synchronized, see
     * [ClientState.shellLive]) shell lists the thread; otherwise nothing changes and the result is false. For requests that name
     * a machine (a notification tap): the check and the selection happen together, so a machine switch in between never opens
     * another machine's thread.
     */
    suspend fun selectThreadIn(environmentId: String, threadId: String): Boolean {
        val current = state.value
        if (current.environment?.id != environmentId || current.connection != ConnectionStatus.Connected || !current.shellLive ||
            current.threads.none { it.id == threadId }) return false
        selectThread(threadId)
        return true
    }
    /** False while the app is in the background: the open thread is then not being looked at, so no visit is recorded. */
    fun setThreadViewing(viewing: Boolean) {}
    /**
     * Re-reads the live environment's shell (projects and thread rows, including run status and background work):
     * the shell stream is resubscribed for a full snapshot (never a cursor resume) that replaces the list. Returns
     * once that snapshot is synchronized. Read-only: never sends or replays a mutation. Throws when there is no live
     * connection or the snapshot is late; a late refresh drops the connection only when its heartbeat is stale too.
     */
    suspend fun refreshThreads() { error("Thread refresh is unavailable") }
    /**
     * Silent catch-up of the live environment's shell: resubscribes with the resume cursor so only the missed changes
     * arrive, or a full snapshot when there is no valid cursor. Same completion and failure rules as [refreshThreads].
     */
    suspend fun resyncThreads() { refreshThreads() }
    suspend fun refreshScreens() { error("Remote screens are unavailable") }
    suspend fun screenAccess(target: ScreenTarget): ScreenAccess { error("Remote screens are unavailable") }
    fun isScreenAccessCurrent(access: ScreenAccess): Boolean = false
    /**
     * Opens (or returns the existing) loopback forward to [remotePort] on the connected environment's host.
     * Returns once the local listener is bound; tunnel failures surface per connection in [ClientState.portForwards].
     */
    suspend fun startPortForward(remotePort: Int): PortForward { error("Port forwarding is unavailable") }
    /** Closes the forward's listener and every open tunnel connection. No-op when none exists. */
    suspend fun stopPortForward(remotePort: Int) {}
    /** Read a body withheld from the synchronized projection, without dispatching a mutation. */
    suspend fun loadActivity(itemId: String) { error("Activity details are unavailable") }
    /** Streams a signed asset into caller-owned storage; failures may leave partial bytes. */
    suspend fun downloadFile(reference: RemoteFileReference, output: java.io.OutputStream, environmentId: String, threadId: String, onProgress: (FileDownloadProgress) -> Unit = {}): DownloadedFile { error("File downloads are unavailable") }
    suspend fun sendMessage(text: String)
    /**
     * Sends [text] to the selected thread. While it works, [FollowUpMode.Queue] sends `queue_after_active` and
     * [FollowUpMode.Steer] `start_immediately` with `deliveryIntent: "steer"` (refused locally unless
     * [ClientState.canSteer]); an idle thread starts immediately either way. Never replayed.
     */
    suspend fun sendMessage(text: String, mode: FollowUpMode) { error("Follow-up delivery is unavailable") }
    /**
     * Stops everything on the selected thread, like T3's thread Stop: `thread.stop` when [ClientState.threadStopSupported],
     * otherwise `run.interrupt {runId, holdQueue: true}` on the live run, or on the latest run while background work is
     * pending. Both hold the queue ([ClientState.queueHeld]). Never replayed.
     */
    suspend fun stopThread() { error("Stopping the thread is unavailable") }
    /** `queued-run.cancel {runId}` for one of [ClientState.queuedMessages]. */
    suspend fun cancelQueuedMessage(runId: String) { error("Queue changes are unavailable") }
    /** Sends a queued message now: `queued-message.promote-to-steer` into the active run, or `queued-run.start` when idle. */
    suspend fun steerQueuedMessage(runId: String) { error("Queue changes are unavailable") }
    /** `queue.resume`: restarts a held queue. */
    suspend fun resumeQueue() { error("Queue changes are unavailable") }
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
     * Sets one provider option ([value] String or Boolean) of the selected thread's model with
     * `thread.model-selection.set`, keeping its other options. Refuses an id or value the model does not offer.
     */
    suspend fun setModelOption(id: String, value: Any) { error("Model option changes are unavailable") }
    /** Stars or unstars one model with `server.updateSettings` `setModelFavorites`; never replayed after a disconnect. */
    suspend fun setModelFavorite(key: ModelKey, favorite: Boolean) { error("Favourites are unavailable") }
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
    /** Streams [threadId], a side thread of the selected thread, into [ClientState.inspector] without touching the selection. */
    suspend fun openInspector(threadId: String) { error("The thread inspector is unavailable") }
    /** Stops the inspector stream and clears [ClientState.inspector]. */
    fun closeInspector() {}
    /** Forks the selected thread into a side chat, sends [question] there, and returns the new thread id. */
    suspend fun startSideChat(question: String): String { error("Side chats are unavailable") }
    /**
     * Reads and parses (off the main thread) the selected thread's diff for one turn of [ClientState.checkpoints]:
     * `getFullThreadDiff` for turn 1, `getTurnDiff {n-1, n}` otherwise. Read-only; cached in memory per environment.
     */
    suspend fun loadTurnDiff(turnCount: Int): DiffDocument { error("Turn diffs are unavailable") }
    /**
     * The panel of one pull request. While collected, the session keeps `pullRequests.subscribeRefreshes` open and
     * revalidates `detail`/`checks`; the default is an empty, never-answered panel.
     */
    fun prPanel(key: PullRequestKey): StateFlow<PrPanelState> = MutableStateFlow(PrPanelState())
    /** Re-reads `detail` (and `checks` when supported) for [key]. Read-only; a no-op when not live. */
    suspend fun refreshPullRequest(key: PullRequestKey) {}
    /** Reads one page of the PR diff (`POST /api/pull-requests/diff`); [cursor] null reads the first page. */
    suspend fun loadPullRequestDiff(key: PullRequestKey, cursor: String?): PrDiffPage { error("Pull request diffs are unavailable") }
    /**
     * Sends `pullRequests.runAction` once; merge sends `expectedHeadSha` and [mergeMethod]. Update-branch uses the
     * server's default update method. Never retried or replayed: an uncertain outcome shows in [prPanel] as
     * [PrActionOutcome.Unknown] and reconciles from `detail {allowStale: false}`.
     */
    suspend fun runPullRequestAction(key: PullRequestKey, action: PrAction, mergeMethod: PrMergeMethod? = null) { error("Pull request actions are unavailable") }
    /** Sends `pullRequests.comment` once; never retried or replayed. */
    suspend fun commentOnPullRequest(key: PullRequestKey, body: String) { error("Pull request comments are unavailable") }
    /**
     * One `issues.list` page across the environment's projects; [cursors] continues the scopes they name.
     * Read-only: failures throw [IssueException] and never change the connection state.
     */
    suspend fun issuesList(query: IssueQuery, cursors: Map<String, String>? = null, limit: Int = 50, refresh: Boolean = false): IssueListPage =
        throw IssueException(IssueFailure.NotSupported, "Issues are unavailable")
    /** `issues.detail` for one issue. Read-only; failures throw [IssueException]. */
    suspend fun issueDetail(ref: IssueRef): IssueDetail = throw IssueException(IssueFailure.NotSupported, "Issues are unavailable")
    /** Older comments from [cursor]: the page and the next cursor (null when none). Read-only; failures throw [IssueException]. */
    suspend fun issueComments(ref: IssueRef, cursor: String): IssueCommentsPage =
        throw IssueException(IssueFailure.NotSupported, "Issues are unavailable")
    /** Threads linking [key] (`issues.linkedThreads`, when [ClientState.issueLinkedThreadsSupported]). Read-only. */
    suspend fun issueLinkedThreads(key: IssueKey): List<IssueLinkedThread> =
        throw IssueException(IssueFailure.NotSupported, "Issues are unavailable")
    /** Dispatches `thread.issue.link` once; never replayed. The shell row's [ThreadSummary.issues] reflects the outcome. */
    suspend fun linkIssue(target: ThreadTarget, link: ThreadIssueLink) {
        throw IssueException(IssueFailure.NotSupported, "Issues are unavailable")
    }
    /** Dispatches `thread.issue.unlink` once; never replayed. */
    suspend fun unlinkIssue(target: ThreadTarget, key: IssueKey) {
        throw IssueException(IssueFailure.NotSupported, "Issues are unavailable")
    }
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
