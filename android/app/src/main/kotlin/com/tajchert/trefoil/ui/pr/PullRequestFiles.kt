package com.tajchert.trefoil.ui.pr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DIFF_MAX_ROWS
import com.tajchert.trefoil.core.model.PrDiffPage
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.diff.DiffViewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Files section of the pull request panel: the PR diff, paged by the server's cursor and rendered by DiffViewer. A failed page keeps the
 * pages already loaded and shows a scoped error with a retry. Diff text, paths and server messages are repository content: never log them.
 */

internal const val PR_FILES_FALLBACK_ERROR = "The pull request diff could not be loaded."

/** What the Files section knows: the pages joined into [document], the cursor for the next page, and the read in flight. */
internal data class PrFilesState(
    val document: DiffDocument? = null,
    /** Cursor of the next page; null once the last page is in (or before the first read). */
    val nextCursor: String? = null,
    val loading: Boolean = false,
    /** Scoped, user-readable failure of the last read. */
    val error: String? = null,
    /** The first page was read. */
    val started: Boolean = false,
    /** Joined pages reached [DIFF_MAX_ROWS]; no more are loaded. */
    val rowCapHit: Boolean = false,
) {
    val canLoadMore: Boolean get() = started && nextCursor != null && !loading && !rowCapHit
}

/** Loads the pages of one pull request's diff. Created per pull request head; the screen cancels it when it leaves. */
internal class PrFilesLoader(
    private val scope: CoroutineScope,
    private val load: suspend (cursor: String?) -> PrDiffPage,
) {
    var state by mutableStateOf(PrFilesState())
        private set
    private var job: Job? = null

    /** Reads the first page once; a failed first page is retried by [retry]. */
    fun ensure() { if (!state.started && !state.loading && state.error == null) fetch(null) }

    /** The next page, if the server named one. */
    fun loadMore() { val cursor = state.nextCursor; if (state.canLoadMore && cursor != null) fetch(cursor) }

    /** Repeats the read that failed: the next page when some pages are in, the first otherwise. */
    fun retry() { if (!state.loading && state.error != null) fetch(state.nextCursor.takeIf { state.started }) }

    fun cancel() { job?.cancel(); job = null; if (state.loading) state = state.copy(loading = false) }

    private fun fetch(cursor: String?) {
        job?.cancel()
        state = state.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val page = load(cursor)
                val joined = appendDiffPage(state.document.takeIf { cursor != null }, page.document)
                val capped = joined.rows.size >= DIFF_MAX_ROWS
                state = PrFilesState(
                    document = joined.copy(rowCapHit = joined.rowCapHit || capped, truncated = joined.truncated || page.truncated),
                    nextCursor = page.nextCursor, loading = false, error = null, started = true, rowCapHit = capped,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loading = false, error = e.message?.takeIf { it.isNotBlank() } ?: PR_FILES_FALLBACK_ERROR)
            }
        }
    }
}

/**
 * The Files section. [live] gates reading: offline, the loaded pages stay readable and "Load more" is off. [available] false means the
 * host cannot serve this pull request's diff.
 */
@Composable
internal fun PullRequestFiles(
    files: PrFilesState, live: Boolean, available: Boolean, wrap: Boolean, onWrapChange: (Boolean) -> Unit,
    onLoadMore: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = Tf.colors
    val document = files.document
    Column(modifier.fillMaxSize().background(c.bg)) {
        if (document == null) when {
            !available -> Message("Diff unavailable", "The server cannot show the diff of this pull request.")
            files.error != null -> Column(Modifier.weight(1f).fillMaxWidth()) {
                Message("Could not load the diff", files.error)
                Retry(live, onRetry)
            }
            !live -> Message("Offline", "Reconnect to load the files of this pull request.")
            else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(2.dp), color = c.accent, strokeWidth = 2.dp)
                    Text("Loading files…", style = TrefoilType.meta, color = c.muted)
                }
            }
        } else if (document.files.isEmpty() && document.rows.isEmpty()) {
            Message("No file changes", "This pull request has no changes to show.")
        } else {
            if (!live) Text(
                "Offline. Showing the loaded files; loading more is unavailable.", style = TrefoilType.meta, color = c.muted,
                modifier = Modifier.fillMaxWidth().background(c.s2).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            DiffViewer(document, Modifier.weight(1f).fillMaxWidth(), wrap = wrap, onWrapChange = onWrapChange)
            Footer(files, live, onLoadMore, onRetry)
        }
    }
}

@Composable
private fun Message(title: String, body: String, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
        Text(body, style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Retry(live: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onRetry, enabled = live, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
    }
}

@Composable
private fun Footer(files: PrFilesState, live: Boolean, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    val c = Tf.colors
    val show = files.loading || files.error != null || files.nextCursor != null || files.rowCapHit
    if (!show) return
    Column(
        Modifier.fillMaxWidth().background(c.s1).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (files.error != null) Text(files.error, style = TrefoilType.meta, color = c.red, textAlign = TextAlign.Center)
        when {
            files.loading -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                CircularProgressIndicator(Modifier.padding(2.dp), color = c.accent, strokeWidth = 2.dp)
                Text("Loading more files…", style = TrefoilType.meta, color = c.muted)
            }
            files.rowCapHit -> Text("This pull request is too large to show in full here. Open it on the code host to see the rest.", style = TrefoilType.meta, color = c.muted, textAlign = TextAlign.Center)
            files.error != null -> OutlinedButton(onClick = onRetry, enabled = live, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            files.nextCursor != null -> OutlinedButton(onClick = onLoadMore, enabled = live, modifier = Modifier.heightIn(min = 48.dp)) { Text("Load more files") }
        }
    }
}
