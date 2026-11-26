package com.tajchert.trefoil.core.model

import org.junit.Assert.*
import org.junit.Test

class UnifiedDiffParserTest {
    private fun lines(doc: DiffDocument) = doc.rows.filterIsInstance<DiffRow.Line>()
    private fun DiffDocument.slice(file: DiffFile) = rows.subList(file.firstRowIndex, file.endRowIndex)
    private fun ranges(line: DiffRow.Line, whole: String = line.text): List<String> =
        line.wordRanges?.toList()?.chunked(2)?.map { (s, e) -> whole.substring(s, e) } ?: emptyList()

    private val modified = """
        diff --git a/src/App.kt b/src/App.kt
        index 1111111..2222222 100644
        --- a/src/App.kt
        +++ b/src/App.kt
        @@ -1,4 +1,5 @@ fun main()
         one
        -two
        +two changed
        +three
         four
         five
    """.trimIndent() + "\n"

    @Test fun `modified file flattens into header hunk and numbered lines`() {
        val doc = UnifiedDiffParser.parse(modified)
        assertEquals(1, doc.files.size)
        val file = doc.files.single()
        assertEquals("src/App.kt", file.path)
        assertNull(file.oldPath)
        assertEquals(DiffChange.Modified, file.change)
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)
        assertEquals(6, file.lineCount)
        assertEquals(0, file.firstRowIndex)
        assertEquals(doc.rows.size, file.endRowIndex)
        assertFalse(doc.truncated)
        assertFalse(doc.rowCapHit)

        assertTrue(doc.rows[0] is DiffRow.FileHeader)
        assertEquals("@@ -1,4 +1,5 @@ fun main()", (doc.rows[1] as DiffRow.Hunk).header)
        val l = lines(doc)
        assertEquals(listOf(DiffLineKind.Context, DiffLineKind.Del, DiffLineKind.Add, DiffLineKind.Add, DiffLineKind.Context, DiffLineKind.Context), l.map { it.kind })
        assertEquals(listOf(1 to 1, 2 to null, null to 2, null to 3, 3 to 4, 4 to 5), l.map { it.oldNumber to it.newNumber })
        assertEquals(listOf("one", "two", "two changed", "three", "four", "five"), l.map { it.text })
    }

    @Test fun `row ids are sequential and stable across parses`() {
        val a = UnifiedDiffParser.parse(modified)
        val b = UnifiedDiffParser.parse(modified)
        assertEquals(a.rows.indices.map { it.toLong() }, a.rows.map { it.id })
        assertEquals(a, b)
        assertTrue(a.rows.all { it.fileIndex == 0 })
    }

    @Test fun `word ranges mark the changed tokens of paired lines`() {
        val doc = UnifiedDiffParser.parse(modified)
        val del = lines(doc).first { it.kind == DiffLineKind.Del }
        val add = lines(doc).first { it.kind == DiffLineKind.Add }
        assertNull(del.wordRanges)
        assertEquals(listOf(" changed"), ranges(add))
    }

    @Test fun `unpaired additions and context lines get no word ranges`() {
        val doc = UnifiedDiffParser.parse(modified)
        val extra = lines(doc).first { it.text == "three" }
        assertNull(extra.wordRanges)
        assertTrue(lines(doc).filter { it.kind == DiffLineKind.Context }.all { it.wordRanges == null })
    }

    @Test fun `pairing is index by index within a change block`() {
        val diff = """
            diff --git a/f b/f
            --- a/f
            +++ b/f
            @@ -1,3 +1,3 @@
            -alpha one
            -beta two
            -gamma three
            +alpha 1
            +beta 2
            +gamma 3
        """.trimIndent() + "\n"
        val l = lines(UnifiedDiffParser.parse(diff))
        assertEquals(listOf("one"), ranges(l[0]))
        assertEquals(listOf("two"), ranges(l[1]))
        assertEquals(listOf("three"), ranges(l[2]))
        assertEquals(listOf("1"), ranges(l[3]))
        assertEquals(listOf("2"), ranges(l[4]))
        assertEquals(listOf("3"), ranges(l[5]))
    }

    @Test fun `word ranges are skipped over the length limit`() {
        val long = "x".repeat(WORD_DIFF_MAX_LINE_LENGTH + 1)
        val diff = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a $long\n+b $long\n"
        assertTrue(lines(UnifiedDiffParser.parse(diff)).all { it.wordRanges == null })
        val shortLine = "x".repeat(WORD_DIFF_MAX_LINE_LENGTH - 2)
        val ok = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a $shortLine\n+b $shortLine\n"
        assertNotNull(lines(UnifiedDiffParser.parse(ok)).first().wordRanges)
    }

    @Test fun `identical text after CR stripping has no ranges`() {
        val diff = "diff --git a/f b/f\r\n--- a/f\r\n+++ b/f\r\n@@ -1 +1 @@\r\n-same\r\n+same\r\n"
        val l = lines(UnifiedDiffParser.parse(diff))
        assertEquals(listOf("same", "same"), l.map { it.text })
        assertTrue(l.all { it.wordRanges == null })
    }

    @Test fun `added file with dev null`() {
        val diff = """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            index 0000000..e69de29
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
        """.trimIndent() + "\n"
        val doc = UnifiedDiffParser.parse(diff)
        val file = doc.files.single()
        assertEquals("new.txt", file.path)
        assertEquals(DiffChange.Added, file.change)
        assertEquals(2, file.additions)
        assertEquals(0, file.deletions)
        assertEquals(listOf(null to 1, null to 2), lines(doc).map { it.oldNumber to it.newNumber })
    }

    @Test fun `deleted file keeps its old path`() {
        val diff = """
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            index e69de29..0000000
            --- a/gone.txt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -a
            -b
        """.trimIndent() + "\n"
        val file = UnifiedDiffParser.parse(diff).files.single()
        assertEquals("gone.txt", file.path)
        assertEquals(DiffChange.Deleted, file.change)
        assertEquals(2, file.deletions)
    }

    @Test fun `rename with and without content change`() {
        val diff = """
            diff --git a/old/name.kt b/new/name.kt
            similarity index 100%
            rename from old/name.kt
            rename to new/name.kt
            diff --git a/a.kt b/b.kt
            similarity index 90%
            rename from a.kt
            rename to b.kt
            index 111..222 100644
            --- a/a.kt
            +++ b/b.kt
            @@ -1 +1 @@
            -x
            +y
        """.trimIndent() + "\n"
        val doc = UnifiedDiffParser.parse(diff)
        assertEquals(2, doc.files.size)
        assertEquals("new/name.kt", doc.files[0].path)
        assertEquals("old/name.kt", doc.files[0].oldPath)
        assertEquals(DiffChange.Renamed, doc.files[0].change)
        assertEquals(1, doc.files[0].endRowIndex - doc.files[0].firstRowIndex)
        assertEquals("b.kt", doc.files[1].path)
        assertEquals("a.kt", doc.files[1].oldPath)
        assertEquals(DiffChange.Renamed, doc.files[1].change)
        assertEquals(1, doc.files[1].additions)
        assertEquals(doc.files[0].endRowIndex, doc.files[1].firstRowIndex)
    }

    @Test fun `binary files show a notice and no lines`() {
        val diff = """
            diff --git a/logo.png b/logo.png
            new file mode 100644
            index 0000000..abcdef0
            Binary files /dev/null and b/logo.png differ
            diff --git a/img.png b/img.png
            index 111..222 100644
            Binary files a/img.png and b/img.png differ
            diff --git a/blob.bin b/blob.bin
            index 111..222 100644
            GIT binary patch
            literal 10
            Rc${'$'}@N)abcdefg

            literal 0
            HcmV?d00001

            diff --git a/after.txt b/after.txt
            --- a/after.txt
            +++ b/after.txt
            @@ -1 +1 @@
            -a
            +b
        """.trimIndent() + "\n"
        val doc = UnifiedDiffParser.parse(diff)
        assertEquals(listOf("logo.png", "img.png", "blob.bin", "after.txt"), doc.files.map { it.path })
        assertEquals(List(3) { DiffChange.Binary } + DiffChange.Modified, doc.files.map { it.change })
        assertEquals(0, doc.files[0].lineCount)
        for (i in 0..2) assertTrue(doc.slice(doc.files[i]).any { it is DiffRow.Notice })
        assertEquals(listOf("a", "b"), lines(doc).map { it.text })
    }

    @Test fun `no newline marker flags the preceding line`() {
        val diff = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file\n"
        val l = lines(UnifiedDiffParser.parse(diff))
        assertEquals(2, l.size)
        assertTrue(l.all { it.noNewlineAtEnd })
        assertEquals(listOf("old", "new"), l.map { it.text })
        assertNotNull(l[0].wordRanges)
    }

    @Test fun `no newline marker only affects its own line`() {
        val diff = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\n\\ No newline at end of file\n"
        val l = lines(UnifiedDiffParser.parse(diff))
        assertEquals(listOf(false, false, true), l.map { it.noNewlineAtEnd })
    }

    @Test fun `CRLF line endings are stripped from rows`() {
        val doc = UnifiedDiffParser.parse(modified.replace("\n", "\r\n"))
        assertEquals("src/App.kt", doc.files.single().path)
        assertEquals(listOf("one", "two", "two changed", "three", "four", "five"), lines(doc).map { it.text })
        assertEquals("@@ -1,4 +1,5 @@ fun main()", (doc.rows[1] as DiffRow.Hunk).header)
    }

    @Test fun `trailing truncated marker sets truncated and is not content`() {
        val doc = UnifiedDiffParser.parse(modified + "\n[truncated]")
        assertTrue(doc.truncated)
        assertFalse(doc.rowCapHit)
        assertEquals(6, lines(doc).size)
        assertEquals("five", lines(doc).last().text)
        assertTrue(UnifiedDiffParser.parse(modified.trimEnd() + "\n\n[truncated]\n").truncated)
        assertTrue(UnifiedDiffParser.parse("[truncated]").truncated)
        assertFalse(UnifiedDiffParser.parse(modified).truncated)
        // The marker text inside a line is content, not the marker.
        assertFalse(UnifiedDiffParser.parse(modified.trimEnd() + "[truncated]").truncated)
    }

    @Test fun `a diff cut mid hunk keeps what arrived`() {
        val cut = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,50 +1,50 @@\n a\n-b\n+c\n\n[truncated]"
        val doc = UnifiedDiffParser.parse(cut)
        assertTrue(doc.truncated)
        assertEquals(listOf("a", "b", "c"), lines(doc).map { it.text })
    }

    @Test fun `row cap stops parsing and appends a notice`() {
        val body = (1..100).joinToString("") { "+line $it\n" }
        val diff = "diff --git a/f b/f\nnew file mode 100644\n--- /dev/null\n+++ b/f\n@@ -0,0 +1,100 @@\n$body"
        val doc = UnifiedDiffParser.parse(diff, maxRows = 10)
        assertTrue(doc.rowCapHit)
        assertEquals(11, doc.rows.size)
        assertTrue(doc.rows.last() is DiffRow.Notice)
        assertEquals(-1, doc.rows.last().fileIndex)
        assertEquals(8, doc.files.single().lineCount)
        assertEquals(10, doc.files.single().endRowIndex)
        assertEquals(doc.rows.indices.map { it.toLong() }, doc.rows.map { it.id })
    }

    @Test fun `row cap is shared across repositories and later parts are dropped`() {
        val one = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-a\n+b\n"
        val doc = UnifiedDiffParser.parseRepositories(listOf("one" to one, "two" to one, "three" to one), maxRows = 8)
        assertTrue(doc.rowCapHit)
        assertEquals(9, doc.rows.size)
        assertEquals(listOf("one", "two"), doc.files.map { it.repository })
    }

    @Test fun `under the cap the flag stays off`() {
        val doc = UnifiedDiffParser.parse(modified, maxRows = 8)
        assertFalse(doc.rowCapHit)
        assertEquals(8, doc.rows.size)
        assertTrue(UnifiedDiffParser.parse(modified, maxRows = 7).rowCapHit)
    }

    @Test fun `repositories renumber ids and file indexes and keep labels`() {
        val a = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-1\n+2\n"
        val b = "diff --git a/y b/y\n--- a/y\n+++ b/y\n@@ -1 +1 @@\n-3\n+4\n[truncated]".replace("[truncated]", "\n[truncated]")
        val doc = UnifiedDiffParser.parseRepositories(listOf("app" to a, null to b, "lib" to ""))
        assertEquals(listOf("app", null), doc.files.map { it.repository })
        assertEquals(listOf(0, 1), doc.files.map { it.index })
        assertEquals(doc.rows.indices.map { it.toLong() }, doc.rows.map { it.id })
        assertEquals(setOf(0, 1), doc.rows.map { it.fileIndex }.toSet())
        assertTrue(doc.truncated)
        assertEquals(doc.files[0].endRowIndex, doc.files[1].firstRowIndex)
    }

    @Test fun `parse labels every file with the repository`() {
        assertEquals("r", UnifiedDiffParser.parse(modified, repository = "r").files.single().repository)
        assertNull(UnifiedDiffParser.parse(modified).files.single().repository)
    }

    @Test fun `content that looks like headers stays content inside a hunk`() {
        val diff = """
            diff --git a/f.md b/f.md
            --- a/f.md
            +++ b/f.md
            @@ -1,3 +1,3 @@
            --- not a header
             context
            +++ also not a header
        """.trimIndent() + "\n"
        val doc = UnifiedDiffParser.parse(diff)
        assertEquals(1, doc.files.size)
        assertEquals(listOf("-- not a header", "context", "++ also not a header"), lines(doc).map { it.text })
    }

    @Test fun `multiple hunks restart numbering and hunk without counts defaults to one`() {
        val diff = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n+b\n@@ -10,2 +10,2 @@ ctx\n c\n-d\n+e\n"
        val doc = UnifiedDiffParser.parse(diff)
        assertEquals(2, doc.rows.count { it is DiffRow.Hunk })
        val l = lines(doc)
        assertEquals(listOf(1 to null, null to 1, 10 to 10, 11 to null, null to 11), l.map { it.oldNumber to it.newNumber })
    }

    @Test fun `plain unified diff without diff --git header`() {
        val diff = "--- a/p.txt\t2020-01-01\n+++ b/p.txt\t2020-01-02\n@@ -1 +1 @@\n-a\n+b\n--- a/q.txt\n+++ b/q.txt\n@@ -1 +1 @@\n-c\n+d\n"
        val doc = UnifiedDiffParser.parse(diff)
        assertEquals(listOf("p.txt", "q.txt"), doc.files.map { it.path })
        assertEquals(listOf("a", "b", "c", "d"), lines(doc).map { it.text })
    }

    @Test fun `quoted and spaced paths`() {
        val diff = """
            diff --git "a/caf\303\251 \"x\".txt" "b/caf\303\251 \"x\".txt"
            --- "a/caf\303\251 \"x\".txt"
            +++ "b/caf\303\251 \"x\".txt"
            @@ -1 +1 @@
            -a
            +b
            diff --git a/with space.txt b/with space.txt
            --- a/with space.txt
            +++ b/with space.txt
            @@ -1 +1 @@
            -a
            +b
            diff --git a/only mode.sh b/only mode.sh
            old mode 100644
            new mode 100755
        """.trimIndent() + "\n"
        val doc = UnifiedDiffParser.parse(diff)
        assertEquals(listOf("café \"x\".txt", "with space.txt", "only mode.sh"), doc.files.map { it.path })
        assertEquals(DiffChange.Modified, doc.files[2].change)
    }

    @Test fun `file kinds and collapse rules`() {
        fun fileDiff(path: String, lines: Int, width: Int = 4): String {
            val body = (1..lines).joinToString("") { "+" + "x".repeat(width) + "\n" }
            return "diff --git a/$path b/$path\nnew file mode 100644\n--- /dev/null\n+++ b/$path\n@@ -0,0 +1,$lines @@\n$body"
        }
        val diff = fileDiff("src/Small.kt", 10) + fileDiff("src/SmallTest.kt", 10) + fileDiff("pnpm-lock.yaml", 3) +
            fileDiff("src/Big.kt", LARGE_DIFF_LINE_THRESHOLD + 1) + fileDiff("src/Edge.kt", LARGE_DIFF_LINE_THRESHOLD) +
            fileDiff("src/Wide.kt", 5, width = LARGE_DIFF_CHARACTER_THRESHOLD / 5 + 10)
        val doc = UnifiedDiffParser.parse(diff)
        val byName = doc.files.associateBy { it.path }
        assertFalse(byName.getValue("src/Small.kt").collapsedByDefault)
        assertFalse(byName.getValue("src/Small.kt").isTest)
        assertTrue(byName.getValue("src/SmallTest.kt").isTest)
        assertTrue(byName.getValue("pnpm-lock.yaml").isGenerated)
        assertTrue(byName.getValue("pnpm-lock.yaml").collapsedByDefault)
        assertTrue(byName.getValue("src/Big.kt").collapsedByDefault)
        assertEquals(LARGE_DIFF_LINE_THRESHOLD + 1, byName.getValue("src/Big.kt").lineCount)
        assertFalse(byName.getValue("src/Edge.kt").collapsedByDefault)
        assertTrue(byName.getValue("src/Wide.kt").collapsedByDefault)
    }

    @Test fun `empty and header-only input`() {
        assertEquals(DiffDocument.Empty, UnifiedDiffParser.parse(""))
        assertTrue(UnifiedDiffParser.parse("garbage\nmore garbage\n").files.isEmpty())
    }

    @Test fun `word ranges merge nearby changes and cover insertions`() {
        val diff = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-val total = price * count\n+val sum = price * (count + 1)\n"
        val l = lines(UnifiedDiffParser.parse(diff))
        assertEquals(listOf("total"), ranges(l[0]))
        assertEquals(listOf("sum", "(", " + 1)"), ranges(l[1]))
    }

    @Test fun `ten megabyte diff parses quickly`() {
        val diff = syntheticDiff(10_000_000)
        assertTrue("synthetic diff is ${diff.length} chars", diff.length >= 10_000_000)
        for (cap in listOf(DIFF_MAX_ROWS, Int.MAX_VALUE)) {
            UnifiedDiffParser.parse(diff, maxRows = cap) // warm up the JIT
            val start = System.nanoTime()
            val doc = UnifiedDiffParser.parse(diff, maxRows = cap)
            val millis = (System.nanoTime() - start) / 1_000_000
            println("10 MB diff, maxRows=$cap: ${doc.rows.size} rows, ${doc.files.size} files, cap=${doc.rowCapHit}, parsed in $millis ms")
            assertTrue("parse took $millis ms", millis < 5_000)
            if (cap == DIFF_MAX_ROWS) {
                assertTrue(doc.rowCapHit)
                assertEquals(DIFF_MAX_ROWS + 1, doc.rows.size)
            } else {
                assertFalse(doc.rowCapHit)
                assertTrue(doc.rows.size > DIFF_MAX_ROWS + 1)
            }
        }
    }

    private fun syntheticDiff(chars: Int): String {
        val out = StringBuilder(chars + 4096)
        var file = 0
        while (out.length < chars) {
            val path = "src/module${file % 50}/File$file.kt"
            out.append("diff --git a/$path b/$path\nindex 1111111..2222222 100644\n--- a/$path\n+++ b/$path\n")
            var hunk = 0
            while (hunk < 5 && out.length < chars) {
                out.append("@@ -${hunk * 40 + 1},40 +${hunk * 40 + 1},41 @@ fun scope$hunk()\n")
                for (i in 0 until 12) out.append("     val context$i = computeSomethingFairlyLong(argument$i, other$i)\n")
                for (i in 0 until 8) out.append("-    val value$i = compute(argumentOld$i, other$i) // old comment number $i\n")
                for (i in 0 until 9) out.append("+    val value$i = compute(argumentNew$i, other$i, extra) // new comment number $i\n")
                for (i in 12 until 32) out.append("     val context$i = computeSomethingFairlyLong(argument$i, other$i)\n")
                hunk++
            }
            file++
        }
        return out.toString()
    }
}
