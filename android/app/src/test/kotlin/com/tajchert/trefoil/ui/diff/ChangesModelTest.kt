package com.tajchert.trefoil.ui.diff

import com.tajchert.trefoil.core.model.CheckpointStatus
import com.tajchert.trefoil.core.model.DiffChange
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffLineKind
import com.tajchert.trefoil.core.model.DiffRow
import com.tajchert.trefoil.core.model.ThreadCheckpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesModelTest {
    private fun checkpoint(turn: Int) = ThreadCheckpoint(id = "c$turn", turnCount = turn, status = CheckpointStatus.Ready, files = emptyList())

    /** A document of [paths] (a path with ".test." counts as a test file), one hunk and [lines] lines per file, a row-cap notice at the end. */
    private fun document(vararg paths: String, lines: Int = 2): DiffDocument {
        val rows = ArrayList<DiffRow>()
        val files = ArrayList<DiffFile>()
        var id = 0L
        paths.forEachIndexed { f, path ->
            val first = rows.size
            rows += DiffRow.FileHeader(id++, f)
            rows += DiffRow.Hunk(id++, f, "@@ -1 +1 @@")
            for (n in 1..lines) rows += DiffRow.Line(id++, f, DiffLineKind.Add, null, n, "$path:$n")
            files += DiffFile(f, path, change = DiffChange.Modified, additions = lines, deletions = 0, isTest = ".test." in path,
                firstRowIndex = first, endRowIndex = rows.size, lineCount = lines)
        }
        rows += DiffRow.Notice(id++, -1, "capped")
        return DiffDocument(files, rows, truncated = true, rowCapHit = true)
    }

    private fun DiffRow.fileIndexOrNull(): Int? = when (this) {
        is DiffRow.FileHeader -> fileIndex
        is DiffRow.Hunk -> fileIndex
        is DiffRow.Line -> fileIndex
        is DiffRow.Notice -> fileIndex
    }

    /** Every file's rows are its own, in order, and indexes are consecutive. */
    private fun assertConsistent(doc: DiffDocument) {
        doc.files.forEachIndexed { i, file ->
            assertEquals(i, file.index)
            for (r in file.firstRowIndex until file.endRowIndex) assertEquals(i, doc.rows[r].fileIndexOrNull())
            assertTrue(doc.rows[file.firstRowIndex] is DiffRow.FileHeader)
            if (i > 0) assertEquals(doc.files[i - 1].endRowIndex, file.firstRowIndex)
        }
        assertEquals(doc.rows.size, doc.rows.map { it.id }.toSet().size)
    }

    @Test fun turnsListNewestFirstAndAMissingTurnFallsBackToTheNewest() {
        val turns = listOf(checkpoint(1), checkpoint(3), checkpoint(2)).newestFirst()
        assertEquals(listOf(3, 2, 1), turns.map { it.turnCount })
        assertEquals(2, resolveChangesTurn(turns, 2))
        assertEquals(3, resolveChangesTurn(turns, null))
        assertEquals(3, resolveChangesTurn(turns, 9))
        assertNull(resolveChangesTurn(emptyList(), 1))
    }

    @Test fun filesLabelIsSingularForOne() {
        assertEquals("1 file", filesLabel(1))
        assertEquals("0 files", filesLabel(0))
        assertEquals("3 files", filesLabel(3))
    }

    @Test fun reviewOrderPutsTestsAfterTheirSourceAndKeepsRowsConsistent() {
        val doc = document("src/Foo.test.ts", "src/Foo.ts", "lib/Bar.ts")
        val arranged = doc.arrangedForReview(hideTests = false)
        assertEquals(doc.files.map { it.path }.toSet(), arranged.files.map { it.path }.toSet())
        assertTrue(arranged.files.indexOfFirst { it.path == "src/Foo.ts" } < arranged.files.indexOfFirst { it.path == "src/Foo.test.ts" })
        assertConsistent(arranged)
        // The row-cap notice stays last and the document keeps its flags.
        assertEquals("capped", (arranged.rows.last() as DiffRow.Notice).text)
        assertTrue(arranged.truncated && arranged.rowCapHit)
        assertEquals(doc.rows.size, arranged.rows.size)
    }

    @Test fun hidingTestsDropsTheirRowsAndRenumbersTheRest() {
        val doc = document("src/Foo.test.ts", "src/Foo.ts", "lib/Bar.ts")
        val arranged = doc.arrangedForReview(hideTests = true)
        assertEquals(setOf("src/Foo.ts", "lib/Bar.ts"), arranged.files.map { it.path }.toSet())
        assertConsistent(arranged)
        assertEquals(doc.rows.size - 4, arranged.rows.size)
        assertTrue(arranged.rows.none { it is DiffRow.Line && it.text.startsWith("src/Foo.test.ts") })
        assertEquals("capped", (arranged.rows.last() as DiffRow.Notice).text)
    }

    @Test fun anAlreadyOrderedDocumentIsReturnedAsIs() {
        val doc = document("lib/Bar.ts")
        assertSame(doc, doc.arrangedForReview(hideTests = true))
        assertSame(DiffDocument.Empty, DiffDocument.Empty.arrangedForReview(hideTests = true))
    }

    @Test fun duplicatePathsAreKept() {
        val doc = document("a/X.ts", "a/X.ts", "b/Y.ts")
        val arranged = doc.arrangedForReview(hideTests = false)
        assertEquals(3, arranged.files.size)
        assertConsistent(arranged)
    }

    @Test fun theLoaderLoadsOnceAndKeepsTheResult() = runTest {
        var reads = 0
        val loader = TurnDiffLoader(TestScope(UnconfinedTestDispatcher(testScheduler)), load = { reads++; document("a.kt") })
        assertNull(loader.stateOf(1))
        loader.ensure(1)
        assertTrue(loader.stateOf(1) is TurnDiffState.Loaded)
        loader.ensure(1)
        assertEquals(1, reads)
        loader.reload(1)
        assertEquals(2, reads)
    }

    @Test fun aFailureCarriesTheMessageOrAFallbackAndRetryRecovers() = runTest {
        var attempt = 0
        val loader = TurnDiffLoader(TestScope(UnconfinedTestDispatcher(testScheduler)), load = { turn ->
            attempt++
            when {
                turn == 2 -> error("")
                attempt == 1 -> error("checkpoint is gone")
                else -> document("a.kt")
            }
        })
        loader.ensure(1)
        assertEquals(TurnDiffState.Failed("checkpoint is gone"), loader.stateOf(1))
        loader.ensure(1) // a failed turn is retried explicitly, not by ensure
        assertTrue(loader.stateOf(1) is TurnDiffState.Failed)
        loader.reload(1)
        assertTrue(loader.stateOf(1) is TurnDiffState.Loaded)
        loader.ensure(2)
        assertEquals(TurnDiffState.Failed(CHANGES_FALLBACK_ERROR), loader.stateOf(2))
    }

    @Test fun theLoaderKeepsAtMostMaxLoadedTurnsDroppingTheLeastRecentlyShown() = runTest {
        val loader = TurnDiffLoader(TestScope(UnconfinedTestDispatcher(testScheduler)), load = { document("a.kt") }, maxLoaded = 2)
        loader.ensure(1); loader.ensure(2); loader.ensure(3)
        assertNull(loader.stateOf(1))
        assertTrue(loader.stateOf(2) is TurnDiffState.Loaded)
        assertTrue(loader.stateOf(3) is TurnDiffState.Loaded)
    }

    @Test fun cancellingStopsAReadInFlightAndForgetsItsLoadingState() = runTest {
        val gate = CompletableDeferred<DiffDocument>()
        val loader = TurnDiffLoader(TestScope(UnconfinedTestDispatcher(testScheduler)), load = { gate.await() })
        loader.ensure(1)
        assertEquals(TurnDiffState.Loading, loader.stateOf(1))
        loader.cancelAll()
        assertNull(loader.stateOf(1))
        gate.complete(document("a.kt"))
        assertNull(loader.stateOf(1))
    }
}
