package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.AssistantCitation
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.collapseWhitespace
import com.tajchert.trefoil.core.model.collectAssistantCitations
import com.tajchert.trefoil.core.model.createAssistantTextSelector
import com.tajchert.trefoil.core.model.serializeAssistantCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantCitationTextTest {
    @Test fun renderedTextDropsMarkupButKeepsWhatIsShown() {
        val markdown = "# Title\n\nSome **bold** and `code` with a [link](https://example.com) and \\*escaped\\*.\n\n- one\n- two\n\n> quoted *text*\n\n---\n\n```kotlin\nval x = 1\n```"
        assertEquals("Title\n\nSome bold and code with a link and *escaped*.\n\none\ntwo\n\nquoted text\n\nval x = 1", renderedAssistantText(markdown))
    }

    @Test fun renderedTextOfATableIsItsCells() {
        val rendered = renderedAssistantText("| a | **b** |\n| - | - |\n| 1 | 2 |")
        assertEquals("a b 1 2", collapseWhitespace(rendered.replace('\t', ' ').replace('\n', ' ')))
    }

    @Test fun selectionOffsetsCountOverTheWhitespaceNormalizedRenderedText() {
        val rendered = renderedAssistantText("First paragraph.\n\nSecond **paragraph** here.")
        val from = rendered.indexOf("paragraph here".substringBefore(' '), rendered.indexOf("Second"))
        val selector = createAssistantTextSelector(rendered, from, rendered.length - 1)!!
        // "First paragraph." + one space (the blank line collapsed) + "Second " = 24 characters before "paragraph".
        assertEquals(24, selector.start)
        assertEquals("paragraph here", selector.text)
        assertEquals("First paragraph. Second ", selector.prefix)
    }

    @Test fun partsKeepTheUsersWordsAndQuotesInOrder() {
        val first = citation("one")
        val second = citation("two", comment = "why")
        val text = "${serializeAssistantCitation(first)}\n${serializeAssistantCitation(second)}\n\nplease compare"
        val parts = citationParts(text, collectAssistantCitations(text))
        assertEquals(listOf(CitationPart.Quote(first), CitationPart.Quote(second), CitationPart.Words("please compare")), parts)
    }

    @Test fun aMessageOfOnlyAQuoteHasNoWords() {
        val text = serializeAssistantCitation(citation("alone"))
        assertEquals(listOf(CitationPart.Quote(citation("alone"))), citationParts(text, collectAssistantCitations(text)))
    }

    @Test fun scrollIndexSkipsTheLoadEarlierRowAndTheWorkingHeader() {
        val shown = listOf("a", "b", "c", "d").map { ConversationEntry.Message(ConversationMessage(it, "assistant", it)) }
        assertEquals(1, citationScrollIndex(shown, "b", leading = 0, headerAt = null))
        assertEquals(2, citationScrollIndex(shown, "b", leading = 1, headerAt = null))
        // The header sits before "c" (index 2): rows from there on are one item later, rows before it are not.
        assertEquals(1, citationScrollIndex(shown, "b", leading = 0, headerAt = 2))
        assertEquals(3, citationScrollIndex(shown, "c", leading = 0, headerAt = 2))
        assertNull(citationScrollIndex(shown, "gone", leading = 0, headerAt = null))
    }

    private fun citation(text: String, comment: String? = null) =
        AssistantCitation("env", "thread", "message", text, 0, text.length.toLong(), "", "", comment)
}
