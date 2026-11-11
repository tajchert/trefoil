/* Subagent model adapted from T3 Code orchestration V2 contracts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

/**
 * One T3 `OrchestrationV2Subagent` record from the **parent** thread's projection (`subagents[]`).
 * Statuses and origins stay raw wire strings; unknown values are tolerated and presented as stopped.
 * Timestamps are ISO-8601 strings as sent by the server.
 */
data class Subagent(
    val id: String,
    /** The parent thread that owns this record. */
    val threadId: String,
    /** The parent's run (turn) that spawned it; null for some workflow members. */
    val runId: String? = null,
    /** `provider_native` (for example a Claude Task call) or `app_owned` (T3 delegated task). */
    val origin: String = "",
    /** Provider driver kind, for example `claudeAgent` or `codex`. */
    val driver: String = "",
    val providerInstanceId: String? = null,
    /** Local child T3 thread in the same environment, if any. */
    val childThreadId: String? = null,
    /** Task running in a linked environment; it has no local child thread. */
    val remoteChild: SubagentRemoteChild? = null,
    val nativeTaskRef: SubagentNativeTaskRef? = null,
    val prompt: String = "",
    val title: String? = null,
    /** Model the provider reported, if any. */
    val model: String? = null,
    /** `modelSelection.instanceId` / `modelSelection.model`, when sent. */
    val selectionInstanceId: String? = null,
    val selectionModel: String? = null,
    /** `modelSelection.options[]` as id to value, for example `effort` to `high`. */
    val selectionOptions: Map<String, String> = emptyMap(),
    /** `idle|pending|running|waiting|completed|failed|cancelled|interrupted`. */
    val status: String = "",
    val progress: String? = null,
    /** Final summary; also the error text when [status] is `failed`. */
    val result: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val updatedAt: String? = null,
    val workflow: SubagentWorkflow? = null,
) {
    val isProviderNative: Boolean get() = origin == SUBAGENT_ORIGIN_PROVIDER_NATIVE
    val isAppOwned: Boolean get() = origin == SUBAGENT_ORIGIN_APP_OWNED
    /** T3 `isOrchestrationV2WorkActive`: pending, running or waiting. */
    val isActive: Boolean get() = isSubagentActiveStatus(status)
    /** Completed, failed, cancelled or interrupted. `idle` is neither active nor terminal. */
    val isTerminal: Boolean get() = isSubagentTerminalStatus(status)
}

data class SubagentRemoteChild(val environmentId: String, val threadId: String, val label: String, val title: String? = null)

/** `strength` is `strong`, `weak` or `none`. */
data class SubagentNativeTaskRef(val nativeId: String? = null, val strength: String = "none")

/** Workflow coordinator detail. Member timestamps are epoch millis on the wire and are not kept. */
data class SubagentWorkflow(val name: String? = null, val agents: List<SubagentWorkflowAgent> = emptyList())

/** `state` is `queued|running|completed|failed`. */
data class SubagentWorkflowAgent(
    val index: Int, val label: String, val state: String,
    val childThreadId: String? = null, val model: String? = null, val phaseTitle: String? = null,
)

const val SUBAGENT_ORIGIN_PROVIDER_NATIVE = "provider_native"
const val SUBAGENT_ORIGIN_APP_OWNED = "app_owned"

fun isSubagentActiveStatus(status: String): Boolean = status == "pending" || status == "running" || status == "waiting"
fun isSubagentTerminalStatus(status: String): Boolean =
    status == "completed" || status == "failed" || status == "cancelled" || status == "interrupted"

/** T3 `isProviderNativeSubagentThread`: provider-native children cannot receive messages. */
fun ThreadSummary.isProviderNativeSubagentThread(): Boolean = relationshipToParent == "subagent" && creationSource == "provider"
