/* Adapted from T3 Code (MIT): packages/shared/src/assistantCitations.ts, packages/contracts/src/assistantCitations.ts and
 * apps/mobile/src/lib/assistantCitationSelection.ts (#35306).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * Assistant citations: a quote of an earlier assistant reply travels as plain markdown inside the user's message,
 * `[Assistant quote](t3-citation://v1/<env>/<thread>/<message>?text&start&end&prefix&suffix[&comment])`. The server
 * expands the link for the provider, so this client only formats, parses and renders it. The format, the strict
 * parser and the limits follow upstream byte for byte. Citation text and comments are conversation content: never log them.
 */

const val ASSISTANT_CITATION_MAX_TEXT_LENGTH = 8_000
const val ASSISTANT_CITATION_MAX_COMMENT_LENGTH = 8_000
const val ASSISTANT_CITATION_CONTEXT_LENGTH = 32
/** Provider message IDs nest and re-encode their thread and turn IDs, so they outgrow a few hundred characters. */
const val ASSISTANT_CITATION_MAX_ID_LENGTH = 4_096
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

private const val CITATION_PROTOCOL = "t3-citation:"
private const val CITATION_HREF_PREFIX = "$CITATION_PROTOCOL//v1/"
private const val CITATION_LINK_HEAD = "[Assistant quote]($CITATION_HREF_PREFIX"
// Percent encoding needs up to nine characters per UTF-16 code unit; 1k covers keys and offsets.
private const val MAX_CITATION_HREF_LENGTH =
    9 * (ASSISTANT_CITATION_MAX_TEXT_LENGTH + ASSISTANT_CITATION_MAX_COMMENT_LENGTH + 3 * ASSISTANT_CITATION_MAX_ID_LENGTH + 2 * ASSISTANT_CITATION_CONTEXT_LENGTH) + 1_000

/**
 * A quote of an assistant message. [start] and [end] are UTF-16 offsets into the message's rendered text after
 * whitespace normalization, not markdown offsets. [comment] is the user's own remark; an empty string is distinct from none.
 */
data class AssistantCitation(
    val environmentId: String,
    val threadId: String,
    val messageId: String,
    val text: String,
    val start: Long,
    val end: Long,
    val prefix: String,
    val suffix: String,
    val comment: String? = null,
) {
    /** Contract of upstream's `AssistantCitation` schema (ids are trimmed and non-blank before they get here). */
    val isValid: Boolean get() =
        listOf(environmentId, threadId, messageId).all { it.isNotEmpty() && it.length <= ASSISTANT_CITATION_MAX_ID_LENGTH && jsTrim(it).length == it.length } &&
            text.isNotEmpty() && text.length <= ASSISTANT_CITATION_MAX_TEXT_LENGTH && jsTrim(text).isNotEmpty() &&
            (comment == null || comment.length <= ASSISTANT_CITATION_MAX_COMMENT_LENGTH) &&
            start in 0..MAX_SAFE_INTEGER && end in 0..MAX_SAFE_INTEGER && end > start &&
            prefix.length <= ASSISTANT_CITATION_CONTEXT_LENGTH && suffix.length <= ASSISTANT_CITATION_CONTEXT_LENGTH
}

/** One citation link found in a message, with its [source] markdown and UTF-16 range `[start, end)` in the scanned text. */
data class AssistantCitationMatch(val citation: AssistantCitation, val source: String, val start: Int, val end: Int)

/** ECMAScript `\s` / `String.prototype.trim` whitespace; Java's own definitions differ (U+FEFF, U+0085, U+2007). */
internal fun isJsWhitespace(ch: Char): Boolean = when (ch) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
    else -> ch in ' '..' '
}

internal fun jsTrim(text: String): String {
    var start = 0
    var end = text.length
    while (start < end && isJsWhitespace(text[start])) start++
    while (end > start && isJsWhitespace(text[end - 1])) end--
    return text.substring(start, end)
}

private fun isHighSurrogate(code: Int) = code in 0xD800..0xDBFF
private fun isLowSurrogate(code: Int) = code in 0xDC00..0xDFFF

/** JS strings may hold lone surrogates; a URL cannot, so they become U+FFFD as in `USVString` conversion. */
private fun String.toWellFormed(): String {
    var result: StringBuilder? = null
    for (i in indices) {
        val ch = this[i]
        val lone = (ch.isHighSurrogate() && !(i + 1 < length && this[i + 1].isLowSurrogate())) ||
            (ch.isLowSurrogate() && !(i > 0 && this[i - 1].isHighSurrogate()))
        if (lone && result == null) result = StringBuilder(length).append(this, 0, i)
        result?.append(if (lone) '�' else ch)
    }
    return result?.toString() ?: this
}

private const val HEX = "0123456789ABCDEF"

private fun StringBuilder.appendPercent(byte: Int) { append('%').append(HEX[byte shr 4 and 0xF]).append(HEX[byte and 0xF]) }

/** `encodeURIComponent` plus the `!'()*` it leaves alone: only ASCII letters, digits and `-_.~` stay literal. */
private fun encodePathPart(value: String): String {
    val out = StringBuilder()
    for (byte in value.toWellFormed().toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        val ch = b.toChar()
        if (b < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~')) out.append(ch) else out.appendPercent(b)
    }
    return out.toString()
}

/** One `URLSearchParams` component: `application/x-www-form-urlencoded`, so a space is `+` and only `*-._` stay literal. */
private fun encodeQueryPart(value: String): String {
    val out = StringBuilder()
    for (byte in value.toWellFormed().toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        val ch = b.toChar()
        when {
            b < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '*') -> out.append(ch)
            b == 0x20 -> out.append('+')
            else -> out.appendPercent(b)
        }
    }
    return out.toString()
}

/** The citation with [comment] bound: only its outer whitespace is trimmed, and a blank comment removes the comment. */
fun AssistantCitation.withComment(comment: String): AssistantCitation {
    val trimmed = jsTrim(comment)
    return copy(comment = trimmed.ifEmpty { null })
}

fun formatAssistantCitationHref(citation: AssistantCitation): String {
    val path = listOf(citation.environmentId, citation.threadId, citation.messageId).joinToString("/", transform = ::encodePathPart)
    val query = StringBuilder()
        .append("text=").append(encodeQueryPart(citation.text))
        .append("&start=").append(citation.start)
        .append("&end=").append(citation.end)
        .append("&prefix=").append(encodeQueryPart(citation.prefix))
        .append("&suffix=").append(encodeQueryPart(citation.suffix))
    if (citation.comment != null) query.append("&comment=").append(encodeQueryPart(citation.comment))
    return "$CITATION_HREF_PREFIX$path?$query"
}

fun serializeAssistantCitation(citation: AssistantCitation): String = "[Assistant quote](${formatAssistantCitationHref(citation)})"

/** Bytes of [text]: raw characters as UTF-8, `%XX` as one byte; null when [strict] and a `%` is not followed by two hex digits. */
private fun percentBytes(text: String, plusIsSpace: Boolean, strict: Boolean): ByteArray? {
    val out = java.io.ByteArrayOutputStream(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '%') {
            val hi = hexValue(text.getOrNull(i + 1) ?: '\u0000')
            val lo = hexValue(text.getOrNull(i + 2) ?: '\u0000')
            if (hi >= 0 && lo >= 0) { out.write(hi * 16 + lo); i += 3; continue }
            if (strict) return null
            out.write('%'.code); i++
        } else if (plusIsSpace && ch == '+') { out.write(' '.code); i++ }
        else {
            val end = if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) i + 2 else i + 1
            out.write(text.substring(i, end).toWellFormed().toByteArray(Charsets.UTF_8)); i = end
        }
    }
    return out.toByteArray()
}

private fun hexValue(ch: Char): Int = when (ch) { in '0'..'9' -> ch - '0'; in 'a'..'f' -> ch - 'a' + 10; in 'A'..'F' -> ch - 'A' + 10; else -> -1 }

/** `decodeURIComponent`: null where it would throw (a malformed escape or invalid UTF-8). */
private fun decodeUriComponent(text: String): String? {
    val bytes = percentBytes(text, plusIsSpace = false, strict = true) ?: return null
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }
}

/** `URLSearchParams` parsing of one value or name: `+` is a space and invalid UTF-8 becomes U+FFFD. */
private fun decodeFormComponent(text: String): String = String(percentBytes(text, plusIsSpace = true, strict = false)!!, Charsets.UTF_8)

private fun isDotSegment(segment: String) = segment == "." || segment.equals("%2e", ignoreCase = true)
private fun isDoubleDotSegment(segment: String) = when (segment.lowercase()) { "..", ".%2e", "%2e.", "%2e%2e" -> true; else -> false }

/** WHATWG URL path parsing of a non-special URL: `.` and `..` segments are resolved before the path is split. */
private fun resolvedPathParts(path: String): List<String> {
    val parts = path.split('/')
    val out = ArrayList<String>()
    for ((index, segment) in parts.withIndex()) {
        val last = index == parts.lastIndex
        when {
            isDoubleDotSegment(segment) -> { if (out.isNotEmpty()) out.removeAt(out.lastIndex); if (last) out += "" }
            isDotSegment(segment) -> if (last) out += ""
            else -> out += segment
        }
    }
    return out
}

private fun parseNonNegative(digits: String): Long? =
    if (digits.length in 1..16 && digits.all { it in '0'..'9' }) digits.toLong().takeIf { it <= MAX_SAFE_INTEGER } else null

/**
 * Strict parser of a v1 citation link: host `v1`, three path parts, exactly the five required query keys plus an optional
 * `comment`, each once, and no fragment. Anything else, or a value that breaks the contract limits, is null.
 * Like upstream it reads the href as a URL, so a raw space or control character is not supported here; a message scan never produces one.
 */
fun parseAssistantCitationHref(href: String): AssistantCitation? {
    if (!href.startsWith(CITATION_HREF_PREFIX) || href.length > MAX_CITATION_HREF_LENGTH) return null
    val fragmentAt = href.indexOf('#')
    if (fragmentAt >= 0 && fragmentAt < href.length - 1) return null
    val beforeFragment = if (fragmentAt >= 0) href.substring(0, fragmentAt) else href
    val queryAt = beforeFragment.indexOf('?')
    val rawPath = (if (queryAt >= 0) beforeFragment.substring(CITATION_HREF_PREFIX.length, queryAt) else beforeFragment.substring(CITATION_HREF_PREFIX.length))
    val rawQuery = if (queryAt >= 0) beforeFragment.substring(queryAt + 1) else ""
    val parts = resolvedPathParts(rawPath)
    if (parts.size != 3) return null
    val pairs = rawQuery.split('&').filter { it.isNotEmpty() }.map { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) decodeFormComponent(pair) to "" else decodeFormComponent(pair.substring(0, eq)) to decodeFormComponent(pair.substring(eq + 1))
    }
    fun all(key: String) = pairs.filter { it.first == key }
    val comment = all("comment").firstOrNull()?.second
    val required = listOf("text", "start", "end", "prefix", "suffix")
    if (pairs.size != required.size + (if (comment == null) 0 else 1) || required.any { all(it).size != 1 }) return null
    val start = parseNonNegative(all("start").single().second) ?: return null
    val end = parseNonNegative(all("end").single().second) ?: return null
    val ids = parts.map { decodeUriComponent(it)?.let(::jsTrim) ?: return null }
    return AssistantCitation(
        environmentId = ids[0], threadId = ids[1], messageId = ids[2],
        text = all("text").single().second, start = start, end = end,
        prefix = all("prefix").single().second, suffix = all("suffix").single().second, comment = comment,
    ).takeIf { it.isValid }
}

/** The citation links in [text], in order. A malformed or unsupported link is left out, so it stays ordinary text. */
fun collectAssistantCitations(text: String): List<AssistantCitationMatch> {
    val matches = ArrayList<AssistantCitationMatch>()
    val maxHref = MAX_CITATION_HREF_LENGTH - CITATION_HREF_PREFIX.length
    var from = 0
    while (true) {
        val start = text.indexOf(CITATION_LINK_HEAD, from)
        if (start < 0) break
        val hrefStart = start + "[Assistant quote](".length
        var i = hrefStart + CITATION_HREF_PREFIX.length
        while (i < text.length && text[i] != ')' && !isJsWhitespace(text[i])) i++
        val bodyLength = i - (hrefStart + CITATION_HREF_PREFIX.length)
        if (i < text.length && text[i] == ')' && bodyLength in 1..maxHref) {
            val citation = parseAssistantCitationHref(text.substring(hrefStart, i))
            if (citation != null) {
                matches += AssistantCitationMatch(citation, text.substring(start, i + 1), start, i + 1)
                from = i + 1
                continue
            }
        }
        from = start + 1
    }
    return matches
}

/** What a citation shows as a chip: its comment (else the quote) on one line, at most 64 UTF-16 units without splitting a pair. */
fun assistantCitationLabel(citation: AssistantCitation): String {
    val source = jsTrim(citation.comment.orEmpty()).ifEmpty { citation.text }
    val preview = collapseWhitespace(source)
    if (preview.length <= 64) return preview
    var end = 64
    if (preview[end - 1].isHighSurrogate() && preview[end].isLowSurrogate()) end -= 1
    return preview.substring(0, end) + "…"
}

/** The message with each citation link replaced by its quote, and `Comment:` on the next line when it has one. */
fun assistantCitationsToPlainText(prompt: String): String {
    val matches = collectAssistantCitations(prompt)
    if (matches.isEmpty()) return prompt
    val out = StringBuilder()
    var cursor = 0
    for (match in matches) {
        out.append(prompt, cursor, match.start).append(match.citation.text)
        match.citation.comment?.let { out.append("\nComment: ").append(it) }
        cursor = match.end
    }
    return out.append(prompt, cursor, prompt.length).toString()
}

/** A quote located in an assistant message's rendered text: [prefix] and [suffix] are the (up to 32) normalized characters around it. */
data class AssistantTextSelector(val text: String, val start: Int, val end: Int, val prefix: String, val suffix: String)

/** Every run of whitespace becomes one space. */
fun collapseWhitespace(text: String): String {
    val out = StringBuilder(text.length)
    var inRun = false
    for (ch in text) {
        if (isJsWhitespace(ch)) { if (!inRun) out.append(' '); inRun = true } else { out.append(ch); inRun = false }
    }
    return out.toString()
}

fun splitsSurrogatePair(text: String, offset: Int): Boolean =
    offset in 1 until text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()

/**
 * The selector for `[rawStart, rawEnd)` of the rendered [text] (not normalized): [AssistantTextSelector.text] is the raw quote,
 * the offsets and context are over the whitespace-normalized text. Null when the selection is blank.
 */
fun createAssistantTextSelector(text: String, rawStart: Int, rawEnd: Int): AssistantTextSelector? {
    val from = rawStart.coerceIn(0, text.length)
    val to = rawEnd.coerceIn(0, text.length)
    val quote = if (from < to) text.substring(from, to) else ""
    if (jsTrim(quote).isEmpty()) return null

    val normalized = collapseWhitespace(text)
    var start = collapseWhitespace(text.substring(0, from)).length
    if (from > 0 && isJsWhitespace(text[from - 1]) && from < text.length && isJsWhitespace(text[from])) start -= 1
    val end = collapseWhitespace(text.substring(0, to)).length
    var prefixStart = maxOf(0, start - ASSISTANT_CITATION_CONTEXT_LENGTH)
    var suffixEnd = minOf(normalized.length, end + ASSISTANT_CITATION_CONTEXT_LENGTH)
    // A split pair becomes a replacement character when the context enters a URL.
    if (splitsSurrogatePair(normalized, prefixStart)) prefixStart += 1
    if (splitsSurrogatePair(normalized, suffixEnd)) suffixEnd -= 1
    return AssistantTextSelector(quote, start, end, normalized.substring(prefixStart, start), normalized.substring(end, suffixEnd))
}

/**
 * Where [selector] sits in [text], as a range over the normalized UTF-16 text (its end is `last + 1`): its saved offsets when they
 * still fit, else the one place its context matches. Null when the quote is missing or its place is ambiguous.
 */
fun findAssistantCitationText(text: String, selector: AssistantTextSelector): IntRange? {
    val normalized = collapseWhitespace(text)
    val quote = collapseWhitespace(selector.text)
    if (jsTrim(quote).isEmpty()) return null
    val prefix = collapseWhitespace(selector.prefix)
    val suffix = collapseWhitespace(selector.suffix)
    fun matchesContext(start: Int, end: Int) =
        normalized.substring(maxOf(0, start - prefix.length), start) == prefix &&
            normalized.substring(end, minOf(normalized.length, end + suffix.length)) == suffix

    var match: IntRange? =
        if (selector.start >= 0 && selector.end - selector.start == quote.length && selector.end <= normalized.length &&
            normalized.substring(selector.start, selector.end) == quote && matchesContext(selector.start, selector.end)) selector.start until selector.end else null
    var onlyQuote: IntRange? = null
    var quoteCount = 0
    var start = normalized.indexOf(quote)
    while (start != -1) {
        val end = start + quote.length
        quoteCount += 1
        onlyQuote = start until end
        if (matchesContext(start, end)) {
            if (match != null && match.first != start) return null
            match = start until end
        }
        start = normalized.indexOf(quote, start + 1)
    }
    return match ?: onlyQuote.takeIf { quoteCount == 1 }
}
