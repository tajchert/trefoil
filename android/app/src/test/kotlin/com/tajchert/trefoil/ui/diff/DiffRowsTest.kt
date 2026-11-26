package com.tajchert.trefoil.ui.diff

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.tajchert.trefoil.core.model.DIFF_MAX_ROWS
import com.tajchert.trefoil.core.model.DiffChange
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffLineKind
import com.tajchert.trefoil.core.model.DiffRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRowsTest {
    /** [files] files of [linesPerFile] lines each, with one hunk row per file; ids are unique and increasing. */
    private fun synthetic(files: Int, linesPerFile: Int, collapsedFiles: Set<Int> = emptySet(), leading: Int = 0, trailing: Int = 0): DiffDocument {
        val rows = ArrayList<DiffRow>(leading + trailing + files * (linesPerFile + 2))
        val list = ArrayList<DiffFile>(files)
        var id = 0L
        repeat(leading) { rows += DiffRow.Notice(id++, -1, "leading") }
        for (f in 0 until files) {
            val first = rows.size
            rows += DiffRow.FileHeader(id++, f)
            rows += DiffRow.Hunk(id++, f, "@@ -1 +1 @@")
            for (n in 1..linesPerFile) rows += DiffRow.Line(id++, f, DiffLineKind.Add, null, n, "line $n of file $f")
            list += DiffFile(
                index = f, path = "f$f.kt", change = DiffChange.Modified, additions = linesPerFile, deletions = 0,
                collapsedByDefault = f in collapsedFiles, firstRowIndex = first, endRowIndex = rows.size, lineCount = linesPerFile,
            )
        }
        repeat(trailing) { rows += DiffRow.Notice(id++, -1, "trailing") }
        return DiffDocument(list, rows)
    }

    private fun VisibleDiffRows.kinds() = (0 until size).map { kind(it) }

    @Test fun expandedFilesListEveryRow() {
        val doc = synthetic(files = 3, linesPerFile = 4)
        val visible = buildVisibleDiffRows(doc) { false }
        assertEquals(doc.rows.size, visible.size)
        assertEquals(DiffItemKind.FileHeader, visible.kind(0))
        assertEquals(DiffItemKind.Hunk, visible.kind(1))
        assertEquals(DiffItemKind.Line, visible.kind(2))
    }

    @Test fun collapsedFileKeepsHeaderAndOnePlaceholder() {
        val doc = synthetic(files = 3, linesPerFile = 10)
        val visible = buildVisibleDiffRows(doc) { it == 1 }
        // file 0 whole (12), file 1 header + placeholder (2), file 2 whole (12)
        assertEquals(26, visible.size)
        assertEquals(DiffItemKind.FileHeader, visible.kind(12))
        assertEquals(DiffItemKind.Collapsed, visible.kind(13))
        assertEquals(1, visible.collapsedFileIndex(13))
        assertNull(visible.row(13))
        assertEquals(-1, visible.collapsedFileIndex(12))
        assertEquals(DiffItemKind.FileHeader, visible.kind(14))
    }

    @Test fun keysAreUniqueAndKeepRowIds() {
        val doc = synthetic(files = 4, linesPerFile = 5)
        val visible = buildVisibleDiffRows(doc) { it % 2 == 0 }
        val keys = (0 until visible.size).map { visible.key(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains("collapsed-0"))
        val hunkOfOpenFile = doc.files[1].firstRowIndex + 1
        assertEquals(doc.rows[hunkOfOpenFile].id, visible.key(visible.indexOfRow(hunkOfOpenFile)))
    }

    @Test fun fileWithoutBodyRowsIsNeverCollapsed() {
        val rows = listOf<DiffRow>(DiffRow.FileHeader(0, 0))
        val file = DiffFile(index = 0, path = "bin.png", change = DiffChange.Binary, additions = 0, deletions = 0, firstRowIndex = 0, endRowIndex = 1)
        val visible = buildVisibleDiffRows(DiffDocument(listOf(file), rows)) { true }
        assertEquals(listOf(DiffItemKind.FileHeader), visible.kinds())
    }

    @Test fun rowsOutsideFilesAreKept() {
        val doc = synthetic(files = 2, linesPerFile = 3, leading = 2, trailing = 1)
        val visible = buildVisibleDiffRows(doc) { true }
        // 2 leading notices, (header + placeholder) x2, 1 trailing notice
        assertEquals(
            listOf(
                DiffItemKind.Notice, DiffItemKind.Notice, DiffItemKind.FileHeader, DiffItemKind.Collapsed,
                DiffItemKind.FileHeader, DiffItemKind.Collapsed, DiffItemKind.Notice,
            ),
            visible.kinds(),
        )
    }

    @Test fun emptyDocumentHasNoRows() {
        assertEquals(0, buildVisibleDiffRows(DiffDocument.Empty) { true }.size)
    }

    @Test fun fileHeaderIndexAccountsForFoldedFilesAndBanner() {
        val doc = synthetic(files = 3, linesPerFile = 10, collapsedFiles = setOf(0))
        // By default file 0 is folded: file 1 starts after header + placeholder.
        assertEquals(0, diffItemIndexOfFile(doc, 0))
        assertEquals(2, diffItemIndexOfFile(doc, 1))
        assertEquals(14, diffItemIndexOfFile(doc, 2))
        // The user expanded file 0.
        assertEquals(12, diffItemIndexOfFile(doc, 1) { false })
        // A banner is one item above everything.
        val banner = doc.copy(truncated = true)
        assertEquals(1, diffItemIndexOfFile(banner, 0))
        assertEquals(3, diffItemIndexOfFile(banner, 1))
        assertNull(diffItemIndexOfFile(doc, 9))
    }

    @Test fun metricsFollowTheLongestLineAndLargestNumber() {
        val doc = synthetic(files = 1, linesPerFile = 1234)
        val metrics = DiffMetrics.of(doc)
        assertEquals(4, metrics.numberDigits)
        assertEquals("line 1234 of file 0".length, metrics.maxChars)
        assertEquals(2, DiffMetrics.of(DiffDocument.Empty).numberDigits)
    }

    @Test fun fiftyThousandRowsBuildQuicklyCollapsedAndExpanded() {
        // 25 files x 2000 lines: 50_050 rows, well inside DIFF_MAX_ROWS.
        val doc = synthetic(files = 25, linesPerFile = 2000, collapsedFiles = (0 until 25 step 2).toSet())
        assertTrue(doc.rows.size in 50_000..DIFF_MAX_ROWS)
        val start = System.nanoTime()
        var total = 0
        repeat(20) {
            total += buildVisibleDiffRows(doc) { it % 2 == 0 }.size
            total += buildVisibleDiffRows(doc) { false }.size
            total += DiffMetrics.of(doc).maxChars
            total += diffItemIndexOfFile(doc, 24) ?: 0
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("total=$total", total > 0)
        // 80 passes over 50k rows; a generous bound, it takes a fraction of this.
        assertTrue("took ${millis}ms", millis < 5_000)
        val collapsed = buildVisibleDiffRows(doc) { it % 2 == 0 }
        assertEquals(13 * 2 + 12 * 2002, collapsed.size)
    }

    private val highlight = Color(0xFF00FF00)

    @Test fun plainLineHasNoSpans() {
        val text = buildDiffLineText("val x = 1", null, highlight)
        assertEquals("val x = 1", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test fun wordRangesBecomeBackgroundSpans() {
        val text = buildDiffLineText("val x = 12", intArrayOf(8, 10), highlight)
        assertEquals(listOf(SpanStyle(background = highlight)), text.spanStyles.map { it.item })
        assertEquals(8, text.spanStyles[0].start)
        assertEquals(10, text.spanStyles[0].end)
    }

    @Test fun rangesOutOfBoundsAreClampedAndEmptyOnesDropped() {
        val text = buildDiffLineText("abc", intArrayOf(1, 99, 2, 2, 5, 3), highlight)
        assertEquals(1, text.spanStyles.size)
        assertEquals(1, text.spanStyles[0].start)
        assertEquals(3, text.spanStyles[0].end)
    }

    @Test fun tabsBecomeSpacesAndRangesFollow() {
        val text = buildDiffLineText("\tfoo bar", intArrayOf(5, 8), highlight)
        assertEquals("    foo bar", text.text)
        assertEquals(8, text.spanStyles[0].start)
        assertEquals(11, text.spanStyles[0].end)
    }

    @Test fun veryLongLinesAreCutWithAnEllipsis() {
        val long = "x".repeat(DIFF_MAX_RENDER_CHARS * 3)
        val text = buildDiffLineText(long, intArrayOf(10, DIFF_MAX_RENDER_CHARS + 500), highlight)
        assertEquals(DIFF_MAX_RENDER_CHARS + 1, text.length)
        assertTrue(text.text.endsWith("…"))
        assertNotNull(text.spanStyles.singleOrNull())
        assertEquals(DIFF_MAX_RENDER_CHARS, text.spanStyles[0].end)
    }
}
