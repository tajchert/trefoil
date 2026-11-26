package com.tajchert.trefoil.core.model

import kotlinx.serialization.Serializable

/*
 * Issue tracker models (T3 `issues.*` RPCs and `thread.issue.*` links). Plain data: unknown tracker kinds stay strings,
 * unknown event kinds are dropped by the parser, unknown enum literals decode to a fallback. Only [IssueKey] and
 * [ThreadIssueLink] are [Serializable], because they ride in the shell cache on [ThreadSummary.issues].
 */

/** Stable identity of one tracker issue. [identity] compares host and scope case-insensitively, the key as written. */
@Serializable
data class IssueKey(val tracker: String, val host: String, val scope: String, val key: String) {
    val identity: String get() = "$tracker\u0000${host.trim().lowercase()}\u0000${scope.trim().lowercase()}\u0000${key.trim()}"
}

/** A thread's link to an issue (shell `issues[]`); server-authoritative. */
@Serializable
data class ThreadIssueLink(val key: IssueKey, val projectId: String, val url: String, val source: String,
    val linkedAt: String?, val displayKey: String, val title: String)

/** Addresses one issue in a project for `issues.detail` / `issues.comments`; [host] null lets the server pick. */
data class IssueRef(val projectId: String, val tracker: String, val host: String?, val scope: String, val key: String)

/** An issue participant; avatars are not loaded in v1. */
data class IssueActor(val login: String, val name: String? = null)

data class IssueLabel(val name: String, val color: String? = null)

/** Tracker state: [open] drives the badge, [name] is the tracker's own label. */
data class IssueState(val open: Boolean, val name: String, val closeReason: String? = null)

/** One row of `issues.list`. [hostName] is the summary's `host`; [ref] carries project, tracker, host, scope and key. */
data class IssueSummary(val ref: IssueRef, val hostName: String, val displayKey: String, val title: String, val url: String,
    val author: IssueActor?, val state: IssueState, val createdAt: String, val updatedAt: String, val closedAt: String?,
    val assignees: List<IssueActor>, val labels: List<IssueLabel>, val commentCount: Int, val projectTitle: String) {
    /** The link identity of this row, with the host taken from [hostName]. */
    val issueKey: IssueKey get() = IssueKey(ref.tracker, hostName, ref.scope, ref.key)
}

/** What the tracker supports for this issue; all false when not reported. */
data class IssueCapabilities(
    val create: Boolean = false, val edit: Boolean = false, val comment: Boolean = false, val labels: Boolean = false,
    val assignees: Boolean = false, val reactions: Boolean = false, val templates: Boolean = false,
    val linkedPullRequests: Boolean = false, val events: Boolean = false, val search: Boolean = false,
)

/** What the viewer may do on this issue; all false when not reported. */
data class IssueViewerPermissions(
    val setState: Boolean = false, val comment: Boolean = false, val edit: Boolean = false,
    val labels: Boolean = false, val assignees: Boolean = false,
)

data class IssueComment(val id: String, val author: IssueActor?, val body: String, val createdAt: String, val url: String?)

/** Timeline event kinds Trefoil renders; other kinds are dropped while parsing. */
enum class IssueEventKind { Closed, Reopened, Labeled, Unlabeled, Assigned, Unassigned, Renamed, Referenced, StateChanged }

data class IssueEvent(val id: String, val kind: IssueEventKind, val actor: IssueActor?, val createdAt: String,
    val detail: String?, val referenceUrl: String?)

data class IssueLinkedPr(val repository: String, val number: Int, val title: String, val url: String, val state: String,
    val draft: Boolean, val closesIssue: Boolean)

/** `issues.detail`: the summary plus body, first comment page ([commentsCursor] for older ones) and timeline. */
data class IssueDetail(val summary: IssueSummary, val writable: Boolean, val capabilities: IssueCapabilities,
    val permissions: IssueViewerPermissions, val states: List<IssueState>, val viewer: String?, val body: String,
    val comments: List<IssueComment>, val commentsCursor: String?, val events: List<IssueEvent>,
    val linkedPullRequests: List<IssueLinkedPr>)

enum class IssueListState { All, Open, Closed }
enum class IssueInvolvement { All, Assigned, Authored, Mentioned }
enum class IssueSort { Updated, Created }

/** List filters; only non-default fields go on the wire. */
data class IssueQuery(val state: IssueListState = IssueListState.Open, val involvement: IssueInvolvement = IssueInvolvement.All,
    val projectId: String? = null, val text: String = "", val labels: List<String> = emptyList(), val assignee: String? = null,
    val sort: IssueSort = IssueSort.Updated)

/** One tracker the server found across projects, and whether it is configured (for example authenticated). */
data class IssueTrackerStatus(val tracker: String, val host: String, val projectCount: Int, val configured: Boolean, val detail: String?)

/** A project whose tracker query failed; the rest of the page still loads. */
data class IssueProjectError(val projectId: String, val projectTitle: String, val message: String)

/** One project/tracker scope the list covered. */
data class IssueScope(val projectId: String, val tracker: String, val host: String?, val scope: String)

/** One `issues.list` page; [nextCursors] continues each scope that has more. */
data class IssueListPage(val entries: List<IssueSummary>, val trackers: List<IssueTrackerStatus>, val errors: List<IssueProjectError>,
    val nextCursors: Map<String, String>, val scopes: List<IssueScope>)

/** A thread that links an issue (`issues.linkedThreads`). */
data class IssueLinkedThread(val id: String, val projectId: String, val title: String, val archived: Boolean, val settled: Boolean)

enum class IssueFailure { NotSupported, NotConnected, Unavailable, NotFound, RateLimited, ReadOnly, NoPermission, TimedOut, Failed }

/** Typed issue failure; [retryAtMillis] is set for [IssueFailure.RateLimited] when the server says when to retry. */
class IssueException(val failure: IssueFailure, message: String, val retryAtMillis: Long? = null) : Exception(message)
