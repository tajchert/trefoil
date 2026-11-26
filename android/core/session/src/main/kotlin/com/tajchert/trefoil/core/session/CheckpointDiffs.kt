package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.serialization.json.*

/*
 * Turn diffs of the selected thread (`orchestration.getFullThreadDiff` / `getTurnDiff`) and the projection reads behind
 * them (`checkpoints`, `thread.pullRequests`). Diff text, file paths and PR titles are conversation content: nothing
 * here logs them.
 */

/** Parses one diff reply: each part is (repository label or null, unified diff text), in wire order. */
fun interface DiffParser { fun parse(parts: List<Pair<String?, String>>): DiffDocument }

/** A turn diff could not be shown. [message] is safe to display: it never carries the server's error or `cause`. */
class TurnDiffException(message: String) : IllegalStateException(message) {
    companion object {
        const val TOO_LARGE = "Diff too large for mobile"
        const val UNAVAILABLE = "Could not load this diff"
        const val THREAD_CHANGED = "Thread changed; open the diff again"
    }
}

/** The RPC a turn diff needs: turn 1 is the full thread diff up to it, turn n the diff between n-1 and n. */
internal data class TurnDiffRequest(val tag: String, val payload: JsonObject)

internal fun turnDiffRequest(threadId: String, turnCount: Int): TurnDiffRequest {
    require(turnCount >= 1) { "Turns start at 1" }
    return if (turnCount == 1) TurnDiffRequest("orchestration.getFullThreadDiff", buildJsonObject {
        put("threadId", threadId); put("toTurnCount", 1)
    }) else TurnDiffRequest("orchestration.getTurnDiff", buildJsonObject {
        put("threadId", threadId); put("fromTurnCount", turnCount - 1); put("toTurnCount", turnCount); put("ignoreWhitespace", false)
    })
}

/**
 * T3 `ThreadTurnDiff {diff, repositories?: [{name, repositoryPath, relativePath, diff}]}` as parser parts: the
 * per-repository diffs when more than one repository is reported, otherwise the single `diff`.
 */
internal fun turnDiffParts(reply: JsonObject): List<Pair<String?, String>> {
    val repositories = reply.objects("repositories")
    if (repositories.size > 1) return repositories.map { repo ->
        repo.string("name").ifBlank { repo.string("relativePath") }.ifBlank { null } to repo.string("diff")
    }
    return listOf(null to reply.string("diff"))
}

/**
 * Parsed turn diffs, per environment, most recently used last: at most [maxDocuments] per environment and [maxRows]
 * rows in total. Memory only. A completed turn's diff is immutable, so entries never go stale while kept.
 */
internal class TurnDiffCache(private val maxDocuments: Int = 6, private val maxRows: Int = 120_000) {
    private data class Key(val threadId: String, val turnCount: Int)
    private val environments = linkedMapOf<String, LinkedHashMap<Key, DiffDocument>>()

    @Synchronized fun get(environmentId: String, threadId: String, turnCount: Int): DiffDocument? {
        val docs = environments[environmentId] ?: return null
        val key = Key(threadId, turnCount)
        val doc = docs.remove(key) ?: return null
        docs[key] = doc
        return doc
    }

    @Synchronized fun put(environmentId: String, threadId: String, turnCount: Int, document: DiffDocument) {
        if (document.rows.size > maxRows) return
        val docs = environments.getOrPut(environmentId) { linkedMapOf() }
        val key = Key(threadId, turnCount)
        docs.remove(key)
        docs[key] = document
        while (docs.size > maxDocuments) docs.remove(docs.keys.first())
        while (totalRows() > maxRows) evictOldest()
    }

    @Synchronized fun forgetEnvironment(environmentId: String?) { environments.remove(environmentId) }

    /** Drops every environment except [environmentId] (an environment switch). */
    @Synchronized fun retainOnly(environmentId: String?) { environments.keys.retainAll { it == environmentId } }

    @Synchronized fun size(environmentId: String): Int = environments[environmentId]?.size ?: 0

    @Synchronized fun contains(environmentId: String, threadId: String, turnCount: Int): Boolean =
        environments[environmentId]?.containsKey(Key(threadId, turnCount)) == true

    private fun totalRows() = environments.values.sumOf { docs -> docs.values.sumOf { it.rows.size } }

    /** Evicts the least recently used document of the environment holding the most rows. */
    private fun evictOldest() {
        val (id, docs) = environments.entries.filter { it.value.isNotEmpty() }
            .maxByOrNull { entry -> entry.value.values.sumOf { it.rows.size } }?.toPair() ?: return
        docs.remove(docs.keys.first())
        if (docs.isEmpty()) environments.remove(id)
    }
}

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Projection `checkpoints` the Changes view lists: those with an `appRunOrdinal` (the turn), either ready or listing
 * files, sorted by turn (wire order breaks ties). Malformed entries are skipped; unknown fields are ignored.
 */
internal fun threadCheckpoints(projection: JsonObject): List<ThreadCheckpoint> =
    projection.objects("checkpoints").mapNotNull { value ->
        val turn = value.int("appRunOrdinal")?.takeIf { it >= 1 } ?: return@mapNotNull null
        val status = when (value.string("status")) {
            "ready" -> CheckpointStatus.Ready
            "missing" -> CheckpointStatus.Missing
            "error" -> CheckpointStatus.Error
            "stale" -> CheckpointStatus.Stale
            else -> CheckpointStatus.Unknown
        }
        val files = value.objects("files").mapNotNull { file ->
            val path = file.text("path")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CheckpointFile(path, file.string("kind"), file.int("additions") ?: 0, file.int("deletions") ?: 0)
        }
        if (status != CheckpointStatus.Ready && files.isEmpty()) return@mapNotNull null
        val totals = (value["filesTotal"] as? JsonObject)?.let { total ->
            val count = total.int("count") ?: return@let null
            CheckpointFileTotals(count, total.int("additions") ?: 0, total.int("deletions") ?: 0)
        }
        ThreadCheckpoint(value.string("id"), turn, status, files, totals, value.text("capturedAt"))
    }.sortedBy { it.turnCount }

/**
 * Thread `pullRequests` links, without `stack-dismissed` ones (hidden upstream), in wire order. [projectId] is the
 * thread's: links carry none. A link without repository or a positive number is skipped; a malformed snapshot is null.
 */
internal fun threadPullRequestLinks(thread: JsonObject, projectId: String): List<ThreadPullRequest> =
    thread.objects("pullRequests").mapNotNull { link ->
        val source = link.string("source")
        if (source == "stack-dismissed") return@mapNotNull null
        val repository = link.string("repository").trim().ifEmpty { return@mapNotNull null }
        val number = link.int("number")?.takeIf { it > 0 } ?: return@mapNotNull null
        val key = PullRequestKey(projectId, link.text("host")?.trim()?.ifEmpty { null }, repository, number)
        ThreadPullRequest(key, link.string("url"), source, (link["snapshot"] as? JsonObject)?.let(::prSnapshot))
    }

/** Upstream `ThreadPullRequestSnapshot`, read leniently: null without a known state or a title. */
internal fun prSnapshot(value: JsonObject): PrSnapshot? {
    val state = PrState.fromWire(value.text("state")) ?: return null
    val title = value.text("title")?.takeIf { it.isNotBlank() } ?: return null
    val author = (value["author"] as? JsonObject)?.let { actor ->
        val login = actor.text("login")?.takeIf { it.isNotBlank() } ?: return@let null
        PrActor(login, actor.text("name"), actor.bool("isBot") == true)
    }
    return PrSnapshot(
        state = state, title = title,
        headRef = value.string("headBranch"), baseRef = value.string("baseBranch"),
        isDraft = value.bool("isDraft") == true, updatedAt = value.text("updatedAt"), author = author,
        additions = value.int("additions"), deletions = value.int("deletions"), changedFiles = value.int("changedFiles"),
        reviewDecision = PrReviewDecision.fromWire(value.text("reviewDecision")),
        checksState = PrChecksState.fromWire(value.text("checksState")),
        mergeability = value.text("mergeability")?.let(PrMergeability::fromWire),
        rebaseRequired = value.bool("rebaseRequired") == true,
    )
}

/** Placeholder until the T2 parser lands: one document with no rows. */
internal val UnifiedDiffParserAdapter = DiffParser { UnifiedDiffParser.parseRepositories(it) }
