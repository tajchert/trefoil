package com.tajchert.trefoil.core.model

/**
 * A composer's text seen as the user's own [body] plus the [citations] quoted in it. The text itself, markers included,
 * is what the composer stores as its draft and sends, so citations persist, restore and send exactly like typed text.
 * Content: never log it.
 */
data class CitationDraft(val body: String, val citations: List<AssistantCitation>) {
    /** The composer text: the citation markers one per line, then a blank line and [body] (as sent). */
    fun text(): String = joinCitationDraft(body, citations)
}

/**
 * Splits composer text. Markers leading the text, one per line and followed by a blank line, are the composer's own layout and
 * come off with that separator, which [joinCitationDraft] restores. A marker anywhere else (pasted) is lifted out of the body.
 */
fun splitCitationDraft(text: String): CitationDraft {
    val matches = collectAssistantCitations(text)
    if (matches.isEmpty()) return CitationDraft(text, emptyList())
    val citations = matches.map { it.citation }
    val leading = matches.withIndex().all { (index, match) -> if (index == 0) match.start == 0 else match.start == matches[index - 1].end + 1 && text[match.start - 1] == '\n' }
    if (leading) {
        val rest = text.substring(matches.last().end)
        return CitationDraft(if (rest.startsWith("\n\n")) rest.substring(2) else rest, citations)
    }
    val body = StringBuilder()
    var cursor = 0
    for (match in matches) { body.append(text, cursor, match.start); cursor = match.end }
    return CitationDraft(body.append(text, cursor, text.length).toString(), citations)
}

fun joinCitationDraft(body: String, citations: List<AssistantCitation>): String {
    if (citations.isEmpty()) return body
    val markers = citations.joinToString("\n", transform = ::serializeAssistantCitation)
    return if (body.isEmpty()) markers else "$markers\n\n$body"
}

/** Why a citation could not be added to a composer. */
enum class CitationAddFailure { TooLong, CommentTooLong, NoRoom }

/** The result of adding a citation: the new composer text, or why it was refused. */
sealed interface CitationAdded {
    data class Text(val text: String, val duplicate: Boolean = false) : CitationAdded
    data class Refused(val reason: CitationAddFailure) : CitationAdded
}

/**
 * Adds [citation] to composer [text] (a quote already there is kept once). [maxLength] is the composer's text cap: a draft
 * longer than that is cut when stored, which would cut a marker.
 */
fun addCitation(text: String, citation: AssistantCitation, maxLength: Int): CitationAdded {
    if (citation.text.length > ASSISTANT_CITATION_MAX_TEXT_LENGTH) return CitationAdded.Refused(CitationAddFailure.TooLong)
    if ((citation.comment?.length ?: 0) > ASSISTANT_CITATION_MAX_COMMENT_LENGTH) return CitationAdded.Refused(CitationAddFailure.CommentTooLong)
    if (!citation.isValid) return CitationAdded.Refused(CitationAddFailure.TooLong)
    val draft = splitCitationDraft(text)
    if (citation in draft.citations) return CitationAdded.Text(text, duplicate = true)
    val next = draft.copy(citations = draft.citations + citation).text()
    return if (next.length > maxLength) CitationAdded.Refused(CitationAddFailure.NoRoom) else CitationAdded.Text(next)
}

/** Replaces [old] with [updated] (a comment edit); the text is unchanged when [old] is not there, or the result would not fit [maxLength]. */
fun replaceCitation(text: String, old: AssistantCitation, updated: AssistantCitation, maxLength: Int): String {
    val draft = splitCitationDraft(text)
    val index = draft.citations.indexOf(old)
    if (index < 0 || (updated.comment?.length ?: 0) > ASSISTANT_CITATION_MAX_COMMENT_LENGTH) return text
    val next = draft.copy(citations = draft.citations.toMutableList().also { it[index] = updated }).text()
    return if (next.length > maxLength) text else next
}

fun removeCitation(text: String, citation: AssistantCitation): String {
    val draft = splitCitationDraft(text)
    if (citation !in draft.citations) return text
    return draft.copy(citations = draft.citations - citation).text()
}
