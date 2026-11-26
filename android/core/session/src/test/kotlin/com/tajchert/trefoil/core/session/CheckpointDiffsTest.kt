package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure projection reads, RPC selection and the parsed-diff cache. Synthetic fixtures only. */
class CheckpointDiffsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun checkpointsSkipNullOrdinalKeepReadyOrFilesAndSortByTurn() {
        val projection = json("""{"checkpoints":[
            {"id":"c3","appRunOrdinal":3,"status":"ready","files":[],"capturedAt":"2026-01-01T00:00:00Z","extra":1},
            {"id":"cNull","appRunOrdinal":null,"status":"ready","files":[{"path":"a.kt","kind":"modified","additions":1,"deletions":0}]},
            {"id":"cMissing","status":"ready","files":[]},
            {"id":"c1","appRunOrdinal":1,"status":"ready","files":[{"path":"src/a.kt","kind":"modified","additions":4,"deletions":2},{"kind":"added"}]},
            {"id":"cErrEmpty","appRunOrdinal":4,"status":"error","files":[]},
            {"id":"c2","appRunOrdinal":2,"status":"stale","files":[{"path":"b.kt","kind":"added","additions":7,"deletions":0}],
             "filesTotal":{"count":250,"additions":900,"deletions":30}},
            {"id":"c5","appRunOrdinal":5,"status":"novel","files":[{"path":"c.kt","kind":"deleted","additions":0,"deletions":3}]}
        ]}""")
        val checkpoints = threadCheckpoints(projection)
        assertEquals(listOf(1, 2, 3, 5), checkpoints.map { it.turnCount })
        assertEquals(listOf("c1", "c2", "c3", "c5"), checkpoints.map { it.id })
        val first = checkpoints[0]
        assertEquals(listOf(CheckpointFile("src/a.kt", "modified", 4, 2)), first.files)
        assertNull(first.filesTotal)
        assertEquals(CheckpointStatus.Stale, checkpoints[1].status)
        assertEquals(CheckpointFileTotals(250, 900, 30), checkpoints[1].filesTotal)
        assertEquals(250, checkpoints[1].fileCount)
        assertEquals(900, checkpoints[1].additions)
        assertEquals("2026-01-01T00:00:00Z", checkpoints[2].capturedAt)
        assertEquals(CheckpointStatus.Unknown, checkpoints[3].status)
        assertTrue(threadCheckpoints(json("{}")).isEmpty())
    }

    @Test fun pullRequestLinksHideStackDismissedAndParseSnapshots() {
        val thread = json("""{"projectId":"p1","pullRequests":[
            {"host":"github.com","repository":"acme/app","number":12,"url":"https://example.test/acme/app/pull/12","source":"created",
             "linkedAt":"2026-01-01T00:00:00Z","stack":null,"future":{"x":1},
             "snapshot":{"state":"open","title":"Add feature","headBranch":"feat","baseBranch":"main","isDraft":true,
               "updatedAt":"2026-01-02T00:00:00Z","syncedAt":"2026-01-02T00:00:00Z","author":{"login":"octo","name":null,"avatarUrl":null,"isBot":false},
               "additions":10,"deletions":3,"changedFiles":2,"reviewDecision":"changes-requested","checksState":"failing",
               "mergeability":"conflicting","rebaseRequired":true}},
            {"host":"github.com","repository":"acme/app","number":13,"url":"u13","source":"stack-dismissed","snapshot":null},
            {"host":"github.com","repository":"acme/app","number":14,"url":"u14","source":"manual","snapshot":{"state":"weird","title":"T"}},
            {"host":"github.com","repository":"acme/app","number":15,"url":"u15","source":"agent","snapshot":{"state":"merged","title":"Minimal"}},
            {"host":"github.com","repository":"","number":16,"url":"u16","source":"manual"},
            {"host":"github.com","repository":"acme/app","number":0,"url":"u17","source":"manual"}
        ]}""")
        val links = threadPullRequestLinks(thread, "p1")
        assertEquals(listOf(12, 14, 15), links.map { it.key.number })
        assertEquals(PullRequestKey("p1", "github.com", "acme/app", 12), links[0].key)
        assertEquals("created", links[0].source)
        assertEquals(PrSnapshot(
            state = PrState.Open, title = "Add feature", headRef = "feat", baseRef = "main", isDraft = true,
            updatedAt = "2026-01-02T00:00:00Z", author = PrActor("octo", null, false), additions = 10, deletions = 3,
            changedFiles = 2, reviewDecision = PrReviewDecision.ChangesRequested, checksState = PrChecksState.Failing,
            mergeability = PrMergeability.Conflicting, rebaseRequired = true,
        ), links[0].snapshot)
        assertNull(links[1].snapshot)
        assertEquals(PrSnapshot(PrState.Merged, "Minimal", "", ""), links[2].snapshot)
        assertTrue(threadPullRequestLinks(json("{}"), "p1").isEmpty())
    }

    @Test fun turnOneAsksForTheFullThreadDiffAndLaterTurnsForTheirStep() {
        assertEquals(TurnDiffRequest("orchestration.getFullThreadDiff", json("""{"threadId":"t","toTurnCount":1}""")), turnDiffRequest("t", 1))
        assertEquals(TurnDiffRequest("orchestration.getTurnDiff", json("""{"threadId":"t","fromTurnCount":3,"toTurnCount":4,"ignoreWhitespace":false}""")), turnDiffRequest("t", 4))
        assertThrows(IllegalArgumentException::class.java) { turnDiffRequest("t", 0) }
    }

    @Test fun repliesWithSeveralRepositoriesUseTheirOwnDiffs() {
        assertEquals(listOf(null to "D"), turnDiffParts(json("""{"diff":"D"}""")))
        assertEquals(listOf(null to "D"), turnDiffParts(json("""{"diff":"D","repositories":[{"name":"one","diff":"X"}]}""")))
        assertEquals(listOf("one" to "A", "sub/two" to "B"), turnDiffParts(json(
            """{"diff":"D","repositories":[{"name":"one","repositoryPath":"/r/one","relativePath":".","diff":"A"},{"name":"","relativePath":"sub/two","diff":"B"}]}""")))
    }

    private fun doc(rows: Int) = DiffDocument(emptyList(), List(rows) { DiffRow.Notice(it.toLong(), -1, "") })

    @Test fun cacheEvictsLeastRecentlyUsedByCountAndRows() {
        val cache = TurnDiffCache(maxDocuments = 3, maxRows = 100)
        cache.put("e", "t", 1, doc(10)); cache.put("e", "t", 2, doc(10)); cache.put("e", "t", 3, doc(10))
        assertNotNull(cache.get("e", "t", 1)) // turn 1 is now most recent
        cache.put("e", "t", 4, doc(10))
        assertFalse(cache.contains("e", "t", 2))
        assertEquals(3, cache.size("e"))
        // By rows: with room for many documents, 70 more rows push 100 over the budget; the least recently used go.
        val rows = TurnDiffCache(maxDocuments = 10, maxRows = 100)
        rows.put("e", "t", 1, doc(40)); rows.put("e", "t", 2, doc(40))
        assertNotNull(rows.get("e", "t", 1))
        rows.put("e", "t", 3, doc(30))
        assertEquals(listOf(true, false, true), listOf(1, 2, 3).map { rows.contains("e", "t", it) })
        rows.put("e", "t", 4, doc(70))
        assertEquals(listOf(false, false, true, true), listOf(1, 2, 3, 4).map { rows.contains("e", "t", it) })
        // A document over the whole budget is never cached.
        cache.put("e", "t", 6, doc(101))
        assertFalse(cache.contains("e", "t", 6))
    }

    @Test fun cacheIsPerEnvironmentAndForgettable() {
        val cache = TurnDiffCache()
        cache.put("a", "t", 1, doc(1)); cache.put("b", "t", 1, doc(1))
        cache.forgetEnvironment("a")
        assertNull(cache.get("a", "t", 1))
        assertNotNull(cache.get("b", "t", 1))
        cache.put("c", "t", 1, doc(1))
        cache.retainOnly("c")
        assertNull(cache.get("b", "t", 1))
        assertNotNull(cache.get("c", "t", 1))
    }
}
