package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.IssueKey
import com.tajchert.trefoil.core.model.IssueRef
import com.tajchert.trefoil.core.model.IssueScope
import com.tajchert.trefoil.core.model.IssueSummary
import com.tajchert.trefoil.core.model.ThreadIssueLink

/** An issue a tapped URL resolved to: [ref] opens the detail, [key] identifies it for thread links. */
data class IssueLinkTarget(val ref: IssueRef, val key: IssueKey)

/**
 * Resolves a tapped chat URL to an issue the server has already named. Pure: no network, no logging, no Android.
 * Rules, in order: a selected-thread link with the same normalized URL; an in-memory list entry with the same
 * normalized URL; an issue-shaped URL whose host and scope exactly match a cached scope (a hostless scope is paired only
 * with the hosts of its own tracker in [trackerHosts], keyed by tracker). A lookalike host or a
 * different port matches nothing.
 */
object IssueLinkResolver {
    private const val LINEAR = "linear"
    private val parts = Regex("^(https?)://([^/?#]*)([^?#]*)", RegexOption.IGNORE_CASE)
    private val number = Regex("\\d+")
    private val linearKey = Regex("([A-Za-z][A-Za-z0-9]*)-(\\d+)")

    private class Parsed(val scheme: String, val host: String, val segments: List<String>) {
        val normalized: String get() = scheme + "://" + host + segments.joinToString("") { "/$it" }
    }

    /** Lowercase scheme and host, no fragment, query or trailing slash; null when [url] is not http(s). */
    fun normalize(url: String): String? = parse(url)?.normalized

    fun resolve(
        url: String,
        threadLinks: List<ThreadIssueLink>,
        listEntries: List<IssueSummary>,
        scopes: List<IssueScope>,
        trackerHosts: Map<String, List<String>> = emptyMap(),
    ): IssueLinkTarget? {
        val parsed = parse(url) ?: return null
        val normalized = parsed.normalized
        threadLinks.firstOrNull { normalize(it.url) == normalized }?.let { link ->
            val k = link.key
            return IssueLinkTarget(IssueRef(link.projectId, k.tracker, k.host, k.scope, k.key), k)
        }
        listEntries.firstOrNull { normalize(it.url) == normalized }?.let { return IssueLinkTarget(it.ref, it.issueKey) }
        return byScope(parsed, candidates(listEntries, scopes, trackerHosts))
    }

    private class Candidate(val projectId: String, val tracker: String, val host: String, val scope: String)

    private fun candidates(entries: List<IssueSummary>, scopes: List<IssueScope>, trackerHosts: Map<String, List<String>>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (s in scopes) {
            val hosts = if (s.host.isNullOrBlank()) trackerHosts[s.tracker].orEmpty() else listOf(s.host!!)
            for (h in hosts) if (h.isNotBlank()) out += Candidate(s.projectId, s.tracker, h.trim(), s.scope.trim())
        }
        for (e in entries) {
            if (e.hostName.isNotBlank()) out += Candidate(e.ref.projectId, e.ref.tracker, e.hostName.trim(), e.ref.scope.trim())
        }
        return out
    }

    private fun byScope(url: Parsed, candidates: List<Candidate>): IssueLinkTarget? {
        val seg = url.segments
        for (c in candidates) {
            if (!c.host.equals(url.host, ignoreCase = true)) continue
            if (c.tracker == LINEAR) {
                // https://linear.app/<workspace>/issue/<TEAM-n>[/slug]
                if (seg.size !in 3..4 || !seg[1].equals("issue", ignoreCase = true)) continue
                val m = linearKey.matchEntire(seg[2]) ?: continue
                if (!m.groupValues[1].equals(c.scope, ignoreCase = true)) continue
                val key = IssueKey(c.tracker, c.host, c.scope, c.scope + "-" + m.groupValues[2])
                return IssueLinkTarget(IssueRef(c.projectId, c.tracker, c.host, c.scope, key.key), key)
            } else {
                // https://<host>/<owner>/<repo>/issues/<n>
                if (seg.size != 4 || !seg[2].equals("issues", ignoreCase = true) || !number.matches(seg[3])) continue
                if (!"${seg[0]}/${seg[1]}".equals(c.scope, ignoreCase = true)) continue
                val key = IssueKey(c.tracker, c.host, c.scope, seg[3])
                return IssueLinkTarget(IssueRef(c.projectId, c.tracker, c.host, c.scope, key.key), key)
            }
        }
        return null
    }

    private fun parse(url: String): Parsed? {
        val m = parts.find(url.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        var authority = m.groupValues[2].lowercase()
        if (authority.isEmpty() || '@' in authority || authority.any { it.isWhitespace() }) return null
        val defaultPort = if (scheme == "https") ":443" else ":80"
        if (authority.endsWith(defaultPort)) authority = authority.removeSuffix(defaultPort)
        if (authority.isEmpty() || authority.startsWith(":")) return null
        return Parsed(scheme, authority, m.groupValues[3].split('/').filter { it.isNotEmpty() })
    }
}
