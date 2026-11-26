package com.tajchert.trefoil.ui.diff

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffRow
import com.tajchert.trefoil.core.model.ThreadCheckpoint
import com.tajchert.trefoil.core.model.orderDiffFilesForReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * State and pure helpers behind the Changes screen. Diff text, paths and server messages are conversation content: never log them.
 */

/** The turns of [this], newest first; the Changes screen lists them in that order. */
internal fun List<ThreadCheckpoint>.newestFirst(): List<ThreadCheckpoint> = sortedByDescending { it.turnCount }

/** The turn the screen shows: [requested] while it still exists, otherwise the newest one; null without checkpoints. */
internal fun resolveChangesTurn(turns: List<ThreadCheckpoint>, requested: Int?): Int? =
    requested?.takeIf { wanted -> turns.any { it.turnCount == wanted } } ?: turns.maxOfOrNull { it.turnCount }

internal fun filesLabel(count: Int): String = if (count == 1) "1 file" else "$count files"

/**
 * [this] with its files in review order ([orderDiffFilesForReview]) and, with [hideTests], without test files. Returns [this]
 * when that changes nothing. Rows are renumbered ([DiffFile.index], [DiffFile.firstRowIndex] and [DiffRow.fileIndex]) so the
 * result is a valid document of its own; row ids stay unique. Rows that belong to no file (the row-cap notice) stay at the end.
 */
internal fun DiffDocument.arrangedForReview(hideTests: Boolean): DiffDocument {
    if (files.isEmpty()) return this
    val queues = HashMap<String, ArrayDeque<DiffFile>>()
    for (file in files) queues.getOrPut(file.path) { ArrayDeque() }.addLast(file)
    val ordered = ArrayList<DiffFile>(files.size)
    for (path in orderDiffFilesForReview(files.map { it.path })) queues[path]?.removeFirstOrNull()?.let(ordered::add)
    // A path the ordering dropped is never lost: it follows the ordered ones, in its original position.
    queues.values.flatten().sortedBy { it.index }.forEach(ordered::add)
    val kept = if (hideTests) ordered.filterNot { it.isTest } else ordered
    if (kept == files) return this

    val rebuilt = ArrayList<DiffRow>(rows.size)
    val head = files.minOf { it.firstRowIndex }.coerceIn(0, rows.size)
    for (r in 0 until head) rebuilt += rows[r]
    val arranged = ArrayList<DiffFile>(kept.size)
    kept.forEachIndexed { newIndex, file ->
        val first = rebuilt.size
        val end = file.endRowIndex.coerceIn(0, rows.size)
        for (r in file.firstRowIndex.coerceIn(0, end) until end) rebuilt += rows[r].withFileIndex(newIndex)
        arranged += file.copy(index = newIndex, firstRowIndex = first, endRowIndex = rebuilt.size)
    }
    for (r in files.maxOf { it.endRowIndex }.coerceIn(0, rows.size) until rows.size) rebuilt += rows[r]
    return copy(files = arranged, rows = rebuilt)
}

private fun DiffRow.withFileIndex(index: Int): DiffRow = when (this) {
    is DiffRow.FileHeader -> copy(fileIndex = index)
    is DiffRow.Hunk -> copy(fileIndex = index)
    is DiffRow.Line -> copy(fileIndex = index)
    is DiffRow.Notice -> copy(fileIndex = index)
}

internal sealed interface TurnDiffState {
    data object Loading : TurnDiffState
    data class Loaded(val document: DiffDocument) : TurnDiffState
    /** [message] comes from the failed request's exception; it may be the server's own text. */
    data class Failed(val message: String) : TurnDiffState
}

internal const val CHANGES_FALLBACK_ERROR = "Could not load this diff."

/**
 * Loads and holds the diffs of the turns the user opened, for one thread (create it per environment and thread). A completed
 * turn's diff never changes, so a loaded one stays until [reload]; at most [maxLoaded] are kept (least recently shown go first)
 * so the heap stays bounded. Memory only. [load] must read through the session (which applies its own selection guard).
 */
@Stable
internal class TurnDiffLoader(
    private val scope: CoroutineScope,
    private val load: suspend (turnCount: Int) -> DiffDocument,
    private val maxLoaded: Int = 4,
) {
    private val _states = mutableStateMapOf<Int, TurnDiffState>()
    private val jobs = HashMap<Int, Job>()
    private val recency = ArrayDeque<Int>()

    /** What is known about [turnCount]: null when nothing was asked for yet. */
    fun stateOf(turnCount: Int): TurnDiffState? = _states[turnCount]

    /** Starts loading [turnCount] unless it is loaded or loading. A failed turn is retried by [reload], not here. */
    fun ensure(turnCount: Int) {
        touch(turnCount)
        if (_states[turnCount] == null) start(turnCount)
    }

    /** Reads [turnCount] again, dropping what was loaded; a request already running is replaced. */
    fun reload(turnCount: Int) {
        touch(turnCount)
        start(turnCount)
    }

    /** Cancels every running read (the screen left, or the thread changed). Loaded diffs stay. */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        for (turn in _states.keys.toList()) if (_states[turn] is TurnDiffState.Loading) _states.remove(turn)
    }

    private fun start(turnCount: Int) {
        jobs.remove(turnCount)?.cancel()
        _states[turnCount] = TurnDiffState.Loading
        jobs[turnCount] = scope.launch {
            val result = try {
                TurnDiffState.Loaded(load(turnCount))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TurnDiffState.Failed(e.message?.takeIf { it.isNotBlank() } ?: CHANGES_FALLBACK_ERROR)
            }
            _states[turnCount] = result
            jobs.remove(turnCount)
            trim()
        }
    }

    private fun touch(turnCount: Int) {
        recency.remove(turnCount)
        recency.addLast(turnCount)
    }

    private fun trim() {
        var loaded = recency.count { _states[it] is TurnDiffState.Loaded }
        val iterator = recency.iterator()
        while (loaded > maxLoaded && iterator.hasNext()) {
            val turn = iterator.next()
            if (_states[turn] is TurnDiffState.Loaded) { _states.remove(turn); iterator.remove(); loaded-- }
        }
    }
}
