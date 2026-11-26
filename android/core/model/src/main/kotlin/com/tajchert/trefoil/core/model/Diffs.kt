package com.tajchert.trefoil.core.model

/**
 * Checkpoint diffs of a thread (T3 projection `checkpoints`) and the parsed, flattened form the diff viewer renders.
 * Diff text and file paths are conversation content: never log them.
 */

/** Status of a T3 checkpoint (`ready|missing|error|stale`); unknown wire values map to [Unknown]. */
enum class CheckpointStatus { Ready, Missing, Error, Stale, Unknown }

/** One file of a checkpoint summary (T3 `CheckpointFileSummary`). [kind] is the raw wire string. */
data class CheckpointFile(val path: String, val kind: String, val additions: Int, val deletions: Int)

/**
 * A completed turn's checkpoint. [turnCount] is T3 `appRunOrdinal` (1-based); checkpoints without it are not shown.
 * [files] holds at most 200 entries; [filesTotal] is set when the wire truncated the list.
 */
data class ThreadCheckpoint(
    val id: String,
    val turnCount: Int,
    val status: CheckpointStatus,
    val files: List<CheckpointFile>,
    val filesTotal: Int? = null,
    val capturedAt: String? = null,
) {
    val additions: Int get() = files.sumOf { it.additions }
    val deletions: Int get() = files.sumOf { it.deletions }
    val fileCount: Int get() = filesTotal ?: files.size
}

enum class DiffChange { Added, Deleted, Modified, Renamed, Binary }

/** One file of a parsed diff. [firstRowIndex] until [endRowIndex] (exclusive) are its rows in [DiffDocument.rows], header included. */
data class DiffFile(
    val index: Int,
    val path: String,
    val oldPath: String? = null,
    val change: DiffChange,
    val additions: Int,
    val deletions: Int,
    val isTest: Boolean = false,
    val isGenerated: Boolean = false,
    /** Over 400 lines or 24_000 characters, or generated: the viewer starts it collapsed. */
    val collapsedByDefault: Boolean = false,
    val firstRowIndex: Int,
    val endRowIndex: Int,
    /** Optional repository label for multi-repository threads; null for single-repository diffs. */
    val repository: String? = null,
)

enum class DiffLineKind { Context, Add, Del }

/** A flattened diff row. [id] is unique and stable within one [DiffDocument]; [fileIndex] is -1 for document-level notices. */
sealed interface DiffRow {
    val id: Long
    val fileIndex: Int

    data class FileHeader(override val id: Long, override val fileIndex: Int) : DiffRow
    data class Hunk(override val id: Long, override val fileIndex: Int, val header: String) : DiffRow
    data class Line(
        override val id: Long,
        override val fileIndex: Int,
        val kind: DiffLineKind,
        val oldNumber: Int?,
        val newNumber: Int?,
        val text: String,
        /** Changed character ranges as flat [start, endExclusive] pairs into [text]; null when not computed. */
        val wordRanges: IntArray? = null,
        /** The source line had `\ No newline at end of file` after it. */
        val noNewlineAtEnd: Boolean = false,
    ) : DiffRow {
        override fun equals(other: Any?) = other is Line && other.id == id && other.fileIndex == fileIndex && other.kind == kind &&
            other.oldNumber == oldNumber && other.newNumber == newNumber && other.text == text &&
            other.noNewlineAtEnd == noNewlineAtEnd && (other.wordRanges?.contentEquals(wordRanges) ?: (wordRanges == null))
        override fun hashCode() = id.hashCode()
    }
    data class Notice(override val id: Long, override val fileIndex: Int, val text: String) : DiffRow
}

/**
 * A parsed unified diff. [truncated] is true when the server appended `[truncated]`; [rowCapHit] when the parser stopped
 * at [DIFF_MAX_ROWS]. Both show a banner.
 */
data class DiffDocument(
    val files: List<DiffFile>,
    val rows: List<DiffRow>,
    val truncated: Boolean = false,
    val rowCapHit: Boolean = false,
) {
    companion object { val Empty = DiffDocument(emptyList(), emptyList()) }
}

const val DIFF_MAX_ROWS = 60_000
const val LARGE_DIFF_LINE_THRESHOLD = 400
const val LARGE_DIFF_CHARACTER_THRESHOLD = 24_000
const val WORD_DIFF_MAX_LINE_LENGTH = 1_000
