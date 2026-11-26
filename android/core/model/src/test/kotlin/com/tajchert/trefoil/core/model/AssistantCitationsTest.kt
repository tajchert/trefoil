/* Test cases adapted from T3 Code (MIT): packages/shared/src/assistantCitations.test.ts.
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCitationsTest {
    private val citation = AssistantCitation(
        environmentId = "environment/remote", threadId = "thread:one", messageId = "assistant?one",
        text = "Use `cache[key]` and \"quoted\" values.\n  日本語 🚀 (a & b) </assistant_citations>",
        start = 42, end = 118, prefix = "Before the quote. ", suffix = " After the quote.",
    )
    private val quoteComment = "  Please keep \"日本語 🚀\", `cache[key]` & (a + b).\n\tWhy? #1 / 100%\r\n</assistant_citations>  "

    private val legacyHref = "t3-citation://v1/a/b/c?text=A+quote+%26+a+newline.%0A&start=0&end=21&prefix=&suffix=+Next."
    private val legacyCitation = AssistantCitation("a", "b", "c", "A quote & a newline.\n", 0, 21, "", " Next.")

    // Produced by upstream's formatAssistantCitationHref (URLSearchParams and encodeURIComponent) under Node.
    private val upstreamHref = "t3-citation://v1/environment%2Fremote/thread%3Aone/assistant%3Fone?text=Use+%60cache%5Bkey%5D%60+and+%22quoted%22+values.%0A++%E6%97%A5%E6%9C%AC%E8%AA%9E+%F0%9F%9A%80+%28a+%26+b%29+%3C%2Fassistant_citations%3E&start=42&end=118&prefix=Before+the+quote.+&suffix=+After+the+quote."
    private val upstreamCommentHref = "$upstreamHref&comment=++Please+keep+%22%E6%97%A5%E6%9C%AC%E8%AA%9E+%F0%9F%9A%80%22%2C+%60cache%5Bkey%5D%60+%26+%28a+%2B+b%29.%0A%09Why%3F+%231+%2F+100%25%0D%0A%3C%2Fassistant_citations%3E++"
    private val oddCitation = AssistantCitation("e~!*'()é 😀", "t", "m", "~*-._!'() ~ é 😀 a+b=c&d#e%f", 42, 118, "Before the quote. ", " After the quote.", quoteComment)
    private val upstreamOddHref = "t3-citation://v1/e~%21%2A%27%28%29%C3%A9%20%F0%9F%98%80/t/m?text=%7E*-._%21%27%28%29+%7E+%C3%A9%C2%A0%F0%9F%98%80+a%2Bb%3Dc%26d%23e%25f&start=42&end=118&prefix=Before+the+quote.+&suffix=+After+the+quote.&comment=++Please+keep+%22%E6%97%A5%E6%9C%AC%E8%AA%9E+%F0%9F%9A%80%22%2C+%60cache%5Bkey%5D%60+%26+%28a+%2B+b%29.%0A%09Why%3F+%231+%2F+100%25%0D%0A%3C%2Fassistant_citations%3E++"

    @Test fun preservesLegacyV1LinkBytesWithoutAComment() {
        assertEquals(legacyCitation, parseAssistantCitationHref(legacyHref))
        assertEquals(legacyHref, formatAssistantCitationHref(legacyCitation))
        assertEquals("[Assistant quote]($legacyHref)", serializeAssistantCitation(legacyCitation))
    }

    @Test fun formatsByteForByteLikeUpstream() {
        assertEquals(upstreamHref, formatAssistantCitationHref(citation))
        assertEquals(upstreamCommentHref, formatAssistantCitationHref(citation.copy(comment = quoteComment)))
        // `~`, `!'()*` in a path part, `~` and `!'()` in a query value, a non-breaking space and an astral character.
        assertEquals(upstreamOddHref, formatAssistantCitationHref(oddCitation))
        assertEquals(oddCitation, parseAssistantCitationHref(upstreamOddHref))
    }

    @Test fun roundTripsCompleteQuoteDataWithoutAServerOrigin() {
        val href = formatAssistantCitationHref(citation)
        assertEquals(citation, parseAssistantCitationHref(href))
        assertTrue(href.startsWith("t3-citation://v1/environment%2Fremote/thread%3Aone/assistant%3Fone?"))
        val marker = serializeAssistantCitation(citation)
        val prompt = "About $marker, explain this."
        assertEquals(listOf(AssistantCitationMatch(citation, marker, 6, 6 + marker.length)), collectAssistantCitations(prompt))
    }

    @Test fun roundTripsAnExplicitlySuppliedCommentWithoutChangingIt() {
        for (comment in listOf(quoteComment, "")) {
            val commented = citation.copy(comment = comment)
            val href = formatAssistantCitationHref(commented)
            val marker = serializeAssistantCitation(commented)
            assertTrue(href.contains("&comment="))
            assertEquals(commented, parseAssistantCitationHref(href))
            assertEquals(listOf(AssistantCitationMatch(commented, marker, 0, marker.length)), collectAssistantCitations(marker))
        }
        // An empty comment is not no comment.
        assertEquals("", parseAssistantCitationHref(formatAssistantCitationHref(citation.copy(comment = "")))!!.comment)
    }

    @Test fun leavesInvalidOrUnsupportedReferencesUnchanged() {
        for (href in listOf(
            "t3-citation://v2/a/b/c?text=quote&start=0&end=5&prefix=&suffix=",
            "t3-citation://v1/a/b/c?text=quote&start=5&end=0&prefix=&suffix=",
            "t3-citation://v1/a/b/c?text=quote&start=0&end=5&prefix=&suffix=&text=other",
            "t3-citation://v1/a/b/c?text=quote&start=0&end=5&prefix=&suffix=&unknown=value",
            "t3-citation://v1/a/b/c?text=quote&start=0&end=5&prefix=&suffix=&comment=one&comment=two",
            "t3-citation://v1/a/b?text=quote&start=0&end=5&prefix=&suffix=",
            "t3-citation://v1/a/b/c/d?text=quote&start=0&end=5&prefix=&suffix=",
            "t3-citation://v1/a/b/c?text=quote&start=0&end=5&prefix=&suffix=#top",
            "t3-citation://v1/a/b/c?text=quote&start=x&end=5&prefix=&suffix=",
            "t3-citation://v1/a/b/c?text=quote&start=0&end=9007199254740993&prefix=&suffix=",
            "t3-citation://v1/a/%E0%A4%A/c?text=quote&start=0&end=5&prefix=&suffix=",
            "t3-citation://v1/a/%FF/c?text=quote&start=0&end=5&prefix=&suffix=",
            "t3-citation://v1/a/b/%20?text=quote&start=0&end=5&prefix=&suffix=",
            "t3-citation://v1/a/b/c?text=quote&start=0&end=5&prefix=&suffix=&text=",
        )) {
            assertNull(href, parseAssistantCitationHref(href))
            val prompt = "[Assistant quote]($href)"
            assertEquals(href, emptyList<AssistantCitationMatch>(), collectAssistantCitations(prompt))
            assertEquals(prompt, assistantCitationsToPlainText(prompt))
        }
    }

    @Test fun readsDotSegmentsLikeAUrlParser() {
        val query = "?text=quote&start=0&end=5&prefix=&suffix="
        assertEquals("c", parseAssistantCitationHref("t3-citation://v1/a/./b/c$query")!!.messageId)
        assertEquals("a", parseAssistantCitationHref("t3-citation://v1/x/../a/b/c$query")!!.environmentId)
        assertNull(parseAssistantCitationHref("t3-citation://v1/a/b/../$query"))
        assertEquals("c", parseAssistantCitationHref("t3-citation://v1/a/b/%2e%2E/c/d$query")!!.threadId)
    }

    @Test fun bareUrlsAndNoncanonicalLabelsAreOrdinaryText() {
        val href = formatAssistantCitationHref(citation)
        val prompt = "  Ordinary *text*\n$href [Other quote]($href)\t"
        assertEquals("", assistantCitationsToPlainText(""))
        assertEquals(prompt, assistantCitationsToPlainText(prompt))
        assertTrue(collectAssistantCitations(prompt).isEmpty())
    }

    @Test fun boundsSelectedTextAndSurroundingContext() {
        assertNull(parseAssistantCitationHref(formatAssistantCitationHref(citation.copy(text = "a".repeat(ASSISTANT_CITATION_MAX_TEXT_LENGTH + 1)))))
        assertNull(parseAssistantCitationHref(formatAssistantCitationHref(citation.copy(prefix = "a".repeat(33)))))
        assertNull(parseAssistantCitationHref(formatAssistantCitationHref(citation.copy(text = "  "))))
        assertNull(parseAssistantCitationHref(formatAssistantCitationHref(citation.copy(comment = "a".repeat(ASSISTANT_CITATION_MAX_COMMENT_LENGTH + 1)))))
        assertNotNull(parseAssistantCitationHref(formatAssistantCitationHref(citation.copy(text = "a".repeat(ASSISTANT_CITATION_MAX_TEXT_LENGTH)))))
    }

    @Test fun acceptsComplete8kCjkQuotesAndCommentsWithMaximumSizedSourceSelectors() {
        val large = AssistantCitation(
            environmentId = "環".repeat(ASSISTANT_CITATION_MAX_ID_LENGTH), threadId = "線".repeat(ASSISTANT_CITATION_MAX_ID_LENGTH),
            messageId = "文".repeat(ASSISTANT_CITATION_MAX_ID_LENGTH), text = "引".repeat(ASSISTANT_CITATION_MAX_TEXT_LENGTH),
            comment = "注".repeat(ASSISTANT_CITATION_MAX_COMMENT_LENGTH),
            start = 9_007_199_254_740_991L - ASSISTANT_CITATION_MAX_TEXT_LENGTH, end = 9_007_199_254_740_991L,
            prefix = "前".repeat(ASSISTANT_CITATION_CONTEXT_LENGTH), suffix = "後".repeat(ASSISTANT_CITATION_CONTEXT_LENGTH),
        )
        val href = formatAssistantCitationHref(large)
        val marker = serializeAssistantCitation(large)
        assertTrue(href.length > 100_000)
        assertEquals(large, parseAssistantCitationHref(href))
        assertEquals(listOf(AssistantCitationMatch(large, marker, 0, marker.length)), collectAssistantCitations(marker))
        assertEquals("${large.text}\nComment: ${large.comment}", assistantCitationsToPlainText(marker))
    }

    @Test fun editsOnlyTheBoundCommentAndTrimsOnlyItsOuterWhitespace() {
        val comment = " \n\tPlease keep \"日本語\".\n  Preserve indentation and `code`.\t "
        val expected = "Please keep \"日本語\".\n  Preserve indentation and `code`."
        assertEquals(citation.copy(comment = expected), citation.withComment(comment))
        assertEquals(citation.copy(comment = expected), citation.copy(comment = "Previous comment").withComment(comment))
    }

    @Test fun removesClearedCommentsAndRestoresLegacyLinkBytes() {
        for (comment in listOf("", " \n\r\t ", "﻿ ")) {
            val cleared = legacyCitation.copy(comment = "Please change this").withComment(comment)
            assertEquals(legacyCitation, cleared)
            assertNull(cleared.comment)
            assertEquals(legacyHref, formatAssistantCitationHref(cleared))
            assertEquals(legacyCitation, legacyCitation.withComment(comment))
        }
    }

    @Test fun includesBoundCommentsInPlainTextWithoutEscaping() {
        val commented = citation.copy(comment = "Why \"this\"?\nKeep <tags> & `code` \$& \$1 \$\$.")
        val marker = serializeAssistantCitation(commented)
        assertEquals(
            "Before ${citation.text}\nComment: ${commented.comment}\n${citation.text}\nComment: ${commented.comment} After",
            assistantCitationsToPlainText("Before $marker\n$marker After"),
        )
    }

    @Test fun replacesEachMarkerOnceIncludingAdjacentAndRepeatedCitations() {
        val second = citation.copy(text = "A second quote: \$& \$1 \$\$")
        val marker = serializeAssistantCitation(citation)
        assertEquals("${citation.text}${second.text}\n${citation.text}", assistantCitationsToPlainText("$marker${serializeAssistantCitation(second)}\n$marker"))
    }

    @Test fun shortensALongLabelWithoutSplittingAnEmoji() {
        assertEquals("${"a".repeat(63)}…", assistantCitationLabel(citation.copy(text = "${"a".repeat(63)}😀 and more")))
        assertEquals("short quote", assistantCitationLabel(citation.copy(text = "short\n  quote")))
        assertEquals("my comment", assistantCitationLabel(citation.copy(comment = "  my comment ")))
        assertEquals("a b", assistantCitationLabel(citation.copy(text = "a b", comment = "  ")))
    }

    @Test fun treatsJavaScriptWhitespaceAsWhitespace() {
        assertEquals("a b c", collapseWhitespace("a ﻿ b  c"))
        assertEquals("x", jsTrim("﻿ x　"))
        // The scan stops at JS whitespace such as U+00A0, which Java's own `\s` ignores.
        val marker = serializeAssistantCitation(citation)
        assertEquals(1, collectAssistantCitations(" $marker ").size)
        assertTrue(collectAssistantCitations("[Assistant quote](${formatAssistantCitationHref(citation)} )").isEmpty())
    }

    // ---- selectors (apps/mobile/src/lib/assistantCitationSelection.ts and packages/shared createAssistantTextSelector) ----

    @Test fun createsASelectorOverNormalizedOffsetsWithBoundedContext() {
        val text = "Heading\n\nFirst   paragraph with a quote inside it.\nSecond line."
        val from = text.indexOf("quote")
        val selector = createAssistantTextSelector(text, from, from + 5)!!
        val normalized = "Heading First paragraph with a quote inside it. Second line."
        assertEquals("quote", selector.text)
        assertEquals(normalized.indexOf("quote"), selector.start)
        assertEquals(normalized.indexOf("quote") + 5, selector.end)
        assertEquals(normalized.substring(maxOf(0, selector.start - 32), selector.start), selector.prefix)
        assertEquals(" inside it. Second line.", selector.suffix)
        assertTrue(selector.prefix.length <= 32 && selector.suffix.length <= 32)
    }

    @Test fun aSelectionSpanningWhitespaceKeepsItsRawTextAndNormalizedOffsets() {
        val text = "one two\n\nthree four"
        val selector = createAssistantTextSelector(text, 4, 14)!!
        assertEquals("two\n\nthree", selector.text)
        assertEquals(4, selector.start)
        assertEquals(13, selector.end)
        assertEquals(4 until 13, findAssistantCitationText(text, selector))
    }

    @Test fun aSelectionStartingInsideAWhitespaceRunStartsAtItsSingleSpace() {
        val text = "ab  cd"
        val selector = createAssistantTextSelector(text, 3, 6)!!
        assertEquals(2, selector.start)
        assertEquals(" cd".length, selector.end - selector.start)
    }

    @Test fun rejectsBlankSelections() {
        assertNull(createAssistantTextSelector("quote quote", 5, 6))
        assertNull(createAssistantTextSelector("quote quote", 3, 3))
        assertNull(createAssistantTextSelector("quote quote", 6, 3))
    }

    @Test fun contextNeverSplitsASurrogatePair() {
        val text = "😀".repeat(40) + "y" + "quote" + "y" + "😀".repeat(40)
        val from = text.indexOf("quote")
        val selector = createAssistantTextSelector(text, from, from + 5)!!
        assertEquals(31, selector.prefix.length)
        assertEquals(31, selector.suffix.length)
        assertFalse(selector.prefix.first().isLowSurrogate())
        assertFalse(selector.suffix.last().isHighSurrogate())
        assertNotNull(parseAssistantCitationHref(formatAssistantCitationHref(citation.copy(prefix = selector.prefix, suffix = selector.suffix))))
    }

    @Test fun findsTheSelectedOccurrenceByContextAndRefusesAnAmbiguousOne() {
        val text = "${"x".repeat(40)}quote${"x".repeat(40)} ${"y".repeat(40)}quote${"y".repeat(40)}"
        val second = text.lastIndexOf("quote")
        val selector = createAssistantTextSelector(text, second, second + 5)!!
        assertEquals(second until second + 5, findAssistantCitationText(text, selector))
        // Saved offsets that drifted still resolve through the context.
        assertEquals(second until second + 5, findAssistantCitationText(text, selector.copy(start = 3, end = 8)))
        // Identical context on both sides stays ambiguous rather than guessing.
        val twin = "quote quote"
        assertNull(findAssistantCitationText(twin, createAssistantTextSelector(twin, 6, 11)!!.copy(prefix = "", suffix = "")))
        // A single occurrence is found even when its context changed.
        assertEquals(4 until 9, findAssistantCitationText("the quote.", AssistantTextSelector("quote", 0, 5, "other ", " text")))
        assertNull(findAssistantCitationText("nothing here", AssistantTextSelector("quote", 0, 5, "", "")))
    }

    // ---- draft layout ----

    @Test fun composerTextHoldsMarkersThenTheBody() {
        val a = citation
        val b = legacyCitation.copy(comment = "why?")
        val text = joinCitationDraft("Please explain.", listOf(a, b))
        assertEquals("${serializeAssistantCitation(a)}\n${serializeAssistantCitation(b)}\n\nPlease explain.", text)
        assertEquals(CitationDraft("Please explain.", listOf(a, b)), splitCitationDraft(text))
        assertEquals("plain", joinCitationDraft("plain", emptyList()))
        assertEquals(serializeAssistantCitation(a), joinCitationDraft("", listOf(a)))
        assertEquals(CitationDraft("", listOf(a)), splitCitationDraft(serializeAssistantCitation(a)))
        // A body that itself starts with blank lines survives the round trip.
        assertEquals(CitationDraft("\n\nbody", listOf(a)), splitCitationDraft(joinCitationDraft("\n\nbody", listOf(a))))
    }

    @Test fun aPastedMarkerAfterLayoutMarkersKeepsTheSeparatorOutOfTheBody() {
        val a = AssistantCitation("env", "thread", "m1", "first quote", 0, 11, "", "")
        val b = AssistantCitation("env", "thread", "m2", "second quote", 0, 12, "", "")
        val text = joinCitationDraft("foo ${serializeAssistantCitation(b)} bar", listOf(a))
        assertEquals(CitationDraft("foo  bar", listOf(a, b)), splitCitationDraft(text))
    }

    @Test fun aPastedMarkerIsLiftedOutOfTheBody() {
        val a = citation
        val split = splitCitationDraft("see ${serializeAssistantCitation(a)} here")
        assertEquals(CitationDraft("see  here", listOf(a)), split)
    }

    @Test fun addsEditsAndRemovesCitationsInTheComposerText() {
        val first = addCitation("hello", citation, 100_000) as CitationAdded.Text
        assertEquals(CitationDraft("hello", listOf(citation)), splitCitationDraft(first.text))
        val again = addCitation(first.text, citation, 100_000) as CitationAdded.Text
        assertTrue(again.duplicate); assertEquals(first.text, again.text)
        val commented = citation.withComment("why")
        val edited = replaceCitation(first.text, citation, commented, 100_000)
        assertEquals(CitationDraft("hello", listOf(commented)), splitCitationDraft(edited))
        assertEquals("hello", removeCitation(edited, commented))
    }

    @Test fun refusesCitationsThatBreakTheLimits() {
        assertEquals(CitationAdded.Refused(CitationAddFailure.TooLong), addCitation("", citation.copy(text = "a".repeat(ASSISTANT_CITATION_MAX_TEXT_LENGTH + 1)), 100_000))
        assertEquals(CitationAdded.Refused(CitationAddFailure.CommentTooLong), addCitation("", citation.copy(comment = "a".repeat(ASSISTANT_CITATION_MAX_COMMENT_LENGTH + 1)), 100_000))
        assertEquals(CitationAdded.Refused(CitationAddFailure.NoRoom), addCitation("", citation, 200))
        val text = joinCitationDraft("x", listOf(citation))
        assertEquals(text, replaceCitation(text, citation, citation.withComment("a".repeat(500)), text.length + 10))
    }
}
