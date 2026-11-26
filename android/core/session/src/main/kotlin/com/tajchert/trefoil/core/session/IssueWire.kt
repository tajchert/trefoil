/* Issue wire mapping adapted from T3 Code's issue contracts and client runtime (MIT):
 * packages/contracts/src/issue.ts (shapes, threadIssueKeyOf), packages/client-runtime/src/state/issues.ts
 * (mergeIssueListPage) and packages/client-runtime/src/issueList.ts (continueIssuePage). Pinned in protocol/ISSUES.md.
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import java.time.Instant
import kotlinx.serialization.json.*

/** Characters of search text `issues.list` accepts. */
internal const val ISSUE_QUERY_MAX_CHARS = 200
/** Labels `issues.list` accepts in one filter. */
internal const val ISSUE_LABELS_MAX = 20
/** Rows per scope `issues.list` accepts. */
internal const val ISSUE_LIST_LIMIT_MAX = 200
/** Rows one merged list holds (§6 bounded memory). */
internal const val ISSUE_HELD_ROWS_MAX = 1_000

/**
 * The first typed `Fail` reason of a failed Exit, reduced to fields that never carry user content: the error class
 * ([tag]), its `reason` literal and `retryAt` (epoch ms). `detail`, `message`, `defect` and nested causes are dropped.
 */
internal data class RpcFailReason(val tag: String, val reason: String?, val retryAt: Long?)

/** Reads the first `Fail` reason of an Exit `cause` (an array of reasons; a single object is tolerated). Null when none. */
internal fun firstFailReason(exit: JsonObject): RpcFailReason? {
    val reasons = when (val cause = exit["cause"]) {
        is JsonArray -> cause.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(cause)
        else -> emptyList()
    }
    val error = reasons.firstOrNull { it.string("_tag") == "Fail" }?.get("error") as? JsonObject ?: return null
    val tag = error.wireText("_tag") ?: return null
    return RpcFailReason(tag, error.wireText("reason"), (error["retryAt"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.toLong())
}

/** Maps a definite `issues.*` rejection to an [IssueException]; [reason] null (a `Die`, an untyped failure) is [IssueFailure.Failed]. */
internal fun issueRejection(reason: RpcFailReason?): IssueException = when (reason?.tag) {
    "IssueUnavailableError" -> IssueException(IssueFailure.Unavailable, when (reason.reason) {
        "tool-missing" -> "The tracker's command-line tool is not installed on the server."
        "unauthenticated" -> "The server is not signed in to this issue tracker."
        "tracker-unsupported" -> "This project has no supported issue tracker."
        else -> "Issues are unavailable for this project."
    })
    "IssueOperationError" -> when (reason.reason) {
        "not-found" -> IssueException(IssueFailure.NotFound, "The issue was not found.")
        "read-only" -> IssueException(IssueFailure.ReadOnly, "This issue tracker is read-only.")
        "rate-limited" -> IssueException(IssueFailure.RateLimited, "The issue tracker is rate limiting requests. Try again later.", reason.retryAt)
        else -> IssueException(IssueFailure.Failed, "The issue tracker could not answer. Try again.")
    }
    "EnvironmentAuthorizationError" -> IssueException(IssueFailure.NoPermission, "This connection may not read issues.")
    else -> IssueException(IssueFailure.Failed, "This T3 server could not load issues.")
}

// --- Request encoders ---

/** `IssueListInput` for [query]: `state` always (required), every other filter only when it differs from the default. */
internal fun issueListInput(query: IssueQuery, cursors: Map<String, String>?, limit: Int, refresh: Boolean): JsonObject = buildJsonObject {
    put("state", query.state.name.lowercase())
    if (query.involvement != IssueInvolvement.All) put("involvement", query.involvement.name.lowercase())
    query.projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", it) }
    put("limit", limit.coerceIn(1, ISSUE_LIST_LIMIT_MAX))
    cursors?.filter { it.key.isNotBlank() && it.value.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { held ->
        put("cursors", buildJsonObject { held.forEach { (scope, cursor) -> put(scope, cursor) } })
    }
    if (refresh) put("refresh", true)
    query.text.trim().take(ISSUE_QUERY_MAX_CHARS).takeIf { it.isNotEmpty() }?.let { put("query", it) }
    query.labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(ISSUE_LABELS_MAX).takeIf { it.isNotEmpty() }?.let { labels ->
        put("labels", buildJsonArray { labels.forEach { add(it) } })
    }
    query.assignee?.trim()?.takeIf { it.isNotEmpty() }?.let { put("assignee", it) }
    if (query.sort == IssueSort.Created) put("sort", "created")
}

/** `IssueRef` payload; never carries `refresh` (only newer servers accept it on `issues.detail`). */
internal fun issueRefPayload(ref: IssueRef, cursor: String? = null): JsonObject = buildJsonObject {
    put("projectId", ref.projectId); put("tracker", ref.tracker)
    ref.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
    put("scope", ref.scope); put("key", ref.key)
    cursor?.let { put("cursor", it) }
}

/** `ThreadIssueKey` for `issues.linkedThreads` (host required). */
internal fun issueKeyPayload(key: IssueKey): JsonObject = buildJsonObject {
    put("tracker", key.tracker); put("host", key.host.trim().lowercase()); put("scope", key.scope.trim()); put("key", key.key.trim())
}

/**
 * Fields of `thread.issue.link` after `type`/`commandId`/`threadId`: host lowercased, strings trimmed, `source` manual.
 * A blank title, display key or URL would be a server `Die`, so it fails here before anything is sent.
 */
internal fun issueLinkFields(link: ThreadIssueLink): JsonObject {
    val key = link.key
    require(listOf(key.tracker, key.host, key.scope, key.key, link.projectId).all { it.isNotBlank() }) { "The issue link is incomplete" }
    require(link.title.isNotBlank() && link.displayKey.isNotBlank() && link.url.isNotBlank()) { "The issue link needs a title, key and URL" }
    return buildJsonObject {
        put("tracker", key.tracker.trim()); put("host", key.host.trim().lowercase()); put("scope", key.scope.trim()); put("key", key.key.trim())
        put("projectId", link.projectId); put("url", link.url.trim()); put("displayKey", link.displayKey.trim()); put("title", link.title.trim())
        put("source", "manual")
    }
}

/** Fields of `thread.issue.unlink` after `type`/`commandId`/`threadId`. */
internal fun issueUnlinkFields(key: IssueKey): JsonObject {
    require(listOf(key.tracker, key.host, key.scope, key.key).all { it.isNotBlank() }) { "The issue key is incomplete" }
    return buildJsonObject { put("tracker", key.tracker.trim()); put("host", key.host.trim().lowercase()); put("scope", key.scope.trim()); put("key", key.key.trim()) }
}

// --- Tolerant parsers: unknown keys ignored, a malformed row is skipped, required strings non-blank ---

private fun JsonObject.wireText(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.wireFlag(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
private fun JsonObject.wireCount(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.rows(key: String): List<JsonObject> = objects(key)

internal fun issueActor(value: JsonElement?): IssueActor? {
    val actor = value as? JsonObject ?: return null
    return IssueActor(actor.wireText("login") ?: return null, actor.wireText("name"))
}

private fun issueLabel(value: JsonObject): IssueLabel? = IssueLabel(value.wireText("name") ?: return null, value.wireText("color"))

internal fun issueState(value: JsonElement?): IssueState? {
    val state = value as? JsonObject ?: return null
    val open = when (state.wireText("category")) { "open" -> true; "closed" -> false; else -> return null }
    return IssueState(open, state.wireText("name") ?: if (open) "Open" else "Closed", state.wireText("closeReason"))
}

/** An `IssueSummary` (flat fields) with the project fields that `IssueListEntry` and `IssueDetail` add. */
internal fun issueSummary(value: JsonObject): IssueSummary? {
    val projectId = value.wireText("projectId") ?: return null
    val tracker = value.wireText("tracker") ?: return null
    val host = value.wireText("host") ?: return null
    val scope = value.wireText("scope") ?: return null
    val key = value.wireText("key") ?: return null
    return IssueSummary(
        ref = IssueRef(projectId, tracker, host, scope, key), hostName = host,
        displayKey = value.wireText("displayKey") ?: return null, title = value.wireText("title") ?: return null,
        url = value.wireText("url") ?: return null, author = issueActor(value["author"]),
        state = issueState(value["state"]) ?: return null,
        createdAt = value.wireText("createdAt").orEmpty(), updatedAt = value.wireText("updatedAt").orEmpty(), closedAt = value.wireText("closedAt"),
        assignees = value.rows("assignees").mapNotNull(::issueActor), labels = value.rows("labels").mapNotNull(::issueLabel),
        commentCount = value.wireCount("commentCount")?.coerceAtLeast(0) ?: 0, projectTitle = value.wireText("projectTitle").orEmpty(),
    )
}

internal fun parseIssueListPage(value: JsonObject): IssueListPage = IssueListPage(
    entries = value.rows("entries").mapNotNull(::issueSummary),
    trackers = value.rows("trackers").mapNotNull { row ->
        IssueTrackerStatus(row.wireText("tracker") ?: return@mapNotNull null, row.wireText("host") ?: return@mapNotNull null,
            row.wireCount("projectCount")?.coerceAtLeast(0) ?: 0, row.wireFlag("configured"), row.wireText("detail"))
    },
    errors = value.rows("errors").mapNotNull { row ->
        IssueProjectError(row.wireText("projectId") ?: return@mapNotNull null, row.wireText("projectTitle").orEmpty(), row.wireText("message").orEmpty())
    },
    nextCursors = value.obj("nextCursors").mapNotNull { (scope, cursor) ->
        (cursor as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && scope.isNotBlank() }?.let { scope to it }
    }.toMap(),
    scopes = value.rows("scopes").mapNotNull { row ->
        IssueScope(row.wireText("projectId") ?: return@mapNotNull null, row.wireText("tracker") ?: return@mapNotNull null, row.wireText("host"),
            row.wireText("scope") ?: return@mapNotNull null)
    },
)

internal fun issueComment(value: JsonObject): IssueComment? = IssueComment(
    id = value.wireText("id") ?: return null, author = issueActor(value["author"]),
    body = (value["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
    createdAt = value.wireText("createdAt").orEmpty(), url = value.wireText("url"),
)

private val EVENT_KINDS = mapOf(
    "closed" to IssueEventKind.Closed, "reopened" to IssueEventKind.Reopened, "labeled" to IssueEventKind.Labeled,
    "unlabeled" to IssueEventKind.Unlabeled, "assigned" to IssueEventKind.Assigned, "unassigned" to IssueEventKind.Unassigned,
    "renamed" to IssueEventKind.Renamed, "referenced" to IssueEventKind.Referenced, "state-changed" to IssueEventKind.StateChanged,
)

/** Unknown event kinds are dropped. */
internal fun issueEvent(value: JsonObject): IssueEvent? = IssueEvent(
    id = value.wireText("id") ?: return null, kind = EVENT_KINDS[value.wireText("kind")] ?: return null, actor = issueActor(value["actor"]),
    createdAt = value.wireText("createdAt").orEmpty(), detail = value.wireText("detail"), referenceUrl = value.obj("reference").wireText("url"),
)

private fun issueLinkedPr(value: JsonObject): IssueLinkedPr? = IssueLinkedPr(
    repository = value.wireText("repository") ?: return null, number = value.wireCount("number") ?: return null,
    title = value.wireText("title").orEmpty(), url = value.wireText("url") ?: return null, state = value.wireText("state").orEmpty(),
    draft = value.wireFlag("isDraft"), closesIssue = value.wireFlag("closesIssue"),
)

private fun issueCapabilities(value: JsonObject) = IssueCapabilities(
    create = value.wireFlag("create"), edit = value.wireFlag("edit"), comment = value.wireFlag("comment"), labels = value.wireFlag("labels"),
    assignees = value.wireFlag("assignees"), reactions = value.wireFlag("reactions"), templates = value.wireFlag("templates"),
    linkedPullRequests = value.wireFlag("linkedPullRequests"), events = value.wireFlag("events"), search = value.wireFlag("search"),
)

private fun issueViewerPermissions(value: JsonObject) = IssueViewerPermissions(
    setState = value.wireFlag("setState"), comment = value.wireFlag("comment"), edit = value.wireFlag("edit"),
    labels = value.wireFlag("labels"), assignees = value.wireFlag("assignees"),
)

/** `IssueDetail`; null when its summary fields are malformed. `viewer` may be a login string or an actor object. */
internal fun parseIssueDetail(value: JsonObject): IssueDetail? {
    val summary = issueSummary(value) ?: return null
    val viewer = (value["viewer"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: issueActor(value["viewer"])?.login
    return IssueDetail(
        summary = summary, writable = value.wireFlag("writable"), capabilities = issueCapabilities(value.obj("capabilities")),
        permissions = issueViewerPermissions(value.obj("viewerPermissions")), states = value.rows("states").mapNotNull(::issueState),
        viewer = viewer, body = (value["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
        comments = value.rows("comments").mapNotNull(::issueComment), commentsCursor = value.wireText("commentsCursor"),
        events = value.rows("events").mapNotNull(::issueEvent), linkedPullRequests = value.rows("linkedPullRequests").mapNotNull(::issueLinkedPr),
    )
}

internal fun parseIssueCommentsPage(value: JsonObject) = IssueCommentsPage(value.rows("comments").mapNotNull(::issueComment), value.wireText("nextCursor"))

internal fun parseIssueLinkedThreads(value: JsonObject): List<IssueLinkedThread> = value.rows("threads").mapNotNull { row ->
    IssueLinkedThread(row.wireText("id") ?: return@mapNotNull null, row.wireText("projectId").orEmpty(), row.wireText("title").orEmpty(),
        archived = row.wireText("archivedAt") != null, settled = row.wireFlag("settled"))
}

/** Shell row `issues[]` (absent and `[]` are the same); a malformed link is skipped. */
internal fun threadIssueLinks(value: JsonElement?): List<ThreadIssueLink> = (value as? JsonArray).orEmpty().mapNotNull { element ->
    val row = element as? JsonObject ?: return@mapNotNull null
    ThreadIssueLink(
        key = IssueKey(row.wireText("tracker") ?: return@mapNotNull null, row.wireText("host")?.lowercase() ?: return@mapNotNull null,
            row.wireText("scope") ?: return@mapNotNull null, row.wireText("key") ?: return@mapNotNull null),
        projectId = row.wireText("projectId") ?: return@mapNotNull null, url = row.wireText("url") ?: return@mapNotNull null,
        source = row.wireText("source") ?: "manual", linkedAt = row.wireText("linkedAt"),
        displayKey = row.wireText("displayKey") ?: return@mapNotNull null, title = row.wireText("title") ?: return@mapNotNull null,
    )
}

// --- Ports of T3 client-runtime list and comment merging ---

/** `mergeIssueListPage` row identity: project, lowercased host and scope, key. */
private fun IssueSummary.rowIdentity(): String = "${ref.projectId}\u0000${hostName.trim().lowercase()}\u0000${ref.scope.trim().lowercase()}\u0000${ref.key.trim()}"

private fun sortInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

/** Newest first by the requested date; unparsable dates sort last, ties keep their order. */
private fun issueOrder(sort: IssueSort): Comparator<IssueSummary> = compareByDescending<IssueSummary, Instant?>(nullsFirst()) {
    sortInstant(if (sort == IssueSort.Created) it.createdAt else it.updatedAt)
}

/**
 * Continues [held] with [page] (T3 `mergeIssueListPage` / `continueIssuePage`): a repeated row replaces the held one,
 * rows are sorted by [sort], and the cursors named in the request ([requested]) are replaced by the reply's. Trackers,
 * errors and scopes are merged with the reply winning. At most [maxRows] rows are kept.
 */
internal fun mergeIssuePage(held: IssueListPage, page: IssueListPage, sort: IssueSort,
    requested: Collection<String> = held.nextCursors.keys, maxRows: Int = ISSUE_HELD_ROWS_MAX): IssueListPage {
    val rows = LinkedHashMap<String, IssueSummary>()
    held.entries.forEach { rows[it.rowIdentity()] = it }
    page.entries.forEach { rows[it.rowIdentity()] = it }
    return IssueListPage(
        entries = rows.values.sortedWith(issueOrder(sort)).take(maxRows),
        trackers = (held.trackers + page.trackers).associateBy { "${it.tracker}\u0000${it.host.lowercase()}" }.values.toList(),
        errors = (held.errors + page.errors).associateBy { it.projectId }.values.toList(),
        nextCursors = held.nextCursors.filterKeys { it !in requested } + page.nextCursors,
        scopes = (held.scopes + page.scopes).associateBy { "${it.projectId}\u0000${it.tracker}\u0000${it.host.orEmpty().lowercase()}\u0000${it.scope.lowercase()}" }.values.toList(),
    )
}

/** Puts an older comment page before [current]; a comment already held keeps its held copy. */
internal fun prependOlderComments(current: List<IssueComment>, older: List<IssueComment>): List<IssueComment> {
    val held = current.mapTo(HashSet()) { it.id }
    val seen = HashSet<String>()
    return older.filter { it.id !in held && seen.add(it.id) } + current
}
