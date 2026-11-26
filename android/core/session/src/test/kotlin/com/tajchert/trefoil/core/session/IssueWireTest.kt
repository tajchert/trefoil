package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class IssueWireTest {
    @Test fun listFixtureKeepsWellFormedRowsAndSkipsMalformedOnes() {
        val page = parseIssueListPage(fixture("issue-list.json"))
        assertEquals(listOf("12", "TEAM-7"), page.entries.map { it.ref.key })
        val first = page.entries[0]
        assertEquals(IssueRef("project-a", "github", "github.com", "fixture/app", "12"), first.ref)
        assertEquals("#12", first.displayKey)
        assertEquals(IssueActor("fixture-author", "Fixture Author"), first.author)
        assertEquals(IssueState(true, "Open"), first.state)
        assertEquals(listOf("fixture-assignee"), first.assignees.map { it.login })
        assertEquals(listOf(IssueLabel("bug", "d73a4a")), first.labels)
        assertEquals(3, first.commentCount)
        assertEquals("Fixture App", first.projectTitle)
        assertEquals(IssueKey("github", "github.com", "fixture/app", "12"), first.issueKey)
        val unknownTracker = page.entries[1]
        assertEquals("future-tracker", unknownTracker.ref.tracker)
        assertNull(unknownTracker.author)
        assertEquals(IssueState(false, "Done", "completed"), unknownTracker.state)
        assertEquals("2026-11-21T10:00:00.000Z", unknownTracker.closedAt)
        assertEquals(listOf("github", "future-tracker"), page.trackers.map { it.tracker })
        assertFalse(page.trackers[1].configured)
        assertEquals(listOf(IssueProjectError("project-c", "Fixture Broken", "Tracker query failed")), page.errors)
        assertEquals(mapOf("github.com fixture/app" to "cursor-2"), page.nextCursors)
        assertEquals(listOf(IssueScope("project-a", "github", "github.com", "fixture/app"), IssueScope("project-b", "future-tracker", null, "TEAM")), page.scopes)
    }

    @Test fun emptyAndPartialListRepliesParse() {
        val empty = parseIssueListPage(Json.parseToJsonElement("""{"viewers":{},"trackers":[],"entries":[],"errors":[],"nextCursors":{},"scopes":[]}""").jsonObject)
        assertEquals(IssueListPage(emptyList(), emptyList(), emptyList(), emptyMap(), emptyList()), empty)
        assertEquals(empty, parseIssueListPage(JsonObject(emptyMap())))
    }

    @Test fun detailFixtureParsesOptionalsAndDropsUnknownEventKinds() {
        val detail = parseIssueDetail(fixture("issue-detail.json"))!!
        assertEquals("12", detail.summary.ref.key)
        assertTrue(detail.writable)
        assertTrue(detail.capabilities.linkedPullRequests && detail.capabilities.search && !detail.capabilities.reactions)
        assertEquals(IssueViewerPermissions(setState = true, comment = true), detail.permissions)
        assertEquals(listOf(IssueState(true, "Open"), IssueState(false, "Closed")), detail.states)
        assertEquals("fixture-viewer", detail.viewer)
        assertEquals("Synthetic fixture body.", detail.body)
        assertEquals(listOf("c2", "c3"), detail.comments.map { it.id })
        assertNull(detail.comments[1].author); assertNull(detail.comments[1].url)
        assertEquals("comments-cursor-1", detail.commentsCursor)
        assertEquals(listOf(IssueEventKind.Labeled, IssueEventKind.StateChanged), detail.events.map { it.kind })
        assertEquals("https://github.com/fixture/app/pull/20", detail.events[1].referenceUrl)
        assertEquals(listOf(IssueLinkedPr("fixture/app", 20, "Fixture fix", "https://github.com/fixture/app/pull/20", "open", true, true)), detail.linkedPullRequests)
    }

    @Test fun detailToleratesMissingOptionalsAndRejectsAMalformedSummary() {
        val minimal = buildJsonObject {
            put("tracker", "github"); put("host", "github.com"); put("scope", "o/r"); put("key", "1"); put("displayKey", "#1")
            put("title", "T"); put("url", "https://github.com/o/r/issues/1"); put("state", buildJsonObject { put("category", "open"); put("name", "Open") })
            put("projectId", "p"); put("viewer", "login-string")
        }
        val detail = parseIssueDetail(minimal)!!
        assertEquals(IssueCapabilities(), detail.capabilities)
        assertEquals(IssueViewerPermissions(), detail.permissions)
        assertEquals("login-string", detail.viewer)
        assertEquals("", detail.body)
        assertNull(detail.commentsCursor)
        assertTrue(detail.comments.isEmpty() && detail.events.isEmpty() && detail.linkedPullRequests.isEmpty())
        assertNull(parseIssueDetail(JsonObject(minimal - "url")))
        assertNull(parseIssueDetail(JsonObject(minimal + ("key" to JsonPrimitive(" ")))))
    }

    @Test fun commentsAndLinkedThreadsParse() {
        val comments = parseIssueCommentsPage(Json.parseToJsonElement("""{"comments":[{"id":"c1","body":"x","createdAt":"t"},{"body":"no id"}],"nextCursor":null}""").jsonObject)
        assertEquals(listOf("c1"), comments.comments.map { it.id }); assertNull(comments.nextCursor)
        val threads = parseIssueLinkedThreads(Json.parseToJsonElement("""{"threads":[{"id":"t1","projectId":"p","title":"A","archivedAt":null,"settled":true},{"id":"t2","projectId":"p","title":"B","archivedAt":"2026-11-01T00:00:00Z","settled":false},{"title":"no id"}]}""").jsonObject)
        assertEquals(listOf(IssueLinkedThread("t1", "p", "A", false, true), IssueLinkedThread("t2", "p", "B", true, false)), threads)
    }

    @Test fun shellIssueLinksParseAbsentEmptyAndMalformedRows() {
        assertTrue(threadIssueLinks(null).isEmpty())
        assertTrue(threadIssueLinks(JsonArray(emptyList())).isEmpty())
        val links = threadIssueLinks(Json.parseToJsonElement("""[
            {"tracker":"github","host":"GitHub.com","scope":"o/r","key":"1","projectId":"p","url":"https://github.com/o/r/issues/1","source":"agent","linkedAt":"2026-11-01T00:00:00Z","displayKey":"#1","title":"One"},
            {"tracker":"github","host":"github.com","scope":"o/r","key":"2","projectId":"p","url":"https://github.com/o/r/issues/2","displayKey":"#2","title":"Two"},
            {"tracker":"github","host":"github.com","scope":"o/r","key":"3","projectId":"p","url":"","displayKey":"#3","title":"Three"},
            7
        ]"""))
        assertEquals(2, links.size)
        assertEquals(IssueKey("github", "github.com", "o/r", "1"), links[0].key)
        assertEquals("agent", links[0].source)
        assertEquals("manual", links[1].source); assertNull(links[1].linkedAt)
    }

    @Test fun shellRowCarriesIssues() {
        val row = threadSummary(Json.parseToJsonElement("""{"id":"t","projectId":"p","title":"T","issues":[{"tracker":"github","host":"github.com","scope":"o/r","key":"1","projectId":"p","url":"https://github.com/o/r/issues/1","displayKey":"#1","title":"One"}]}""").jsonObject)
        assertEquals(listOf("#1"), row.issues.map { it.displayKey })
        assertTrue(threadSummary(Json.parseToJsonElement("""{"id":"t","projectId":"p","title":"T"}""").jsonObject).issues.isEmpty())
    }

    @Test fun listInputSendsOnlyNonDefaultFields() {
        val plain = issueListInput(IssueQuery(), null, 50, false)
        assertEquals(buildJsonObject { put("state", "open"); put("limit", 50) }, plain)
        val full = issueListInput(IssueQuery(IssueListState.All, IssueInvolvement.Assigned, "p", "  " + "x".repeat(250) + "  ",
            (1..30).map { "l$it" } + listOf("l1", " "), " octo ", IssueSort.Created), mapOf("github.com o/r" to "c1", "" to "x"), 500, true)
        assertEquals("all", full["state"]!!.jsonPrimitive.content)
        assertEquals("assigned", full["involvement"]!!.jsonPrimitive.content)
        assertEquals("p", full["projectId"]!!.jsonPrimitive.content)
        assertEquals(200, full["limit"]!!.jsonPrimitive.int)
        assertEquals(buildJsonObject { put("github.com o/r", "c1") }, full["cursors"])
        assertEquals(true, full["refresh"]!!.jsonPrimitive.boolean)
        assertEquals(ISSUE_QUERY_MAX_CHARS, full["query"]!!.jsonPrimitive.content.length)
        assertEquals((1..20).map { "l$it" }, full["labels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("octo", full["assignee"]!!.jsonPrimitive.content)
        assertEquals("created", full["sort"]!!.jsonPrimitive.content)
        assertFalse("query" in issueListInput(IssueQuery(text = "   "), null, 50, false))
    }

    @Test fun refPayloadNeverCarriesRefresh() {
        val ref = IssueRef("p", "github", null, "o/r", "1")
        assertEquals(buildJsonObject { put("projectId", "p"); put("tracker", "github"); put("scope", "o/r"); put("key", "1") }, issueRefPayload(ref))
        assertEquals("c", issueRefPayload(ref.copy(host = "github.com"), "c")["cursor"]!!.jsonPrimitive.content)
        assertEquals("github.com", issueKeyPayload(IssueKey("github", " GitHub.COM ", "o/r", "1"))["host"]!!.jsonPrimitive.content)
    }

    @Test fun linkFieldsNormaliseAndValidate() {
        val link = ThreadIssueLink(IssueKey("github", "GitHub.com", " o/r ", " 1 "), "p", " https://github.com/o/r/issues/1 ", source = "agent", displayKey = "#1", title = " One ")
        val fields = issueLinkFields(link)
        assertEquals("github.com", fields["host"]!!.jsonPrimitive.content)
        assertEquals("o/r", fields["scope"]!!.jsonPrimitive.content)
        assertEquals("1", fields["key"]!!.jsonPrimitive.content)
        assertEquals("manual", fields["source"]!!.jsonPrimitive.content)
        assertEquals("One", fields["title"]!!.jsonPrimitive.content)
        assertEquals(setOf("tracker", "host", "scope", "key", "projectId", "url", "displayKey", "title", "source"), fields.keys)
        for (blank in listOf(link.copy(title = " "), link.copy(displayKey = ""), link.copy(url = "  "), link.copy(key = link.key.copy(key = "")))) {
            assertThrows(IllegalArgumentException::class.java) { issueLinkFields(blank) }
        }
        assertEquals(setOf("tracker", "host", "scope", "key"), issueUnlinkFields(link.key).keys)
    }

    @Test fun failuresMapByTagAndReasonOnly() {
        fun exit(cause: String) = Json.parseToJsonElement("""{"_tag":"Failure","cause":$cause}""").jsonObject
        fun mapped(cause: String) = issueRejection(firstFailReason(exit(cause)))
        assertEquals(IssueFailure.Unavailable, mapped("""[{"_tag":"Fail","error":{"_tag":"IssueUnavailableError","reason":"tracker-unsupported"}}]""").failure)
        assertEquals(IssueFailure.Unavailable, mapped("""[{"_tag":"Fail","error":{"_tag":"IssueUnavailableError","reason":"tool-missing","tracker":"github"}}]""").failure)
        assertEquals(IssueFailure.NotFound, mapped("""[{"_tag":"Fail","error":{"_tag":"IssueOperationError","operation":"detail","detail":"secret text","reason":"not-found"}}]""").failure)
        assertEquals(IssueFailure.ReadOnly, mapped("""[{"_tag":"Fail","error":{"_tag":"IssueOperationError","reason":"read-only"}}]""").failure)
        val limited = mapped("""[{"_tag":"Fail","error":{"_tag":"IssueOperationError","reason":"rate-limited","retryAt":1767225600000}}]""")
        assertEquals(IssueFailure.RateLimited, limited.failure); assertEquals(1767225600000L, limited.retryAtMillis)
        assertEquals(IssueFailure.Failed, mapped("""[{"_tag":"Fail","error":{"_tag":"IssueOperationError","operation":"list","detail":"x"}}]""").failure)
        assertEquals(IssueFailure.NoPermission, mapped("""[{"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError"}}]""").failure)
        assertEquals(IssueFailure.Failed, mapped("""[{"_tag":"Die","defect":"Missing key\n  at [\"state\"]"}]""").failure)
        assertEquals(IssueFailure.Failed, mapped("""[{"_tag":"Fail","error":{"_tag":"SomethingNew"}}]""").failure)
        // The first Fail wins, even after a Die; a single reason object is tolerated.
        assertEquals(IssueFailure.NotFound, mapped("""[{"_tag":"Die","defect":"x"},{"_tag":"Fail","error":{"_tag":"IssueOperationError","reason":"not-found"}},{"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError"}}]""").failure)
        assertEquals(IssueFailure.NoPermission, mapped("""{"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError"}}""").failure)
        val reduced = firstFailReason(exit("""[{"_tag":"Fail","error":{"_tag":"IssueOperationError","operation":"detail","detail":"secret text","reason":"not-found","cause":{"message":"secret"}}}]"""))
        assertEquals(RpcFailReason("IssueOperationError", "not-found", null), reduced)
        assertFalse(mapped("""[{"_tag":"Fail","error":{"_tag":"IssueOperationError","detail":"secret text","reason":"not-found"}}]""").message!!.contains("secret"))
    }

    @Test fun mergeReplacesRepeatsKeepsOtherScopesSortsAndReplacesRequestedCursors() {
        val held = IssueListPage(listOf(summary("1", "2026-11-10T00:00:00Z"), summary("2", "2026-11-05T00:00:00Z", scope = "o/other")),
            listOf(IssueTrackerStatus("github", "github.com", 1, false, null)), emptyList(),
            mapOf("github.com o/r" to "c1", "github.com o/other" to "d1"), listOf(IssueScope("p", "github", "github.com", "o/r")))
        val page = IssueListPage(listOf(summary("1", "2026-11-20T00:00:00Z", title = "Updated", host = "GitHub.com", scope = "O/R"), summary("3", "2026-11-07T00:00:00Z")),
            listOf(IssueTrackerStatus("github", "github.com", 1, true, null)), listOf(IssueProjectError("p", "P", "m")),
            mapOf("github.com o/r" to "c2"), listOf(IssueScope("p", "github", "github.com", "o/r")))
        val merged = mergeIssuePage(held, page, IssueSort.Updated, requested = setOf("github.com o/r"))
        assertEquals(listOf("1", "3", "2"), merged.entries.map { it.ref.key })
        assertEquals("Updated", merged.entries[0].title)
        assertEquals(mapOf("github.com o/r" to "c2", "github.com o/other" to "d1"), merged.nextCursors)
        assertTrue(merged.trackers.single().configured)
        assertEquals(1, merged.errors.size); assertEquals(1, merged.scopes.size)
        // A finished scope drops its cursor when it was requested and the reply omits it.
        assertEquals(mapOf("github.com o/other" to "d1"), mergeIssuePage(held, page.copy(nextCursors = emptyMap()), IssueSort.Updated, setOf("github.com o/r")).nextCursors)
        val byCreated = mergeIssuePage(held, page, IssueSort.Created, setOf("github.com o/r"))
        assertEquals(listOf("3", "1", "2"), byCreated.entries.map { it.ref.key })
        // The same key in another project is a different row.
        val otherProject = mergeIssuePage(held, page.copy(entries = listOf(summary("1", "2026-11-01T00:00:00Z", projectId = "q"))), IssueSort.Updated)
        assertEquals(3, otherProject.entries.size)
        assertEquals(1, mergeIssuePage(held, page, IssueSort.Updated, maxRows = 1).entries.size)
    }

    @Test fun olderCommentsPrependWithoutDuplicates() {
        val current = listOf(IssueComment("c3", null, "three", "t3", null), IssueComment("c4", null, "four", "t4", null))
        val older = listOf(IssueComment("c1", null, "one", "t1", null), IssueComment("c2", null, "two", "t2", null), IssueComment("c3", null, "stale", "t3", null))
        val merged = prependOlderComments(current, older)
        assertEquals(listOf("c1", "c2", "c3", "c4"), merged.map { it.id })
        assertEquals("three", merged[2].body)
    }

    private fun summary(key: String, at: String, title: String = "T$key", host: String = "github.com", scope: String = "o/r", projectId: String = "p") =
        IssueSummary(IssueRef(projectId, "github", host, scope, key), host, "#$key", title, "https://github.com/o/r/issues/$key", null,
            IssueState(true, "Open"), createdAt = if (key == "3") "2026-11-30T00:00:00Z" else "2026-11-01T00:00:00Z", updatedAt = at,
            closedAt = null, assignees = emptyList(), labels = emptyList(), commentCount = 0, projectTitle = "P")

    companion object {
        fun fixture(name: String): JsonObject {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, "protocol/fixtures/$name").isFile) dir = dir.parentFile
            return Json.parseToJsonElement(File(dir ?: error("Fixture $name not found"), "protocol/fixtures/$name").readText()).jsonObject
        }
    }
}
