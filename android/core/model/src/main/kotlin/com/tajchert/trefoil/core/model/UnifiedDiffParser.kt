package com.tajchert.trefoil.core.model

import java.io.ByteArrayOutputStream

/**
 * Parses git unified diffs into the flat [DiffDocument] the diff viewer renders. Diff text and paths are conversation
 * content: this file never logs them.
 *
 * The parser is a single pass over the text with prefix checks (no regex per line), stops at a row cap, and computes
 * word-level ranges for paired deleted/added lines. The truncation marker handling follows T3 Code
 * (MIT, `apps/mobile/src/features/review/reviewModel.ts` @ 7deb51b550); the equal-length del/add pairing follows its
 * `buildRenderableRows`.
 */
object UnifiedDiffParser {
    /** Parses one diff. [repository] labels every file of it. */
    fun parse(diff: String, maxRows: Int = DIFF_MAX_ROWS, repository: String? = null): DiffDocument =
        parseRepositories(listOf(repository to diff), maxRows)

    /**
     * Parses one diff per repository into one document: row ids and file indexes run on across parts, each file carries
     * its part's label, and [maxRows] is shared by all parts. When more rows follow, parsing stops after [maxRows] rows,
     * a [DiffRow.Notice] is appended (so at most `maxRows + 1` rows) and [DiffDocument.rowCapHit] is set.
     */
    fun parseRepositories(parts: List<Pair<String?, String>>, maxRows: Int = DIFF_MAX_ROWS): DiffDocument {
        val parser = Parser(maxOf(maxRows, 0))
        var truncated = false
        for ((repository, text) in parts) {
            val cut = truncationStart(text)
            if (cut >= 0) truncated = true
            if (!parser.capHit) parser.parse(text, if (cut >= 0) cut else text.length, repository)
        }
        return parser.finish(truncated)
    }
}

private const val TRUNCATED_MARKER = "[truncated]"

/** Where the content ends when [text] ends in `\n\n[truncated]` (also one newline, CRLF, or a bare marker); -1 if it does not. */
private fun truncationStart(text: String): Int {
    var end = text.length
    while (end > 0 && text[end - 1].isWhitespace()) end--
    var marker = end - TRUNCATED_MARKER.length
    if (marker < 0 || !text.startsWith(TRUNCATED_MARKER, marker)) return -1
    // `(?:(?:\r?\n){1,2}|^)\[truncated\]`: one or two line breaks before the marker, or the start of the text.
    if (marker == 0) return 0
    var breaks = 0
    while (breaks < 2 && marker > 0 && text[marker - 1] == '\n') {
        marker--
        if (marker > 0 && text[marker - 1] == '\r') marker--
        breaks++
    }
    return if (breaks == 0) -1 else marker
}

private class IntList {
    var data = IntArray(16)
    var size = 0
    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }
    fun clear() { size = 0 }
}

private class FileBuilder(val index: Int, val firstRowIndex: Int, val repository: String?) {
    var gitOld: String? = null
    var gitNew: String? = null
    var oldName: String? = null
    var newName: String? = null
    var oldIsNull = false
    var newIsNull = false
    var newFileMode = false
    var deletedFileMode = false
    var renameFrom: String? = null
    var renameTo: String? = null
    var binary = false
    var sawOld = false
    var sawHunk = false
    var additions = 0
    var deletions = 0
    var lineCount = 0
    var characters = 0L
}

private class Parser(private val contentLimit: Int) {
    private val rows = ArrayList<DiffRow>()
    private val files = ArrayList<DiffFile>()
    var capHit = false
        private set

    private var file: FileBuilder? = null
    private var repository: String? = null

    private var inHunk = false
    private var countsKnown = false
    private var oldRemaining = 0
    private var newRemaining = 0
    private var oldNumber = 0
    private var newNumber = 0
    private var lastLineRow = -1

    private val deletedRows = IntList()
    private val addedRows = IntList()
    private val wordDiff = WordDiff()

    // Cursor for the number readers below.
    private var cursor = 0

    fun parse(text: String, end: Int, repository: String?) {
        this.repository = repository
        var position = 0
        while (position < end && !capHit) {
            var newline = text.indexOf('\n', position)
            if (newline < 0 || newline > end) newline = end
            var lineEnd = newline
            if (lineEnd > position && text[lineEnd - 1] == '\r') lineEnd--
            handleLine(text, position, lineEnd, end)
            position = newline + 1
        }
        finishFile()
    }

    fun finish(truncated: Boolean): DiffDocument {
        if (capHit) {
            rows.add(DiffRow.Notice(rows.size.toLong(), -1, "Diff cut off after ${rows.size} rows"))
        }
        return DiffDocument(files, rows, truncated = truncated, rowCapHit = capHit)
    }

    private fun addRow(build: (Long) -> DiffRow): Boolean {
        if (rows.size >= contentLimit) {
            capHit = true
            return false
        }
        rows.add(build(rows.size.toLong()))
        return true
    }

    private fun handleLine(text: String, start: Int, end: Int, limit: Int) {
        if (inHunk && handleHunkLine(text, start, end, limit)) return
        inHunk = false
        handleHeaderLine(text, start, end, limit)
    }

    private fun hunkOpen() = !countsKnown || oldRemaining > 0 || newRemaining > 0

    /** Returns false when the line is not part of the open hunk. */
    private fun handleHunkLine(text: String, start: Int, end: Int, limit: Int): Boolean {
        if (start == end) {
            if (!hunkOpen()) return false
            addLine(DiffLineKind.Context, text, start, end)
            return true
        }
        return when (text[start]) {
            '\\' -> {
                markNoNewline()
                true
            }
            ' ' -> if (hunkOpen()) { addLine(DiffLineKind.Context, text, start + 1, end); true } else false
            '+' -> if (hunkOpen()) { addLine(DiffLineKind.Add, text, start + 1, end); true } else false
            '-' -> when {
                !hunkOpen() -> false
                // Without hunk counts a file header is the only way out of the hunk: `--- x` followed by `+++ y`.
                !countsKnown && text.startsWith("--- ", start) && nextLineStartsWith(text, end, limit, "+++ ") -> false
                else -> { addLine(DiffLineKind.Del, text, start + 1, end); true }
            }
            else -> false
        }
    }

    private fun nextLineStartsWith(text: String, lineEnd: Int, limit: Int, prefix: String): Boolean {
        var next = lineEnd
        if (next < limit && text[next] == '\r') next++
        if (next < limit && text[next] == '\n') next++ else return false
        return next < limit && text.startsWith(prefix, next)
    }

    private fun handleHeaderLine(text: String, start: Int, end: Int, limit: Int) {
        if (start == end) return
        when (text[start]) {
            'd' -> when {
                text.startsWith("diff --git ", start) -> startGitFile(text, start + 11, end)
                text.startsWith("deleted file mode", start) -> file?.deletedFileMode = true
            }
            '@' -> if (text.startsWith("@@ ", start)) startHunk(text, start, end)
            '-' -> if (text.startsWith("--- ", start)) handleOldFileLine(text, start, end, limit)
            '+' -> if (text.startsWith("+++ ", start)) handleNewFileLine(text, start, end)
            'n' -> if (text.startsWith("new file mode", start)) file?.newFileMode = true
            'r' -> when {
                text.startsWith("rename from ", start) -> file?.renameFrom = headerPath(text, start + 12, end, stripPrefix = null)
                text.startsWith("rename to ", start) -> file?.renameTo = headerPath(text, start + 10, end, stripPrefix = null)
            }
            'c' -> when {
                text.startsWith("copy from ", start) -> file?.renameFrom = headerPath(text, start + 10, end, stripPrefix = null)
                text.startsWith("copy to ", start) -> file?.renameTo = headerPath(text, start + 8, end, stripPrefix = null)
            }
            'B' -> if (text.startsWith("Binary files ", start)) markBinary(text, start, end)
            'G' -> if (text.startsWith("GIT binary patch", start)) markBinary(text, start, end)
        }
    }

    private fun startGitFile(text: String, start: Int, end: Int) {
        if (!beginFile()) return
        val builder = file ?: return
        splitGitHeader(text, start, end, builder)
    }

    /** Closes the open file and opens a new one, emitting its header row. False when the row cap was hit. */
    private fun beginFile(): Boolean {
        finishFile()
        val index = files.size
        val first = rows.size
        if (!addRow { id -> DiffRow.FileHeader(id, index) }) return false
        file = FileBuilder(index, first, repository)
        return true
    }

    private fun handleOldFileLine(text: String, start: Int, end: Int, limit: Int) {
        var current = file
        if (current == null || current.sawHunk || current.sawOld) {
            // A plain unified diff has no `diff --git` line: its `--- x` / `+++ y` pair opens the file.
            if (!nextLineStartsWith(text, end, limit, "+++ ")) return
            if (!beginFile()) return
            current = file ?: return
        }
        current.sawOld = true
        if (isDevNull(text, start + 4, end)) {
            current.oldIsNull = true
        } else {
            current.oldName = headerPath(text, start + 4, end, stripPrefix = "a/")
        }
    }

    private fun handleNewFileLine(text: String, start: Int, end: Int) {
        val current = file ?: return
        if (current.sawHunk) return
        if (isDevNull(text, start + 4, end)) {
            current.newIsNull = true
        } else {
            current.newName = headerPath(text, start + 4, end, stripPrefix = "b/")
        }
    }

    private fun isDevNull(text: String, start: Int, end: Int): Boolean =
        text.startsWith("/dev/null", start) && (end - start == 9 || text[start + 9] == '\t')

    private fun markBinary(text: String, start: Int, end: Int) {
        val current = file ?: return
        if (current.binary) return
        current.binary = true
        if (text.startsWith("Binary files ", start)) {
            // `Binary files /dev/null and b/x differ` is an addition, `Binary files a/x and /dev/null differ` a deletion.
            if (text.startsWith("Binary files /dev/null and ", start)) current.oldIsNull = true
            if (end - start >= 21 && text.regionMatches(end - 21, " and /dev/null differ", 0, 21)) current.newIsNull = true
        }
        addRow { id -> DiffRow.Notice(id, current.index, "Binary file not shown") }
    }

    private fun startHunk(text: String, start: Int, end: Int) {
        val current = file ?: run {
            // A bare hunk with no file header: show it under an unnamed file.
            if (!beginFile()) return
            file
        } ?: return
        if (current.binary) return
        flushRun()
        current.sawHunk = true
        val header = text.substring(start, end)
        if (!addRow { id -> DiffRow.Hunk(id, current.index, header) }) return
        current.characters += header.length
        lastLineRow = -1
        inHunk = true
        parseHunkCounts(text, start + 3, end)
    }

    /** Reads `-a[,b] +c[,d]` after `@@ `. Missing counts default to 1; an unreadable header leaves the counts unknown. */
    private fun parseHunkCounts(text: String, start: Int, end: Int) {
        countsKnown = false
        oldNumber = 0
        newNumber = 0
        cursor = start
        if (cursor >= end || text[cursor] != '-') return
        cursor++
        val oldStart = readInt(text, end)
        if (oldStart < 0) return
        var oldCount = 1
        if (cursor < end && text[cursor] == ',') {
            cursor++
            oldCount = readInt(text, end)
            if (oldCount < 0) return
        }
        while (cursor < end && text[cursor] == ' ') cursor++
        if (cursor >= end || text[cursor] != '+') return
        cursor++
        val newStart = readInt(text, end)
        if (newStart < 0) return
        var newCount = 1
        if (cursor < end && text[cursor] == ',') {
            cursor++
            newCount = readInt(text, end)
            if (newCount < 0) return
        }
        countsKnown = true
        oldNumber = oldStart
        newNumber = newStart
        oldRemaining = oldCount
        newRemaining = newCount
    }

    private fun readInt(text: String, end: Int): Int {
        val begin = cursor
        var value = 0L
        while (cursor < end && text[cursor] in '0'..'9') {
            value = value * 10 + (text[cursor] - '0')
            if (value > Int.MAX_VALUE) return -1
            cursor++
        }
        return if (cursor == begin) -1 else value.toInt()
    }

    private fun addLine(kind: DiffLineKind, text: String, start: Int, end: Int) {
        val current = file ?: return
        val body = text.substring(start, end)
        val oldNo: Int?
        val newNo: Int?
        when (kind) {
            DiffLineKind.Context -> {
                oldNo = if (countsKnown) oldNumber++ else null
                newNo = if (countsKnown) newNumber++ else null
                oldRemaining--
                newRemaining--
            }
            DiffLineKind.Del -> {
                oldNo = if (countsKnown) oldNumber++ else null
                newNo = null
                oldRemaining--
            }
            DiffLineKind.Add -> {
                oldNo = null
                newNo = if (countsKnown) newNumber++ else null
                newRemaining--
            }
        }
        if (kind == DiffLineKind.Context) {
            flushRun()
        } else if (kind == DiffLineKind.Del && addedRows.size > 0) {
            // A deletion after additions starts the next change block.
            flushRun()
        }
        val rowIndex = rows.size
        if (!addRow { id -> DiffRow.Line(id, current.index, kind, oldNo, newNo, body) }) {
            flushRun()
            return
        }
        lastLineRow = rowIndex
        current.lineCount++
        current.characters += body.length
        when (kind) {
            DiffLineKind.Add -> { current.additions++; addedRows.add(rowIndex) }
            DiffLineKind.Del -> { current.deletions++; deletedRows.add(rowIndex) }
            DiffLineKind.Context -> Unit
        }
    }

    private fun markNoNewline() {
        val index = lastLineRow
        if (index < 0) return
        val line = rows[index] as? DiffRow.Line ?: return
        rows[index] = line.copy(noNewlineAtEnd = true)
    }

    /** Pairs the deleted and added lines of one change block (index by index, as upstream) and stores their word ranges. */
    private fun flushRun() {
        val paired = minOf(deletedRows.size, addedRows.size)
        for (k in 0 until paired) {
            val deletedIndex = deletedRows.data[k]
            val addedIndex = addedRows.data[k]
            val deleted = rows[deletedIndex] as DiffRow.Line
            val added = rows[addedIndex] as DiffRow.Line
            val old = deleted.text
            val new = added.text
            if ((old.isEmpty() && new.isEmpty()) || old.length > WORD_DIFF_MAX_LINE_LENGTH || new.length > WORD_DIFF_MAX_LINE_LENGTH) continue
            if (!wordDiff.compute(old, new)) continue
            rows[deletedIndex] = deleted.copy(wordRanges = wordDiff.oldRanges)
            rows[addedIndex] = added.copy(wordRanges = wordDiff.newRanges)
        }
        deletedRows.clear()
        addedRows.clear()
    }

    private fun finishFile() {
        flushRun()
        val builder = file ?: return
        file = null
        inHunk = false
        lastLineRow = -1
        val renamed = builder.renameFrom != null || builder.renameTo != null ||
            (builder.oldName != null && builder.newName != null && builder.oldName != builder.newName)
        val path = builder.renameTo ?: builder.newName ?: builder.oldName ?: builder.gitNew ?: builder.gitOld ?: ""
        val oldPath = if (renamed) (builder.renameFrom ?: builder.oldName ?: builder.gitOld)?.takeIf { it != path } else null
        val change = when {
            builder.binary -> DiffChange.Binary
            builder.deletedFileMode || builder.newIsNull -> DiffChange.Deleted
            builder.newFileMode || builder.oldIsNull -> DiffChange.Added
            renamed && oldPath != null -> DiffChange.Renamed
            else -> DiffChange.Modified
        }
        val generated = isGeneratedChangedFile(path)
        val collapsed = builder.lineCount > LARGE_DIFF_LINE_THRESHOLD || builder.characters > LARGE_DIFF_CHARACTER_THRESHOLD || generated
        files.add(
            DiffFile(
                index = builder.index,
                path = path,
                oldPath = oldPath,
                change = change,
                additions = builder.additions,
                deletions = builder.deletions,
                isTest = isTestChangedFile(path),
                isGenerated = generated,
                collapsedByDefault = collapsed,
                firstRowIndex = builder.firstRowIndex,
                endRowIndex = rows.size,
                lineCount = builder.lineCount,
                repository = builder.repository,
            ),
        )
    }
}

/** Fills [FileBuilder.gitOld] and [FileBuilder.gitNew] from `a/X b/Y` (the paths after `diff --git `). */
private fun splitGitHeader(text: String, start: Int, end: Int, builder: FileBuilder) {
    if (start >= end) return
    if (text[start] == '"') {
        val first = quotedEnd(text, start, end)
        if (first < 0) return
        builder.gitOld = stripPrefix(unquote(text, start, first), "a/")
        var second = first
        while (second < end && text[second] == ' ') second++
        if (second < end) {
            builder.gitNew = stripPrefix(if (text[second] == '"') unquote(text, second, quotedEnd(text, second, end).takeIf { it > 0 } ?: end) else text.substring(second, end), "b/")
        }
        return
    }
    val length = end - start
    // Same name on both sides: `a/X b/X` has the separator exactly in the middle.
    if (length % 2 == 1) {
        val middle = start + length / 2
        if (text[middle] == ' ' && text.regionMatches(start + 2, text, middle + 3, middle - start - 2) &&
            text.startsWith("a/", start) && text.startsWith("b/", middle + 1)
        ) {
            val name = text.substring(start + 2, middle)
            builder.gitOld = name
            builder.gitNew = name
            return
        }
    }
    val separator = text.lastIndexOf(" b/", end - 1)
    if (separator > start && text.startsWith("a/", start)) {
        builder.gitOld = text.substring(start + 2, separator)
        builder.gitNew = text.substring(separator + 3, end)
    } else {
        // `--no-prefix` diffs: split at the middle space when both halves match, else at the first space.
        val space = if (length % 2 == 1 && text[start + length / 2] == ' ') start + length / 2 else text.indexOf(' ', start).takeIf { it in start until end } ?: return
        builder.gitOld = text.substring(start, space)
        builder.gitNew = text.substring(space + 1, end)
    }
}

/** Reads a header path: quoted C-style or plain, cut at the tab git adds after paths with spaces. */
private fun headerPath(text: String, start: Int, end: Int, stripPrefix: String?): String {
    if (start < end && text[start] == '"') {
        val close = quotedEnd(text, start, end)
        if (close > 0) return stripPrefix(unquote(text, start, close), stripPrefix)
    }
    var cut = end
    val tab = text.indexOf('\t', start)
    if (tab in start until end) cut = tab
    return stripPrefix(text.substring(start, cut), stripPrefix)
}

private fun stripPrefix(path: String, prefix: String?): String =
    if (prefix != null && path.startsWith(prefix)) path.substring(prefix.length) else path

/** Index just after the closing quote of the quoted string starting at [start], or -1. */
private fun quotedEnd(text: String, start: Int, end: Int): Int {
    var i = start + 1
    while (i < end) {
        when (text[i]) {
            '\\' -> i += 2
            '"' -> return i + 1
            else -> i++
        }
    }
    return -1
}

/** Decodes git's C-style quoting (`\"`, `\\`, `\t`, `\n` and `\NNN` octal UTF-8 bytes). */
private fun unquote(text: String, start: Int, end: Int): String {
    val out = ByteArrayOutputStream(end - start)
    var i = start + 1
    val last = end - 1
    while (i < last) {
        if (text[i] != '\\' || i + 1 >= last) {
            var run = i + 1
            while (run < last && text[run] != '\\') run++
            out.write(text.substring(i, run).toByteArray(Charsets.UTF_8))
            i = run
            continue
        }
        val next = text[i + 1]
        when (next) {
            in '0'..'7' -> {
                var value = 0
                var digits = 0
                while (digits < 3 && i + 1 + digits < last && text[i + 1 + digits] in '0'..'7') {
                    value = value * 8 + (text[i + 1 + digits] - '0')
                    digits++
                }
                out.write(value and 0xFF)
                i += 1 + digits
            }
            'n' -> { out.write('\n'.code); i += 2 }
            't' -> { out.write('\t'.code); i += 2 }
            'r' -> { out.write('\r'.code); i += 2 }
            'a' -> { out.write(7); i += 2 }
            'b' -> { out.write(8); i += 2 }
            'f' -> { out.write(12); i += 2 }
            'v' -> { out.write(11); i += 2 }
            else -> { out.write(next.toString().toByteArray(Charsets.UTF_8)); i += 2 }
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

/**
 * Token-wise changed ranges between a deleted and an added line, close to jsdiff's `diffWordsWithSpace` as used upstream:
 * tokens are runs of word characters, runs of whitespace, runs of other characters (brackets and quotes alone), and
 * ranges closer than one character are merged. Bounded work: common head and tail tokens are trimmed first, and the LCS
 * table of the rest is capped; beyond the cap the whole middle counts as changed.
 */
private class WordDiff {
    var oldRanges: IntArray? = null
    var newRanges: IntArray? = null

    private var dp = IntArray(0)
    private var budget = TOTAL_CELL_BUDGET

    private var oldStarts = IntArray(64)
    private var oldHashes = IntArray(64)
    private var newStarts = IntArray(64)
    private var newHashes = IntArray(64)
    private var oldChanged = BooleanArray(64)
    private var newChanged = BooleanArray(64)

    /** Returns true when the lines differ and [oldRanges] / [newRanges] are set. */
    fun compute(old: String, new: String): Boolean {
        val oldCount = tokenize(old, isOld = true)
        val newCount = tokenize(new, isOld = false)
        var head = 0
        while (head < oldCount && head < newCount && equal(old, new, head, head)) head++
        var tail = 0
        while (tail < oldCount - head && tail < newCount - head && equal(old, new, oldCount - 1 - tail, newCount - 1 - tail)) tail++
        val oldMiddle = oldCount - head - tail
        val newMiddle = newCount - head - tail
        if (oldMiddle == 0 && newMiddle == 0) return false

        if (oldChanged.size < oldCount) oldChanged = BooleanArray(oldCount * 2)
        if (newChanged.size < newCount) newChanged = BooleanArray(newCount * 2)
        java.util.Arrays.fill(oldChanged, 0, oldCount, false)
        java.util.Arrays.fill(newChanged, 0, newCount, false)

        val cells = (oldMiddle + 1).toLong() * (newMiddle + 1)
        if (oldMiddle == 0 || newMiddle == 0 || cells > MAX_CELLS || cells > budget) {
            for (i in head until head + oldMiddle) oldChanged[i] = true
            for (j in head until head + newMiddle) newChanged[j] = true
        } else {
            budget -= cells.toInt()
            lcsMark(old, new, head, oldMiddle, newMiddle)
        }
        oldRanges = toRanges(oldChanged, oldStarts, oldCount)
        newRanges = toRanges(newChanged, newStarts, newCount)
        return oldRanges != null || newRanges != null
    }

    private fun equal(old: String, new: String, i: Int, j: Int): Boolean {
        if (oldHashes[i] != newHashes[j]) return false
        val length = oldStarts[i + 1] - oldStarts[i]
        return length == newStarts[j + 1] - newStarts[j] && old.regionMatches(oldStarts[i], new, newStarts[j], length)
    }

    /** Marks the tokens outside a longest common subsequence of the middles, which start at token [head] on both sides. */
    private fun lcsMark(old: String, new: String, head: Int, oldMiddle: Int, newMiddle: Int) {
        val width = newMiddle + 1
        val size = (oldMiddle + 1) * width
        if (dp.size < size) dp = IntArray(size)
        for (j in 0..newMiddle) dp[oldMiddle * width + j] = 0
        for (i in oldMiddle - 1 downTo 0) {
            dp[i * width + newMiddle] = 0
            for (j in newMiddle - 1 downTo 0) {
                dp[i * width + j] = if (equal(old, new, head + i, head + j)) {
                    dp[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(dp[(i + 1) * width + j], dp[i * width + j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < oldMiddle && j < newMiddle) {
            when {
                equal(old, new, head + i, head + j) -> { i++; j++ }
                dp[(i + 1) * width + j] >= dp[i * width + j + 1] -> { oldChanged[head + i] = true; i++ }
                else -> { newChanged[head + j] = true; j++ }
            }
        }
        while (i < oldMiddle) { oldChanged[head + i] = true; i++ }
        while (j < newMiddle) { newChanged[head + j] = true; j++ }
    }

    /** Flat `[start, end)` pairs of changed tokens, merging ranges at most one character apart; null when none. */
    private fun toRanges(changed: BooleanArray, starts: IntArray, count: Int): IntArray? {
        var out: IntArray? = null
        var size = 0
        var i = 0
        while (i < count) {
            if (!changed[i]) { i++; continue }
            val from = starts[i]
            while (i < count && changed[i]) i++
            val to = starts[i]
            val buffer = out ?: IntArray(8).also { out = it }
            if (size > 0 && from - buffer[size - 1] <= 1) {
                buffer[size - 1] = to
            } else {
                val target = if (size + 2 > buffer.size) buffer.copyOf(buffer.size * 2).also { out = it } else buffer
                target[size++] = from
                target[size++] = to
            }
        }
        return out?.copyOf(size)
    }

    /** Splits [s] into tokens; fills the start offsets (with a final sentinel at the length) and hashes. */
    private fun tokenize(s: String, isOld: Boolean): Int {
        var starts = if (isOld) oldStarts else newStarts
        var hashes = if (isOld) oldHashes else newHashes
        val length = s.length
        var count = 0
        var i = 0
        while (i < length) {
            if (count + 2 > starts.size) {
                starts = starts.copyOf(starts.size * 2)
                hashes = hashes.copyOf(hashes.size * 2)
            }
            val begin = i
            val c = s[i]
            val kind = charKind(c)
            i++
            if (kind != KIND_SYMBOL_SINGLE) {
                while (i < length && charKind(s[i]) == kind) i++
            }
            var hash = 17
            for (k in begin until i) hash = hash * 31 + s[k].code
            starts[count] = begin
            hashes[count] = hash
            count++
        }
        starts[count] = length
        if (isOld) { oldStarts = starts; oldHashes = hashes } else { newStarts = starts; newHashes = hashes }
        return count
    }

    private fun charKind(c: Char): Int = when {
        c == '_' || c.isLetterOrDigit() -> KIND_WORD
        c == ' ' || c == '\t' || c.isWhitespace() -> KIND_SPACE
        c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' || c == '\'' || c == '"' -> KIND_SYMBOL_SINGLE
        else -> KIND_SYMBOL
    }

    private companion object {
        const val KIND_WORD = 0
        const val KIND_SPACE = 1
        const val KIND_SYMBOL = 2
        const val KIND_SYMBOL_SINGLE = 3
        /** An LCS table above this many cells (a 200 x 200 token middle) is replaced by "the whole middle changed". */
        const val MAX_CELLS = 40_000L
        /** Table cells a whole document may spend, so a diff of thousands of long paired lines stays fast. */
        const val TOTAL_CELL_BUDGET = 60_000_000
    }
}
