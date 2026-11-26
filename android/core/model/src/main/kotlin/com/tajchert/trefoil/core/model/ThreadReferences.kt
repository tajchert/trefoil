/* Thread reference markers and context records ported from T3 Code (MIT): packages/shared/src/composerContextReferences.ts
 * (`toKindScopedComposerContextId`, `sanitizeComposerContextLabel`, `formatComposerContextReference`,
 * `collectComposerContextReferences`, `threadContextRecord`) and packages/contracts/src/composerContext.ts.
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// A thread reference is a markdown link in the composer text: `[<label>](t3-context://v1/thread/<contextId>)`. The server pairs
// such occurrences with the `context.records` sent beside the message by `contextId`, so the marker and its record must agree.
// Every rule below mirrors upstream, including its JavaScript semantics (UTF-16 units, the JS `\s` set): ids and labels must
// be byte-identical to what a T3 web client produces, or the server will not pair them. Content: never log it.

private const val CONTEXT_HREF_PREFIX = "t3-context://v1/"
private const val THREAD_KIND = "thread"
private const val CONTEXT_LABEL_MAX_CHARS = 200
private const val LINK_LABEL_MAX_CHARS = 512
private const val LINK_HREF_MAX_CHARS = 200
private const val CONTEXT_ID_MAX_CHARS = 128
private const val SLUG_MAX_CHARS = 48
private const val CONTEXT_KIND_MAX_CHARS = 40

/** Most context records one message may carry (upstream `COMPOSER_CONTEXT_MAX_RECORDS`). */
const val THREAD_CONTEXT_MAX_RECORDS = 200

/** One thread named in composer text: its sanitized [label] and the [contextId] that pairs it with a context record. */
data class ThreadReference(val label: String, val contextId: String) {
    /** The composer-text marker for this reference. */
    fun marker(): String = "[$label]($CONTEXT_HREF_PREFIX$THREAD_KIND/$contextId)"
}

/** A [reference] found in text at [start] until [end] (exclusive), image `!` prefix included. */
data class ThreadReferenceMatch(val reference: ThreadReference, val start: Int, val end: Int)

// Whitespace is the JavaScript `\s` set ([isJsWhitespace], shared with the citation parser); Kotlin's Char.isWhitespace differs.

private fun isContextIdChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

/** `^[a-z0-9_-]{1,128}$` (case-insensitive), with no allowance for a trailing newline. */
private fun isContextId(value: String): Boolean = value.length in 1..CONTEXT_ID_MAX_CHARS && value.all(::isContextIdChar)

/** `^[a-z][a-z0-9-]{0,39}$` (case-sensitive). */
private fun isContextKind(value: String): Boolean =
    value.length in 1..CONTEXT_KIND_MAX_CHARS && value[0] in 'a'..'z' && value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

/** Upstream `fnv1a64`: two 32-bit FNV-1a passes, the second over the code units in reverse, as 16 hex digits. */
private fun fnv1a64(value: String): String {
    var forward = 0x811c9dc5.toInt()
    var reverse = 0x9dc5811c.toInt()
    for (index in value.indices) {
        forward = (forward xor value[index].code) * 0x01000193
        reverse = (reverse xor value[value.length - 1 - index].code) * 0x01000193
    }
    return forward.toUInt().toString(16).padStart(8, '0') + reverse.toUInt().toString(16).padStart(8, '0')
}

private val slugRuns = Regex("[^A-Za-z0-9_-]+")

/**
 * Upstream `toKindScopedComposerContextId(kind, id)`: `"<kind>_<id>"` when that is already a valid context id, else its slug
 * (non-`[a-z0-9_-]` runs as `-`, edge dashes trimmed, first 48 chars, `ctx` when empty), a dash and a 16-digit digest of the whole.
 */
fun toComposerContextId(kind: String, id: String): String {
    val producerId = "${kind}_$id"
    if (isContextId(producerId)) return producerId
    val slug = slugRuns.replace(producerId, "-").trim('-').take(SLUG_MAX_CHARS).ifEmpty { "ctx" }
    return "$slug-${fnv1a64(producerId)}"
}

private fun sanitizeLabel(raw: String, fallback: String): String {
    // `[ ] \ CR LF` become spaces, whitespace runs collapse to one space, the ends are trimmed.
    val normalized = StringBuilder(raw.length)
    var pendingSpace = false
    for (c in raw) {
        if (c == '[' || c == ']' || c == '\\' || isJsWhitespace(c)) {
            if (normalized.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) { normalized.append(' '); pendingSpace = false }
            normalized.append(c)
        }
    }
    var end = CONTEXT_LABEL_MAX_CHARS
    if (normalized.length > end && normalized[end - 1].isHighSurrogate() && normalized[end].isLowSurrogate()) end--
    val cleaned = if (normalized.length > end) normalized.substring(0, end) else normalized.toString()
    return cleaned.ifEmpty { fallback }
}

/** Upstream `sanitizeComposerContextLabel(label, "thread")`: safe inside `[...]`, one line, at most 200 UTF-16 units, never empty. */
fun sanitizeContextLabel(raw: String): String = sanitizeLabel(raw, THREAD_KIND)

/** The marker text for a thread: `[<sanitized title>](t3-context://v1/thread/<contextId>)`. */
fun threadReferenceMarker(threadId: String, title: String): String =
    ThreadReference(sanitizeContextLabel(title), toComposerContextId(THREAD_KIND, threadId)).marker()

private class ContextLink(val label: String, val rest: String, val start: Int, val end: Int)

/**
 * One attempt of upstream's `CONTEXT_LINK` at [p]: `(!?)\[([^[\]\n]{0,512})\]\((t3-context://v1/[^\s)]{1,200})\)`. Written by hand
 * because the bounds count UTF-16 units and `\s` is the JavaScript set, neither of which `java.util.regex` shares.
 */
private fun matchContextLink(text: String, p: Int): ContextLink? {
    val n = text.length
    var i = p
    if (text[i] == '!') i++
    if (i >= n || text[i] != '[') return null
    i++
    val labelStart = i
    while (i < n && i - labelStart <= LINK_LABEL_MAX_CHARS && text[i] != '[' && text[i] != ']' && text[i] != '\n') i++
    if (i - labelStart > LINK_LABEL_MAX_CHARS || i + 1 >= n || text[i] != ']' || text[i + 1] != '(') return null
    val labelEnd = i
    i += 2
    if (!text.startsWith(CONTEXT_HREF_PREFIX, i)) return null
    i += CONTEXT_HREF_PREFIX.length
    val restStart = i
    while (i < n && i - restStart <= LINK_HREF_MAX_CHARS && !isJsWhitespace(text[i]) && text[i] != ')') i++
    if (i - restStart !in 1..LINK_HREF_MAX_CHARS || i >= n || text[i] != ')') return null
    return ContextLink(text.substring(labelStart, labelEnd), text.substring(restStart, i), p, i + 1)
}

/** Upstream `collectComposerContextReferences`, thread kind only: links whose href is `thread/<valid context id>`, in order. */
fun collectThreadReferenceMatches(text: String): List<ThreadReferenceMatch> {
    val matches = ArrayList<ThreadReferenceMatch>()
    if (!text.contains("](t3-context:")) return matches
    var p = 0
    while (p < text.length) {
        if (text[p] != '!' && text[p] != '[') { p++; continue }
        val link = matchContextLink(text, p)
        if (link == null) { p++; continue }
        // A link that matched but does not parse (bad kind or id, not a thread) is consumed all the same.
        p = link.end
        val parts = link.rest.split('/')
        if (parts.size != 2 || !isContextKind(parts[0]) || !isContextId(parts[1]) || parts[0] != THREAD_KIND) continue
        matches += ThreadReferenceMatch(ThreadReference(sanitizeLabel(link.label, THREAD_KIND), parts[1]), link.start, link.end)
    }
    return matches
}

/** The thread references in [text], in order (a thread named twice appears twice). */
fun collectThreadReferences(text: String): List<ThreadReference> = collectThreadReferenceMatches(text).map { it.reference }

/**
 * The context records to send beside [text]: one per distinct reference whose context id is that of a known, not deleted thread
 * in [threads] (all of environment [environmentId]), in order of first appearance, at most [THREAD_CONTEXT_MAX_RECORDS]. Label
 * and title are the thread's current title, sanitized. Null when there is none, so the caller sends no `context` at all.
 */
fun threadContextRecords(text: String, threads: List<ThreadSummary>, environmentId: String): JsonArray? {
    val contextIds = collectThreadReferences(text).map { it.contextId }.distinct()
    if (contextIds.isEmpty()) return null
    val wanted = contextIds.toHashSet()
    val known = HashMap<String, ThreadSummary>()
    for (thread in threads) {
        if (thread.deletedAt != null) continue
        val contextId = toComposerContextId(THREAD_KIND, thread.id)
        if (contextId in wanted) known.putIfAbsent(contextId, thread)
    }
    val records = contextIds.mapNotNull { contextId -> known[contextId]?.let { threadContextRecord(contextId, it, environmentId) } }
    return if (records.isEmpty()) null else JsonArray(records.take(THREAD_CONTEXT_MAX_RECORDS))
}

private fun threadContextRecord(contextId: String, thread: ThreadSummary, environmentId: String): JsonObject {
    val label = sanitizeContextLabel(thread.title)
    return buildJsonObject {
        put("version", 1)
        put("kind", THREAD_KIND)
        put("contextId", contextId)
        put("label", label)
        put("environmentId", environmentId)
        put("threadId", thread.id)
        put("title", label)
    }
}

/**
 * A composer's text seen as the user's own [body] plus the thread [references] named in it. The text itself, markers included,
 * is what the composer stores as its draft and sends (like [CitationDraft]). Content: never log it.
 */
data class ThreadReferenceDraft(val body: String, val references: List<ThreadReference>) {
    /** The composer text: the markers one per line, then a blank line and [body] (as sent). */
    fun text(): String = joinThreadReferenceDraft(body, references)
}

/** Splits composer text like [splitCitationDraft]: leading markers, one per line then a blank line, come off with that separator; a marker anywhere else is lifted out of the body. */
fun splitThreadReferenceDraft(text: String): ThreadReferenceDraft {
    val matches = collectThreadReferenceMatches(text)
    if (matches.isEmpty()) return ThreadReferenceDraft(text, emptyList())
    val references = matches.map { it.reference }
    var leading = 0
    while (leading < matches.size && matches[leading].start == (if (leading == 0) 0 else matches[leading - 1].end + 1) &&
        (leading == 0 || text[matches[leading].start - 1] == '\n')) leading++
    var cursor = if (leading == 0) 0 else matches[leading - 1].end.let { end -> if (text.startsWith("\n\n", end)) end + 2 else end }
    if (leading == matches.size) return ThreadReferenceDraft(text.substring(cursor), references)
    val body = StringBuilder()
    for (match in matches.drop(leading)) { body.append(text, cursor, match.start); cursor = match.end }
    return ThreadReferenceDraft(body.append(text, cursor, text.length).toString(), references)
}

fun joinThreadReferenceDraft(body: String, references: List<ThreadReference>): String {
    if (references.isEmpty()) return body
    val markers = references.joinToString("\n") { it.marker() }
    return if (body.isEmpty()) markers else "$markers\n\n$body"
}
