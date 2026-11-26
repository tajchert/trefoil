package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.IssueKey
import com.tajchert.trefoil.core.model.IssueRef
import com.tajchert.trefoil.core.model.IssueScope
import com.tajchert.trefoil.core.model.IssueState
import com.tajchert.trefoil.core.model.IssueSummary
import com.tajchert.trefoil.core.model.ThreadIssueLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IssueLinkResolverTest {
    private val ghScope = IssueScope("p1", "github", "github.com", "acme/app")
    private val linearScope = IssueScope("p2", "linear", "linear.app", "ENG")
    private val scopes = listOf(ghScope, linearScope)

    private fun resolve(
        url: String, links: List<ThreadIssueLink> = emptyList(), entries: List<IssueSummary> = emptyList(),
        scopes: List<IssueScope> = this.scopes, hosts: Map<String, List<String>> = emptyMap(),
    ) = IssueLinkResolver.resolve(url, links, entries, scopes, hosts)

    private fun summary(url: String, key: String = "7", scope: String = "acme/app", host: String = "github.com") = IssueSummary(
        ref = IssueRef("p9", "github", host, scope, key), hostName = host, displayKey = "#$key", title = "t", url = url,
        author = null, state = IssueState(true, "open"), createdAt = "", updatedAt = "", closedAt = null,
        assignees = emptyList(), labels = emptyList(), commentCount = 0, projectTitle = "P",
    )

    @Test fun normalizeLowercasesSchemeAndHostDropsQueryFragmentAndTrailingSlash() {
        assertEquals("https://github.com/Acme/App/issues/5", IssueLinkResolver.normalize("HTTPS://GitHub.COM/Acme/App/issues/5/?q=1#c"))
        assertEquals("https://github.com", IssueLinkResolver.normalize("https://github.com/"))
        assertEquals("http://ghe.corp:8080/a", IssueLinkResolver.normalize(" http://GHE.corp:8080/a "))
        assertEquals("https://github.com/a", IssueLinkResolver.normalize("https://github.com:443/a"))
    }

    @Test fun normalizeRejectsNonHttpAndMalformed() {
        assertNull(IssueLinkResolver.normalize("ftp://github.com/a/b/issues/1"))
        assertNull(IssueLinkResolver.normalize("javascript:alert(1)"))
        assertNull(IssueLinkResolver.normalize("mailto:a@b.c"))
        assertNull(IssueLinkResolver.normalize("github.com/a/b/issues/1"))
        assertNull(IssueLinkResolver.normalize("https:///a"))
        assertNull(IssueLinkResolver.normalize("https://github.com@evil.io/a/b/issues/1"))
    }

    @Test fun threadLinkMatchesByNormalizedUrl() {
        val key = IssueKey("github", "ghe.corp", "acme/app", "42")
        val link = ThreadIssueLink(key, "p3", "https://ghe.corp/acme/app/issues/42", displayKey = "#42", title = "x")
        val target = resolve("https://GHE.corp/acme/app/issues/42/?x=1#top", links = listOf(link), scopes = emptyList())
        assertEquals(IssueRef("p3", "github", "ghe.corp", "acme/app", "42"), target?.ref)
        assertEquals(key, target?.key)
    }

    @Test fun threadLinkWinsOverListEntry() {
        val url = "https://github.com/acme/app/issues/7"
        val key = IssueKey("github", "github.com", "acme/app", "7")
        val link = ThreadIssueLink(key, "linkProject", url, displayKey = "#7", title = "x")
        assertEquals("linkProject", resolve(url, links = listOf(link), entries = listOf(summary(url)))?.ref?.projectId)
    }

    @Test fun listEntryMatchesByNormalizedUrl() {
        val entry = summary("https://tracker.example/issue/abc", key = "abc", scope = "S", host = "tracker.example")
        val target = resolve("https://tracker.example/issue/abc/#frag", entries = listOf(entry), scopes = emptyList())
        assertEquals(entry.ref, target?.ref)
        assertEquals(entry.issueKey, target?.key)
    }

    @Test fun githubPatternUsesBareNumberAndScopeTracker() {
        val target = resolve("https://github.com/Acme/App/issues/123?notification_referrer_id=x#issuecomment-9")
        assertEquals(IssueRef("p1", "github", "github.com", "acme/app", "123"), target?.ref)
        assertEquals(IssueKey("github", "github.com", "acme/app", "123"), target?.key)
    }

    @Test fun linearPatternUsesTeamAsScope() {
        val target = resolve("https://linear.app/acme-ws/issue/eng-45/fix-the-thing")
        assertEquals(IssueRef("p2", "linear", "linear.app", "ENG", "ENG-45"), target?.ref)
        assertEquals(IssueKey("linear", "linear.app", "ENG", "ENG-45"), target?.key)
        assertNotNull(resolve("https://linear.app/acme-ws/issue/ENG-45"))
    }

    @Test fun linearTeamMustMatchScope() {
        assertNull(resolve("https://linear.app/acme-ws/issue/OPS-1"))
    }

    @Test fun githubScopeMustMatchExactly() {
        assertNull(resolve("https://github.com/acme/other/issues/1"))
        assertNull(resolve("https://github.com/acme/app-two/issues/1"))
    }

    @Test fun lookalikeHostsMatchNothing() {
        assertNull(resolve("https://github.com.evil.io/acme/app/issues/1"))
        assertNull(resolve("https://evilgithub.com/acme/app/issues/1"))
        assertNull(resolve("https://github.com@evil.io/acme/app/issues/1"))
        assertNull(resolve("https://linear.app.evil.io/acme-ws/issue/ENG-1"))
    }

    @Test fun portInHostMustMatchTheScopeHost() {
        assertNull(resolve("https://github.com:8443/acme/app/issues/1"))
        val ghe = IssueScope("p4", "github", "ghe.corp:8443", "acme/app")
        assertNotNull(resolve("https://ghe.corp:8443/acme/app/issues/1", scopes = listOf(ghe)))
        assertNull(resolve("https://ghe.corp/acme/app/issues/1", scopes = listOf(ghe)))
        assertNull(resolve("https://ghe.corp:9000/acme/app/issues/1", scopes = listOf(ghe)))
    }

    @Test fun enterpriseHostResolvesOnlyThroughScopes() {
        val url = "https://ghe.corp/acme/app/issues/3"
        assertNull(resolve(url))
        val target = resolve(url, scopes = scopes + IssueScope("p5", "github", "GHE.corp", "Acme/App"))
        assertEquals(IssueRef("p5", "github", "GHE.corp", "Acme/App", "3"), target?.ref)
    }

    @Test fun trackerHostCoversScopeWithoutHost() {
        val url = "https://ghe.corp/acme/app/issues/3"
        val hostless = IssueScope("p6", "github", null, "acme/app")
        assertNull(resolve(url, scopes = listOf(hostless)))
        assertEquals("ghe.corp", resolve(url, scopes = listOf(hostless), hosts = mapOf("github" to listOf("ghe.corp")))?.ref?.host)
        assertNull(resolve("https://github.com/acme/app/issues/3", scopes = listOf(hostless), hosts = mapOf("github" to listOf("ghe.corp"))))
    }

    @Test fun hostlessScopeOnlyMatchesHostsOfItsOwnTracker() {
        val hostless = IssueScope("p6", "github", null, "acme/app")
        val hosts = mapOf("github" to listOf("github.com"), "gitlab" to listOf("gitlab.corp"))
        assertEquals("github.com", resolve("https://github.com/acme/app/issues/3", scopes = listOf(hostless), hosts = hosts)?.ref?.host)
        assertNull(resolve("https://gitlab.corp/acme/app/issues/3", scopes = listOf(hostless), hosts = hosts))
    }

    @Test fun nonHttpSchemeIsNull() {
        assertNull(resolve("ftp://github.com/acme/app/issues/1"))
        assertNull(resolve("git@github.com:acme/app/issues/1"))
        assertNull(resolve("file:///acme/app/issues/1"))
    }

    @Test fun pullRequestIsNotAnIssue() {
        assertNull(resolve("https://github.com/acme/app/pull/1"))
        assertNull(resolve("https://github.com/acme/app/issues"))
        assertNull(resolve("https://github.com/acme/app/issues/abc"))
        assertNull(resolve("https://github.com/acme/app/issues/1/extra"))
    }
}
