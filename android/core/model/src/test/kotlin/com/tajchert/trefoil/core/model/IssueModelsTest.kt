package com.tajchert.trefoil.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IssueModelsTest {
    /** Same settings as the shell cache codec (core/session ShellCacheCodec). */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val link = ThreadIssueLink(IssueKey("github", "github.com", "owner/repo", "42"), "p", "https://github.com/owner/repo/issues/42",
        "manual", "2026-11-26T10:00:00Z", "#42", "Crash on start")

    @Test fun `identity ignores host and scope case and surrounding blanks`() {
        val a = IssueKey("github", "GitHub.com ", " Owner/Repo", "42")
        val b = IssueKey("github", "github.com", "owner/repo", " 42 ")
        assertEquals(a.identity, b.identity)
    }

    @Test fun `identity keeps tracker and key distinct`() {
        val base = IssueKey("github", "github.com", "owner/repo", "42")
        assertNotEquals(base.identity, base.copy(tracker = "gitlab").identity)
        assertNotEquals(base.identity, base.copy(key = "43").identity)
        assertNotEquals(IssueKey("github", "github.com", "owner/repo", "4").identity, IssueKey("github", "github.com", "owner/repo4", "").identity)
    }

    @Test fun `issue summary key takes the host from hostName`() {
        val summary = IssueSummary(IssueRef("p", "github", null, "owner/repo", "42"), "github.com", "#42", "Crash", "https://x",
            null, IssueState(true, "open"), "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z", null, emptyList(), emptyList(), 0, "Project")
        assertEquals(IssueKey("github", "github.com", "owner/repo", "42"), summary.issueKey)
    }

    @Test fun `thread summary with issues survives a shell cache round trip`() {
        val thread = ThreadSummary("t", "p", "Fix crash", issues = listOf(link, link.copy(linkedAt = null, key = link.key.copy(key = "7"))))
        val decoded = json.decodeFromString<ThreadSummary>(json.encodeToString(thread))
        assertEquals(thread, decoded)
        assertNull(decoded.issues[1].linkedAt)
    }

    @Test fun `old cached thread summary without issues still decodes`() {
        val decoded = json.decodeFromString<ThreadSummary>("""{"id":"t","projectId":"p","title":"Old","status":"idle","future":1}""")
        assertEquals(emptyList<ThreadIssueLink>(), decoded.issues)
        assertFalse(json.encodeToString(decoded).contains("issues"))
    }

    @Test fun `client defaults report issues as unsupported`() {
        val state = ClientState()
        assertFalse(state.issuesSupported)
        assertFalse(state.issueLinkedThreadsSupported)
    }
}
