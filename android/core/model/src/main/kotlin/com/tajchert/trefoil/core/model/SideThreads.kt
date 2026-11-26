/* Side thread kinds adapted from T3 Code client-runtime `sideThread.ts` (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

/** A thread that opens beside its parent (T3 `SideThreadKind` without `linked`). */
enum class SideThreadKind { SideChat, Subagent, ProviderSubagent }

/** T3 `sideThreadKindOf`: null for a thread that opens like any other. */
fun ThreadSummary.sideThreadKind(): SideThreadKind? = when {
    sideChat && parentThreadId != null -> SideThreadKind.SideChat
    relationshipToParent != "subagent" -> null
    creationSource == "provider" -> SideThreadKind.ProviderSubagent
    else -> SideThreadKind.Subagent
}

/** Live side chats of [parentId], newest first (by `createdAt`, ISO-8601 strings compare in order). */
fun sideChatsOf(threads: List<ThreadSummary>, parentId: String): List<ThreadSummary> =
    threads.filter { it.sideChat && it.parentThreadId == parentId && it.deletedAt == null }
        .sortedByDescending { it.createdAt.orEmpty() }

enum class InspectorError { TooLarge, RefreshFailed, Gone }

/** Read-only child thread shown in the inspector pane beside the selected thread; published in [ClientState.inspector]. */
data class InspectorThread(
    val threadId: String,
    val kind: SideThreadKind,
    /** An authoritative snapshot for the current stream arrived; false while cached rows repaint or the stream is down. */
    val ready: Boolean = false,
    val error: InspectorError? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val activities: List<ToolActivity> = emptyList(),
    val requests: List<RuntimeRequest> = emptyList(),
    val runs: List<ConversationRun> = emptyList(),
    val attempts: List<RunAttempt> = emptyList(),
    val subagents: List<Subagent> = emptyList(),
    val activeRunId: String? = null,
)
