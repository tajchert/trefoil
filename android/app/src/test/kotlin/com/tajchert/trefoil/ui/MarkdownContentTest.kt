package com.tajchert.trefoil.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownContentTest {
    private val userTable = """
        | Branch | Contents |
        | --- | --- |
        | `custom` | Upstream `main` plus one commit: the custom build flavor, `fork/` scripts and `FORK.md` |
        | `t3/custom-t3-remote-adb` | `custom` plus one commit with the Android sharing feature |
    """.trimIndent()

    @Test fun usersTableParsesIntoHeaderAndRows() {
        val table = parseMarkdown(userTable).single() as MdNode.Table
        assertEquals(listOf("Branch", "Contents"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals("`custom`", table.rows[0][0])
        assertEquals("`custom` plus one commit with the Android sharing feature", table.rows[1][1])
        assertEquals(listOf(MdAlign.Start, MdAlign.Start), table.align)
    }

    @Test fun alignmentColonsAreRead() {
        val table = parseMarkdown("|a|b|c|d|\n|:---|:---:|---:|---|\n|1|2|3|4|").single() as MdNode.Table
        assertEquals(listOf(MdAlign.Start, MdAlign.Center, MdAlign.End, MdAlign.Start), table.align)
    }

    @Test fun escapedPipesStayInsideTheCell() {
        val table = parseMarkdown("| a | b |\n|---|---|\n| x \\| y | z |").single() as MdNode.Table
        assertEquals(listOf("x | y", "z"), table.rows.single())
    }

    @Test fun raggedRowsArePaddedAndTrimmed() {
        val table = parseMarkdown("| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 | 5 |").single() as MdNode.Table
        assertEquals(listOf("1", "", ""), table.rows[0])
        assertEquals(listOf("1", "2", "3"), table.rows[1])
    }

    @Test fun tableDirectlyAfterParagraphIsSplitOut() {
        val nodes = parseMarkdown("Here is the summary:\n$userTable")
        assertEquals(MdNode.Paragraph("Here is the summary:"), nodes[0])
        assertTrue(nodes[1] is MdNode.Table)
    }

    @Test fun tableInsideCodeFenceStaysCode() {
        val nodes = parseMarkdown("```\n| a | b |\n|---|---|\n| 1 | 2 |\n```")
        assertEquals(1, nodes.size)
        assertTrue(nodes.single() is MdNode.Code)
    }

    @Test fun headerWithoutPipeOrMismatchedSeparatorIsNotATable() {
        assertTrue(parseMarkdown("Title\n---").none { it is MdNode.Table })
        assertTrue(parseMarkdown("| a | b |\n|---|").none { it is MdNode.Table })
    }

    @Test fun headingsListsQuotesAndRules() {
        val nodes = parseMarkdown("## Plan\n\n- one\n  - nested\n- [x] done\n- [ ] todo\n\n1. first\n2. second\n\n> quoted\n> more\n\n---")
        assertEquals(MdNode.Heading(2, "Plan"), nodes[0])
        val list = nodes[1] as MdNode.ListBlock
        assertEquals(listOf(0, 1, 0, 0), list.items.map { it.depth })
        assertEquals(listOf(null, null, true, false), list.items.map { it.checked })
        assertEquals(listOf("1.", "2."), (nodes[2] as MdNode.ListBlock).items.map { it.marker })
        assertEquals(MdNode.Quote("quoted\nmore"), nodes[3])
        assertEquals(MdNode.Rule, nodes[4])
    }

    @Test fun onlyHttpLinksAreTappable() {
        assertTrue(isSafeLink("https://example.com"))
        assertTrue(isSafeLink("http://example.com"))
        assertFalse(isSafeLink("javascript:alert(1)"))
        assertFalse(isSafeLink("intent://x"))
    }

    @Test fun tableColumnsKeepNaturalWidthWhenTheyFit() {
        assertArrayEquals(intArrayOf(100, 150), tableColumnWidths(intArrayOf(100, 150), intArrayOf(80, 60), 400))
    }

    @Test fun tableColumnsShrinkTowardTheirLongestWordToFit() {
        val widths = tableColumnWidths(intArrayOf(180, 600), intArrayOf(180, 90), 370)
        assertEquals(370, widths.sum())
        assertEquals(180, widths[0])
        assertTrue(widths[1] >= 90)
    }

    @Test fun tableScrollsOnlyWhenEvenTightestColumnsOverflow() {
        assertArrayEquals(intArrayOf(200, 250), tableColumnWidths(intArrayOf(280, 280), intArrayOf(200, 250), 370))
    }
}
