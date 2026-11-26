package com.tajchert.trefoil.ui

/**
 * The text an assistant message shows once rendered: block by block, markup gone, links and code as their text. Citation offsets
 * count over this text (whitespace-normalized), as T3 Code counts over its rendered message. Content: never log it.
 */
internal fun renderedAssistantText(markdown: String): String = parseMarkdown(markdown).map { node ->
    when (node) {
        is MdNode.Code -> node.text
        is MdNode.Heading -> inlinePlainText(node.text)
        is MdNode.Paragraph -> inlinePlainText(node.text)
        is MdNode.ListBlock -> node.items.joinToString("\n") { inlinePlainText(it.text) }
        is MdNode.Quote -> inlinePlainText(node.text)
        MdNode.Rule -> ""
        is MdNode.Table -> (listOf(node.header) + node.rows).joinToString("\n") { row -> row.joinToString("\t") { inlinePlainText(it) } }
    }
}.filter { it.isNotBlank() }.joinToString("\n\n")

/**
 * Index of the LazyColumn item showing message [messageId], or null when it is not a visible top-level row (hidden by the turn
 * window, or folded into completed work). [shown] is the visible entries, [leading] the items before them (the "Load earlier"
 * row) and [headerAt] where the live "Working for" header is inserted among them, if it is.
 */
internal fun citationScrollIndex(shown: List<ConversationEntry>, messageId: String, leading: Int, headerAt: Int?): Int? {
    val position = shown.indexOfFirst { it is ConversationEntry.Message && it.message.id == messageId }
    if (position < 0) return null
    return leading + position + if (headerAt != null && position >= headerAt) 1 else 0
}
