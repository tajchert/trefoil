package com.tajchert.trefoil.ui.diff

import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffRow

/** What a visible list item renders as. Also the LazyColumn `contentType`, so slots are only reused between like items. */
internal enum class DiffItemKind { FileHeader, Hunk, Line, Notice, Collapsed }

/**
 * The rows the viewer lists once collapsed files are folded: every row of an expanded file, only the header plus one
 * "Show N lines" placeholder for a collapsed one. Rows are kept as indices into [DiffDocument.rows] (a negative code
 * `-(fileIndex + 1)` is a collapsed placeholder), so a 60k-row document costs one `IntArray`, not 60k wrappers.
 */
internal class VisibleDiffRows(private val document: DiffDocument, private val codes: IntArray) {
    val size: Int get() = codes.size

    /** The document row at [index], or null for a collapsed placeholder. */
    fun row(index: Int): DiffRow? = codes[index].let { if (it >= 0) document.rows[it] else null }

    /** [DiffFile.index] of the placeholder at [index], or -1 when it is a real row. */
    fun collapsedFileIndex(index: Int): Int = codes[index].let { if (it < 0) -(it + 1) else -1 }

    fun isFileHeader(index: Int): Boolean = row(index) is DiffRow.FileHeader

    /** Stable and unique within one list; saveable (Long or String). */
    fun key(index: Int): Any = row(index)?.id ?: "collapsed-${collapsedFileIndex(index)}"

    fun kind(index: Int): DiffItemKind = when (row(index)) {
        is DiffRow.FileHeader -> DiffItemKind.FileHeader
        is DiffRow.Hunk -> DiffItemKind.Hunk
        is DiffRow.Line -> DiffItemKind.Line
        is DiffRow.Notice -> DiffItemKind.Notice
        null -> DiffItemKind.Collapsed
    }

    /** Position of the document row [rowIndex] in this list, or -1 when it is folded away. */
    fun indexOfRow(rowIndex: Int): Int = codes.indexOf(rowIndex)
}

internal fun DiffDocument.fileByIndex(fileIndex: Int): DiffFile? =
    files.getOrNull(fileIndex)?.takeIf { it.index == fileIndex } ?: files.firstOrNull { it.index == fileIndex }

/** A file can be folded only when it has rows besides its header. */
internal fun DiffFile.isCollapsible(): Boolean = endRowIndex - firstRowIndex > 1

/** Builds the visible list in one pass over the document. [isCollapsed] is asked once per collapsible file. */
internal fun buildVisibleDiffRows(document: DiffDocument, isCollapsed: (fileIndex: Int) -> Boolean): VisibleDiffRows {
    val rows = document.rows
    // A placeholder replaces at least one body row, so the result never outgrows the document.
    val out = IntArray(rows.size)
    var n = 0
    var cursor = 0
    for (file in document.files) {
        val first = file.firstRowIndex.coerceIn(cursor, rows.size)
        while (cursor < first) out[n++] = cursor++
        val end = file.endRowIndex.coerceIn(first, rows.size)
        if (first < end) {
            out[n++] = first
            if (end - first > 1 && isCollapsed(file.index)) {
                out[n++] = -(file.index + 1)
            } else {
                for (r in first + 1 until end) out[n++] = r
            }
        }
        cursor = end
    }
    while (cursor < rows.size) out[n++] = cursor++
    return VisibleDiffRows(document, out.copyOf(n))
}

/** The one list item above the rows: shown when the diff was cut by the server or by the row cap. */
internal fun diffBannerCount(document: DiffDocument): Int = if (document.truncated || document.rowCapHit) 1 else 0

/**
 * Index of file [fileIndex]'s header in the viewer's LazyColumn (banner included), or null when the file is unknown.
 * [isCollapsed] defaults to each file's `collapsedByDefault`.
 */
internal fun diffItemIndexOfFile(
    document: DiffDocument,
    fileIndex: Int,
    isCollapsed: ((Int) -> Boolean)? = null,
): Int? {
    val file = document.fileByIndex(fileIndex) ?: return null
    val byDefault = document.files.associate { it.index to it.collapsedByDefault }
    val visible = buildVisibleDiffRows(document, isCollapsed ?: { byDefault[it] ?: false })
    val at = visible.indexOfRow(file.firstRowIndex)
    return if (at < 0) null else at + diffBannerCount(document)
}

/** Sizes the viewer needs for its gutters and its horizontal scroll range; one pass over the lines. */
internal class DiffMetrics(val numberDigits: Int, val maxChars: Int) {
    companion object {
        fun of(document: DiffDocument): DiffMetrics {
            var maxNumber = 0
            var maxChars = 0
            for (row in document.rows) {
                if (row !is DiffRow.Line) continue
                val old = row.oldNumber ?: 0
                val new = row.newNumber ?: 0
                if (old > maxNumber) maxNumber = old
                if (new > maxNumber) maxNumber = new
                if (row.text.length > maxChars) maxChars = row.text.length
            }
            return DiffMetrics(numberDigits = maxOf(2, maxNumber.toString().length), maxChars = maxChars)
        }
    }
}
