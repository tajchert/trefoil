package com.tajchert.trefoil.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.tajchert.trefoil.core.model.DIFF_MAX_ROWS
import com.tajchert.trefoil.core.model.DiffChange
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffLineKind
import com.tajchert.trefoil.core.model.DiffRow
import com.tajchert.trefoil.ui.InterFamily
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfChip
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilColors
import com.tajchert.trefoil.ui.TrefoilType
import java.util.Locale

/*
 * Diff viewer (plan D3-D6). One LazyColumn over the flattened rows of a DiffDocument: a sticky header per file, hunk
 * rows, and line rows with pinned gutters. In no-wrap mode all code cells share ONE horizontal offset that is applied
 * in a graphicsLayer, so a horizontal drag only invalidates draw layers (no recomposition, no re-measure). Collapsed
 * files fold to their header plus a "Show N lines" row. Diff text and paths are conversation content: never log them.
 */

private val CodeSp = 12.sp
private val GutterSp = 11.sp
private val LineHeightSp = 18.sp
private val EdgePad = 8.dp
private val GutterGap = 6.dp
private const val MonoAdvance = 0.6f // JetBrains Mono advance width, in em

private val CodeStyle = TrefoilType.mono.copy(fontWeight = FontWeight.Normal, fontSize = CodeSp, lineHeight = LineHeightSp)
private val GutterStyle = TrefoilType.mono.copy(fontWeight = FontWeight.Normal, fontSize = GutterSp, lineHeight = LineHeightSp, textAlign = TextAlign.End)

/**
 * Which files the user has folded or unfolded, keyed by [DiffFile.index]. Starts from each file's `collapsedByDefault`.
 * Hoist it (see [rememberDiffCollapseState]) to expand a file from outside or to make [scrollToFile] exact.
 */
@Stable
class DiffCollapseState internal constructor(private val defaults: Map<Int, Boolean>) {
    private val overrides = mutableStateMapOf<Int, Boolean>()

    fun isCollapsed(fileIndex: Int): Boolean = overrides[fileIndex] ?: defaults[fileIndex] ?: false
    fun setCollapsed(fileIndex: Int, collapsed: Boolean) { overrides[fileIndex] = collapsed }
    fun toggle(fileIndex: Int) = setCollapsed(fileIndex, !isCollapsed(fileIndex))
}

/** The collapse state of [document]; starts over when a different document arrives. */
@Composable
fun rememberDiffCollapseState(document: DiffDocument): DiffCollapseState =
    remember(document) { DiffCollapseState(document.files.associate { it.index to it.collapsedByDefault }) }

/**
 * Scrolls the viewer's [LazyListState] so file [fileIndex] ([DiffFile.index]) starts at the top. Pass the viewer's
 * [collapseState] when the user can fold files, so earlier folded files are counted correctly; otherwise each file's
 * `collapsedByDefault` is assumed. Does nothing for an unknown file.
 */
suspend fun LazyListState.scrollToFile(document: DiffDocument, fileIndex: Int, collapseState: DiffCollapseState? = null) {
    val index = diffItemIndexOfFile(document, fileIndex, collapseState?.let { it::isCollapsed }) ?: return
    scrollToItem(index)
}

/** The shared horizontal offset of all code cells in no-wrap mode (pixels scrolled; 0 = left edge). */
@Stable
internal class DiffHorizontalScroll {
    var offset by mutableFloatStateOf(0f)
        private set
    var max by mutableFloatStateOf(0f)
        private set

    val scrollable: ScrollableState = ScrollableState { delta ->
        val next = (offset + delta).coerceIn(0f, max)
        val consumed = next - offset
        offset = next
        consumed
    }

    fun updateMax(value: Float) {
        max = value.coerceAtLeast(0f)
        if (offset > max) offset = max
    }
}

@Immutable
private class DiffPalette(c: TrefoilColors) {
    private val weak = if (c.isDark) .16f else .12f
    private val strong = if (c.isDark) .40f else .30f
    val addBg = c.green.copy(alpha = weak)
    val delBg = c.red.copy(alpha = weak)
    val addWord = c.green.copy(alpha = strong)
    val delWord = c.red.copy(alpha = strong)
    val hunkBg = c.blue.copy(alpha = if (c.isDark) .12f else .10f)
}

@Immutable
private class DiffDims(density: Density, metrics: DiffMetrics) {
    val numberWidth: Dp
    val gutterWidth: Dp
    val markerWidth: Dp
    val gutterPx: Float
    /** Width of the longest (capped) line plus its right padding, in pixels. */
    val contentPx: Float

    init {
        with(density) {
            numberWidth = (metrics.numberDigits * GutterSp.value * MonoAdvance).sp.toDp()
            markerWidth = (CodeSp.value * MonoAdvance).sp.toDp()
            gutterWidth = EdgePad + numberWidth + GutterGap + numberWidth + GutterGap + markerWidth + GutterGap
            gutterPx = gutterWidth.toPx()
            // The ellipsis of a clipped line and a few tab expansions are covered by the extra characters.
            val chars = minOf(metrics.maxChars, DIFF_MAX_RENDER_CHARS) + 8
            contentPx = chars * CodeSp.toPx() * MonoAdvance + EdgePad.toPx()
        }
    }
}

@Composable
fun DiffViewer(
    document: DiffDocument,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    onWrapChange: ((Boolean) -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    collapseState: DiffCollapseState = rememberDiffCollapseState(document),
) {
    val c = Tf.colors
    val palette = remember(c) { DiffPalette(c) }
    val metrics = remember(document) { DiffMetrics.of(document) }
    val density = LocalDensity.current
    val dims = remember(density.density, density.fontScale, metrics) { DiffDims(density, metrics) }
    val visible by remember(document, collapseState) { derivedStateOf { buildVisibleDiffRows(document, collapseState::isCollapsed) } }
    val hScroll = remember(document) { DiffHorizontalScroll() }
    var viewportPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(dims, viewportPx, wrap) {
        hScroll.updateMax(if (wrap) 0f else dims.contentPx - (viewportPx - dims.gutterPx))
    }

    // Code is left-to-right whatever the UI language; this also keeps the shared offset's sign simple.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.background(c.bg)) {
            if (onWrapChange != null) {
                Row(Modifier.fillMaxWidth().background(c.s1).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TfChip("Wrap lines", onClick = { onWrapChange(!wrap) }, selected = wrap, dense = true)
                }
            }
            LazyColumn(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportPx = it.width }
                    .then(if (wrap) Modifier else Modifier.scrollable(hScroll.scrollable, Orientation.Horizontal)),
            ) {
                if (diffBannerCount(document) > 0) {
                    item(key = "banner", contentType = "banner") { DiffBanner(document) }
                }
                val rows = visible
                var i = 0
                while (i < rows.size) {
                    if (rows.isFileHeader(i)) {
                        val header = rows.row(i) as DiffRow.FileHeader
                        stickyHeader(key = header.id, contentType = DiffItemKind.FileHeader) {
                            val file = document.fileByIndex(header.fileIndex)
                            if (file != null) {
                                val collapsed = collapseState.isCollapsed(file.index)
                                DiffFileHeader(file, collapsed, onToggle = { collapseState.toggle(file.index) })
                            }
                        }
                        i++
                    } else {
                        val start = i
                        i++
                        while (i < rows.size && !rows.isFileHeader(i)) i++
                        items(count = i - start, key = { rows.key(start + it) }, contentType = { rows.kind(start + it) }) { offset ->
                            val index = start + offset
                            val row = rows.row(index)
                            when (row) {
                                is DiffRow.Line -> DiffLineRow(row, wrap, hScroll, dims, palette)
                                is DiffRow.Hunk -> DiffHunkRow(row, palette)
                                is DiffRow.Notice -> DiffNoticeRow(row)
                                is DiffRow.FileHeader -> Unit // handled above
                                null -> {
                                    val file = document.fileByIndex(rows.collapsedFileIndex(index))
                                    if (file != null) DiffCollapsedRow(file.lineCount, onShow = { collapseState.setCollapsed(file.index, false) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffBanner(document: DiffDocument) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().background(c.amberSoft).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (document.truncated) Text("This diff was cut off by the server. Changes after this point are not shown.", style = TrefoilType.meta, color = c.amber)
        if (document.rowCapHit) {
            Text("Only the first ${"%,d".format(Locale.US, DIFF_MAX_ROWS)} rows are shown. Later changes are not shown.", style = TrefoilType.meta, color = c.amber)
        }
    }
}

@Composable
private fun DiffFileHeader(file: DiffFile, collapsed: Boolean, onToggle: () -> Unit) {
    val c = Tf.colors
    val collapsible = file.isCollapsible()
    Column(Modifier.fillMaxWidth().background(c.s2)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(
                    if (collapsible) Modifier
                        .clickable(onClickLabel = if (collapsed) "Expand file" else "Collapse file", role = Role.Button, onClick = onToggle)
                        .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" }
                    else Modifier,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collapsible) Icon(if (collapsed) TfIcons.ChevronRight else TfIcons.ChevronDown, null, Modifier.size(16.dp), tint = c.muted)
            else Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val repository = file.repository
                if (repository != null) Text(repository, style = TrefoilType.mono.copy(fontSize = 11.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val oldPath = file.oldPath
                if (oldPath != null && oldPath != file.path) {
                    Text(oldPath, style = TrefoilType.mono, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("→ ${file.path}", style = TrefoilType.mono, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(file.path, style = TrefoilType.mono, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChangeBadge(file.change)
                    if (file.change != DiffChange.Binary) {
                        Text("+${file.additions}", style = TrefoilType.mono, color = c.green)
                        Text("−${file.deletions}", style = TrefoilType.mono, color = c.red)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
private fun ChangeBadge(change: DiffChange) {
    val c = Tf.colors
    val (label, fg, bg) = when (change) {
        DiffChange.Added -> Triple("Added", c.green, c.green.copy(alpha = .16f))
        DiffChange.Deleted -> Triple("Deleted", c.red, c.red.copy(alpha = .16f))
        DiffChange.Modified -> Triple("Modified", c.blue, c.blueSoft)
        DiffChange.Renamed -> Triple("Renamed", c.amber, c.amberSoft)
        DiffChange.Binary -> Triple("Binary", c.muted, c.s3)
    }
    Text(
        label.uppercase(Locale.ROOT), style = TrefoilType.section.copy(fontSize = 10.sp, lineHeight = 14.sp), color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun DiffHunkRow(row: DiffRow.Hunk, palette: DiffPalette) {
    Text(
        row.header, style = TrefoilType.mono.copy(fontSize = 11.sp, lineHeight = LineHeightSp), color = Tf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(palette.hunkBg).padding(horizontal = EdgePad, vertical = 3.dp),
    )
}

@Composable
private fun DiffNoticeRow(row: DiffRow.Notice) {
    Text(
        row.text, style = TrefoilType.meta.copy(fontStyle = FontStyle.Italic), color = Tf.colors.muted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun DiffCollapsedRow(lineCount: Int, onShow: () -> Unit) {
    val c = Tf.colors
    val label = if (lineCount > 0) "Show $lineCount ${if (lineCount == 1) "line" else "lines"}" else "Show changes"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).background(c.s1).clickable(role = Role.Button, onClick = onShow).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TrefoilType.meta, color = c.accent)
    }
}

@Composable
private fun DiffLineRow(row: DiffRow.Line, wrap: Boolean, hScroll: DiffHorizontalScroll, dims: DiffDims, palette: DiffPalette) {
    val c = Tf.colors
    val background = when (row.kind) {
        DiffLineKind.Add -> palette.addBg
        DiffLineKind.Del -> palette.delBg
        DiffLineKind.Context -> Color.Transparent
    }
    val word = if (row.kind == DiffLineKind.Del) palette.delWord else palette.addWord
    // Built once per row and cached while it stays composed; a new document brings new row objects.
    val text = remember(row, word) { buildDiffLineText(row.text, row.wordRanges, word) }
    val marker = when (row.kind) {
        DiffLineKind.Add -> "+"
        DiffLineKind.Del -> "-"
        DiffLineKind.Context -> ""
    }
    val markerColor = when (row.kind) {
        DiffLineKind.Add -> c.green
        DiffLineKind.Del -> c.red
        DiffLineKind.Context -> c.muted
    }
    Row(Modifier.fillMaxWidth().background(background).padding(start = EdgePad), verticalAlignment = Alignment.Top) {
        Text(row.oldNumber?.toString().orEmpty(), style = GutterStyle, color = c.muted, softWrap = false, maxLines = 1, modifier = Modifier.width(dims.numberWidth))
        Spacer(Modifier.width(GutterGap))
        Text(row.newNumber?.toString().orEmpty(), style = GutterStyle, color = c.muted, softWrap = false, maxLines = 1, modifier = Modifier.width(dims.numberWidth))
        Spacer(Modifier.width(GutterGap))
        Text(marker, style = CodeStyle, color = markerColor, softWrap = false, maxLines = 1, modifier = Modifier.width(dims.markerWidth))
        Spacer(Modifier.width(GutterGap))
        if (wrap) {
            Text(text, style = CodeStyle, color = c.text, modifier = Modifier.weight(1f).padding(end = EdgePad))
        } else {
            Box(Modifier.weight(1f).clipToBounds()) {
                Text(
                    text, style = CodeStyle, color = c.text, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { translationX = -hScroll.offset },
                )
            }
        }
    }
}
